/**
 * GC9A01 240x240 - Simplified test
 * Wiring: VCC→3.3V, GND→GND, SCL→GPIO12, SDA→GPIO11,
 *         RST→GPIO10, DC→GPIO9, CS→GPIO13, BL→3.3V
 */
#include <Arduino.h>
#include <SPI.h>
#include <TFT_eSPI.h>

TFT_eSPI tft = TFT_eSPI();

void setup()
{
    // Simple test: just cycle colors
    tft.init();
    tft.setRotation(0);
    tft.invertDisplay(true); // Some GC9A01 need inversion

    while (true)
    {
        tft.fillScreen(TFT_RED);
        delay(2000);
        tft.fillScreen(TFT_GREEN);
        delay(2000);
        tft.fillScreen(TFT_BLUE);
        delay(2000);
        tft.fillScreen(TFT_WHITE);
        delay(2000);
        tft.fillScreen(TFT_BLACK);
        delay(2000);
    }
}

void loop() {}