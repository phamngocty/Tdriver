/*
 * firmware-esp32-s3
 * ==================
 * Hệ thống dẫn đường xe máy thông minh
 * Board: ESP32-S3 DevKitC-1 (16 MB Flash, 16 MB PSRAM)
 * Màn hình: GC9A01 1.28" 240×240 round
 * Giao tiếp: BLE với Android app
 *
 * PlatformIO + Arduino framework
 */

#include <Arduino.h>
#include <TFT_eSPI.h>
#include <ESP32Time.h>

#include "ble_manager.h"
#include "jpeg_handler.h"
#include "mode_manager.h"
#include "ui_renderer.h"
#include "MyFont.h"

// ===================================================================
// Hằng số
// ===================================================================

// Chân GPIO (board: ESP32-S3-N16R16)
#define PIN_LED_STATUS GPIO_NUM_21 // LED trạng thái (GPIO tự do)
#define PIN_BTN_MODE GPIO_NUM_0    // Nút MODE (BOOT button, active low)
#define PIN_BTN_ZOOM GPIO_NUM_17   // Nút ZOOM (GPIO tự do trên header)
#define PIN_BL GPIO_NUM_2          // Backlight PWM
#define PIN_BAT_ADC GPIO_NUM_3     // ADC1_CH2 - đo điện áp ắc quy (cầu phân áp 100k/27k)

// BLE
#define BLE_DEVICE_NAME "S3-Navigator"

// Cầu phân áp: R1=100kΩ, R2=27kΩ → V_out = V_bat * 27/(100+27) ≈ V_bat / 4.704
#define VOLTAGE_DIVIDER_RATIO 4.7f

// Chu kỳ
#define HUD_REDRAW_MS 200      // HUD redraw interval (ms)
#define STATUS_REDRAW_MS 1000  // STATUS redraw interval
#define BATTERY_READ_MS 5000   // Đọc điện áp mỗi 5 giây
#define STATUS_SEND_MS 10000   // Gửi BLE status mỗi 10 giây
#define RECONNECT_WAIT_MS 5000 // Chờ kết nối lại

// ===================================================================
// Đối tượng toàn cục
// ===================================================================

TFT_eSPI tft;                                    // Màn hình
ESP32Time rtc;                                   // RTC từ Unix timestamp
BleManager ble;                                  // BLE GATT server
JpegHandler jpeg(&tft);                          // Giải mã JPEG
ModeManager modeMgr(PIN_BTN_MODE, PIN_BTN_ZOOM); // Nút bấm & menu
UiRenderer ui(&tft);                             // Vẽ giao diện

// ===================================================================
// Trạng thái
// ===================================================================

static bool connected = false;
static Mode lastMode = MODE_HUD;
static uint32_t lastHudDraw = 0;
static uint32_t lastStatusDraw = 0;

// Bộ đệm navString để tránh đọc đi đọc lại
static String cachedNavString;
static uint8_t cachedIconIndex = 0;
static String cachedSpeed;

// ===================================================================
// Forward declarations
// ===================================================================

static void onSendCtrl(uint8_t byte);
static void applyBrightness(int level);
static void setupBacklight();
static void checkBleConnection();
static void handleBleData();
static void renderCurrentMode();
static float readBatteryVoltage();
static float readAverageVoltage(int samples = 10);
static void sendBatteryStatus();

// ===================================================================
// Setup
// ===================================================================

