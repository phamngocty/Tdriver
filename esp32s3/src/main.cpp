/**
 * ESP32-S3 Motorcycle HUD Display Firmware
 * Board: ESP32-S3 DevKitC-1 (N16R8)
 * Display: GC9A01 240x240 round SPI
 * BLE: NimBLE-Arduino (Peripheral)
 *
 * Architecture:
 * - ble_manager: BLE GATT server, data reception
 * - jpeg_handler: JPEG accumulation + decoding to TFT
 * - mode_manager: Button handling, mode state machine, overlay menu
 * - ui_renderer: HUD/STATUS rendering, backlight PWM
 * - icons.h: Bitmap icons for turn arrows and weather
 *
 * Display modes:
 * - HUD (0): Turn arrow, street name, speed, distance, ETA
 * - MAP (1): Full-screen JPEG map from phone
 * - STATUS (2): Clock, weather, battery voltage
 */

#include <Arduino.h>
#include <TFT_eSPI.h>
#include <ESP32Time.h>
#include <FontMaker.h>

#include "ble_manager.h"
#include "jpeg_handler.h"
#include "mode_manager.h"
#include "ui_renderer.h"

// ============================================================
// Global objects
// ============================================================
TFT_eSPI tft = TFT_eSPI();

// ============================================================
// FontMaker pixel callback (needs tft declared above)
// ============================================================
void setpx(int16_t x, int16_t y, uint16_t color)
{
    tft.drawPixel(x, y, color);
}

// MakeFont instance for all text rendering
MakeFont myfont(&setpx);
ESP32Time rtc; // Software RTC, set via BLE CHA_TIME

// Timing
unsigned long lastSecondTick = 0;
unsigned long lastStatusReportMs = 0;
unsigned long lastAdcReadMs = 0;
bool needsFullRedraw = true;

// Current battery reading
float g_batteryVoltage = 12.0f;

// ============================================================
// TFT_eSPI User Setup — GC9A01 round display
// These would normally go in User_Setup.h, but we define them
// at compile time via build_flags if needed.
// ============================================================
#ifndef TFT_CS
#define TFT_CS 10
#define TFT_DC 13
#define TFT_RST 14
#define TFT_MOSI 11
#define TFT_SCLK 12
#define TFT_BL 2
#define TOUCH_CS -1
#define TFT_MISO -1
#endif

// Touch I2C pins
#ifndef TOUCH_SDA
#define TOUCH_SDA 6
#define TOUCH_SCL 7
#define TOUCH_INT 5
#define TOUCH_RST_PIN 1
#endif

// ============================================================
// Setup
// ============================================================
void setup()
{
    // Initialize USB CDC serial for debugging
    Serial.begin(115200);
    delay(500);
    Serial.println("\n\n=== MotoHUD ESP32-S3 starting ===");

    // Check PSRAM
    if (psramFound())
    {
        Serial.printf("PSRAM found: %d bytes total\n", ESP.getPsramSize());
    }
    else
    {
        Serial.println("WARNING: PSRAM not found!");
    }

    // Initialize TFT display
    tft.begin();
    tft.setRotation(0);
    tft.fillScreen(TFT_BLACK);
    tft.setTextWrap(false);

    // Initialize FontMaker with a Unicode-compatible font
    myfont.set_font(Microsoft_Sans_Serif_14);

    // Turn on backlight
    pinMode(TFT_BL, OUTPUT);
    digitalWrite(TFT_BL, HIGH);

    Serial.println("TFT initialized");

    // Show splash using FontMaker
    myfont.print(60, 100, (char *)"MotoHUD", TFT_WHITE, TFT_BLACK);
    myfont.print(60, 130, (char *)"Connecting...", TFT_WHITE, TFT_BLACK);
    delay(500);

    // Initialize UI renderer
    uiRenderer.init(&tft);

    // Initialize JPEG handler (allocates PSRAM buffer)
    if (!jpegHandler.init())
    {
        Serial.println("FATAL: JPEG handler init failed!");
    }

    // Initialize BLE server
    bleManager.init("MotoHUD");
    Serial.println("BLE advertising started");

    // Initialize button manager
    modeManager.init();
    Serial.println("Button manager initialized");

    // Configure ADC for battery voltage
    // GPIO4 is ADC1_CH3, 12-bit, 0-3.3V input
    // Voltage divider 1:6 — 15V max -> 2.5V at ADC pin

    // Initial timestamp
    lastSecondTick = millis();
    lastStatusReportMs = millis();
    lastAdcReadMs = millis();
    needsFullRedraw = true;

    Serial.println("=== Setup complete ===");
}

// ============================================================
// Main Loop
// ============================================================
void loop()
{
    unsigned long now = millis();

    // 1. Button handling
    modeManager.tick();

    // 2. Periodic tasks every ~1 second
    if (now - lastSecondTick >= 1000)
    {
        lastSecondTick = now;

        // Read battery ADC
        int adcRaw = analogReadMilliVolts(4); // GPIO4 (BAT_ADC)
        float vAdc = adcRaw / 1000.0f;
        g_batteryVoltage = vAdc * 6.0f;
        if (g_batteryVoltage < 5.0f)
            g_batteryVoltage = 12.0f; // Sanity clamp

        // Send status report every 5 seconds
        if (now - lastStatusReportMs >= 5000)
        {
            lastStatusReportMs = now;
            bleManager.sendStatusReport(
                (uint8_t)modeManager.getCurrentMode(),
                g_batteryVoltage,
                bleManager.getRssi());
        }
    }

    // 3. Handle data from BLE callbacks
    DisplayMode currentMode = modeManager.getCurrentMode();

    // --- New JPEG received ---
    if (g_newJPEG && currentMode == MODE_MAP)
    {
        g_newJPEG = false;
        Serial.println("Processing new JPEG...");
        jpegHandler.decodeToTFT();
        needsFullRedraw = false;
    }

    // --- New HUD data received ---
    if (g_newNavData && currentMode == MODE_HUD)
    {
        g_newNavData = false;
        needsFullRedraw = true;
    }

    // --- New weather data received ---
    if (g_newWeatherData && currentMode == MODE_STATUS)
    {
        g_newWeatherData = false;
        needsFullRedraw = true;
    }

    // --- New GPS speed received ---
    if (g_newGpsSpeed && currentMode == MODE_HUD)
    {
        g_newGpsSpeed = false;
        needsFullRedraw = true;
    }

    // 4. Full redraw when needed (mode change or data update)
    if (needsFullRedraw && !modeManager.isMenuActive())
    {
        needsFullRedraw = false;
        switch (currentMode)
        {
        case MODE_HUD:
            uiRenderer.renderHUD();
            break;
        case MODE_MAP:
            // Map is rendered by JPEG decode, but draw placeholder if no map
            // If no JPEG pending, show a "No Map" message
            break;
        case MODE_STATUS:
            uiRenderer.renderStatus();
            break;
        }
    }

    // 5. Periodic STATUS refresh every second
    if (currentMode == MODE_STATUS && (now - lastSecondTick < 100))
    {
        uiRenderer.renderStatus();
    }

    // 6. Update BLE indicator on all modes
    static unsigned long lastBleIndicator = 0;
    if (now - lastBleIndicator >= 2000)
    {
        lastBleIndicator = now;
        uiRenderer.drawBLEIndicator(bleManager.isConnected(), bleManager.getRssi());
    }

    // 7. Handle overlay menu redraw
    if (modeManager.isMenuActive())
    {
        // Menu overlay redraw is triggered by button callbacks
    }

    // Brief yield for ESP32 background tasks
    delay(5);
}