void setup()
{
    Serial.begin(115200);
    delay(500);
    Serial.println("\n=== S3 Navigator Firmware v1.0 ===");

    // --- LED trạng thái ---
    pinMode(PIN_LED_STATUS, OUTPUT);
    digitalWrite(PIN_LED_STATUS, LOW);

    // --- Backlight ---
    setupBacklight();

    // --- TFT ---
    tft.begin();
    tft.setRotation(0); // Tuỳ chỉnh theo hướng gắn
    tft.fillScreen(TFT_BLACK);
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextFont(FONT_MEDIUM);
    tft.setTextDatum(MC_DATUM);
    tft.drawString("Starting...", 120, 120);
    Serial.println("[TFT] Initialized");

    // --- BLE ---
    ble.begin(BLE_DEVICE_NAME);
    Serial.println("[BLE] Advertising started");

    // --- Mode Manager ---
    modeMgr.setTft(&tft);
    modeMgr.setOnSendCtrl(onSendCtrl);
    modeMgr.begin();
    Serial.println("[MODE] Manager initialized");

    // --- RTC mặc định (sẽ được đồng bộ qua BLE) ---
    rtc.setTime(0);

    // --- ADC (đo điện áp ắc quy) ---
    analogReadResolution(12);                       // 12-bit
    analogSetPinAttenuation(PIN_BAT_ADC, ADC_11db); // 0-3.3V

    Serial.println("=== System ready ===");
    tft.fillScreen(TFT_BLACK);
}

// ===================================================================
// Loop
// ===================================================================

static uint32_t lastStatusSend = 0;

void loop()
{
    uint32_t now = millis();

    // 1. Xử lý nút bấm
    modeMgr.tick();

    // 2. Kiểm tra kết nối BLE
    checkBleConnection();

    // 3. Xử lý dữ liệu BLE mới
    handleBleData();

    // 4. Vẽ giao diện theo chế độ
    renderCurrentMode();

    // 5. Gửi trạng thái qua BLE định kỳ (khi đã kết nối)
    if (ble.isConnected() && now - lastStatusSend >= STATUS_SEND_MS)
    {
        lastStatusSend = now;
        sendBatteryStatus();
    }

    // 6. Nghỉ ngắn để tiết kiệm năng lượng
    delay(10);
}

// ===================================================================
// Kiểm tra / xử lý kết nối BLE
// ===================================================================

static void checkBleConnection()
{
    bool nowConnected = ble.isConnected();
    if (nowConnected != connected)
    {
        connected = nowConnected;
        if (connected)
        {
            Serial.println("[SYS] BLE connected");
            digitalWrite(PIN_LED_STATUS, HIGH);
        }
        else
        {
            Serial.println("[SYS] BLE disconnected");
            digitalWrite(PIN_LED_STATUS, LOW);
        }
    }
}

// ===================================================================
// Xử lý dữ liệu nhận từ App qua BLE
// ===================================================================

static void handleBleData()
{
    BleNavData *data = ble.getData();

    // --- Cài đặt (brightness, theme) ---
    if (data->settingsUpdated)
    {
        applyBrightness(data->brightness);
        ble.clearSettingsFlag();
    }

    // --- Thời gian ---
    if (data->timeUpdated)
    {
        rtc.setTime(data->unixTime);
        ble.clearTimeFlag();
    }

    // --- Dữ liệu dẫn đường ---
    if (data->navUpdated)
    {
        cachedNavString = data->navString;
        cachedIconIndex = data->iconIndex;
        ble.clearNavFlag();
    }

    // --- Tốc độ ---
    if (data->speedUpdated)
    {
        cachedSpeed = data->speedStr;
        ble.clearSpeedFlag();
    }

    // --- Ảnh JPEG ---
    if (data->newJpegReady)
    {
        if (modeMgr.getCurrentMode() == MODE_MAP)
        {
            Serial.println("[SYS] Processing JPEG...");
            jpeg.processJPEG(data->jpegBuffer, data->jpegSize);
        }
        else
        {
            Serial.println("[SYS] Discarding JPEG (not in MAP mode)");
        }
        // Luôn giải phóng buffer để tránh rò rỉ
        if (data->jpegBuffer)
        {
            free(data->jpegBuffer);
            data->jpegBuffer = nullptr;
        }
        data->jpegSize = 0;
        data->jpegReceived = 0;
        ble.clearJpegFlag();
    }
}

// ===================================================================
// Vẽ giao diện theo chế độ hiện tại
// ===================================================================

static void renderCurrentMode()
{
    Mode currentMode = modeMgr.getCurrentMode();
    uint32_t now = millis();

    // Nếu đang hiển thị menu, không vẽ UI chính
    if (modeMgr.isMenuActive())
        return;

    // Xoá màn hình khi chuyển chế độ
    if (currentMode != lastMode)
    {
        tft.fillScreen(TFT_BLACK);
        lastMode = currentMode;
        // Reset interval để vẽ ngay
        lastHudDraw = 0;
        lastStatusDraw = 0;
    }

    switch (currentMode)
    {
    case MODE_HUD:
    {
        if (now - lastHudDraw < HUD_REDRAW_MS)
            return;
        lastHudDraw = now;
        ui.drawHUD(
            cachedNavString.c_str(),
            cachedIconIndex,
            cachedSpeed.length() > 0 ? cachedSpeed.c_str() : "0");
        break;
    }

    case MODE_MAP:
    {
        // MAP mode: ảnh được vẽ bởi JPEG callback khi có dữ liệu mới
        break;
    }

    case MODE_STATUS:
    {
        if (now - lastStatusDraw < STATUS_REDRAW_MS)
            return;
        lastStatusDraw = now;

        BleNavData *data = ble.getData();

        // Đo điện áp (cache 5 giây)
        static uint32_t lastBattRead = 0;
        static float cachedVolt = 0;
        if (now - lastBattRead > BATTERY_READ_MS)
        {
            cachedVolt = readAverageVoltage(10);
            lastBattRead = now;
            Serial.printf("[BAT] volt=%.2f\n", cachedVolt);
        }

        ui.drawStatus(
            data->weatherJson.c_str(),
            rtc.getEpoch(),
            cachedVolt);
        if (data->weatherUpdated)
        {
            ble.clearWeatherFlag();
        }
        break;
    }

    default:
        break;
    }
}

// ===================================================================
// Gửi lệnh điều khiển lên App
// ===================================================================

static void onSendCtrl(uint8_t byte)
{
    ble.sendCtrl(byte);
}

// ===================================================================
// Điều khiển backlight bằng PWM
// ===================================================================

static void setupBacklight()
{
    // LEDC channel 0, 8-bit, 5 kHz
    ledcSetup(0, 5000, 8);
    ledcAttachPin(PIN_BL, 0);
    ledcWrite(0, 200); // ~80% brightness mặc định
}

static void applyBrightness(int level)
{
    level = constrain(level, 0, 100);
    // map 0-100 → 0-255 (PWM 8-bit)
    uint8_t pwm = map(level, 0, 100, 0, 255);
    ledcWrite(0, pwm);
    Serial.printf("[BL] Brightness: %d%% (PWM=%d)\n", level, pwm);
}

// ===================================================================
// Đo điện áp ắc quy
// ===================================================================

static float readBatteryVoltage()
{
    int adcRaw = analogRead(PIN_BAT_ADC);
    float voltageADC = (adcRaw / 4095.0f) * 3.3f;
    float batteryVoltage = voltageADC * VOLTAGE_DIVIDER_RATIO;
    return batteryVoltage;
}

static float readAverageVoltage(int samples)
{
    float sum = 0;
    for (int i = 0; i < samples; i++)
    {
        sum += readBatteryVoltage();
        delay(10);
    }
    return sum / samples;
}

// ===================================================================
// Gửi trạng thái thiết bị qua BLE
// ===================================================================

static void sendBatteryStatus()
{
    if (!ble.isConnected())
        return;

    float vBat = readAverageVoltage(5);
    String status = "mode=" + String(MODE_NAMES[modeMgr.getCurrentMode()]) +
                    "\nvoltage=" + String(vBat, 1);
    ble.sendStatus(status);
    Serial.printf("[BLE] Status sent: %s\n", status.c_str());
}
