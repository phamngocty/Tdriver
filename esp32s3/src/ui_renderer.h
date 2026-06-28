#ifndef UI_RENDERER_H
#define UI_RENDERER_H

#include <Arduino.h>
#include <TFT_eSPI.h>
#include <FontMaker.h>

// Colors
#define COLOR_BLACK TFT_BLACK
#define COLOR_WHITE TFT_WHITE
#define COLOR_RED TFT_RED
#define COLOR_GREEN TFT_GREEN
#define COLOR_BLUE TFT_BLUE
#define COLOR_YELLOW TFT_YELLOW
#define COLOR_CYAN TFT_CYAN
#define COLOR_ORANGE 0xFD20
#define COLOR_DARK_GRAY 0x4A69
#define COLOR_GRAY 0x8410

class UIRenderer
{
public:
    void init(TFT_eSPI *tft);

    // Full mode renders
    void renderHUD();
    void renderMap();
    void renderStatus();

    // Helper: draw BLE status indicator (top-right corner)
    void drawBLEIndicator(bool connected, int8_t rssi);

    // Helper: draw bottom status bar
    void drawStatusBar(uint8_t mode, float voltage);

    // Draw overlay menu sprite
    void drawMenuOverlay(bool active, int selection);

    // Set backlight PWM (TFT_BL = GPIO 2)
    void setBacklight(uint8_t level);

private:
    TFT_eSPI *m_tft;

    // HUD layout constants
    static const int HUD_ICON_X = 88; // Center of 240
    static const int HUD_ICON_Y = 10;
    static const int HUD_SPEED_X = 120;
    static const int HUD_SPEED_Y = 100;
    static const int HUD_DIST_X = 120;
    static const int HUD_DIST_Y = 170;
    static const int HUD_ETA_X = 120;
    static const int HUD_ETA_Y = 210;
    static const int HUD_ROAD_X = 120;
    static const int HUD_ROAD_Y = 80;

    // STATUS layout
    static const int ST_TIME_X = 120;
    static const int ST_TIME_Y = 40;
    static const int ST_WTHR_X = 120;
    static const int ST_WTHR_Y = 110;
    static const int ST_TEMP_X = 120;
    static const int ST_TEMP_Y = 150;
    static const int ST_BAT_X = 120;
    static const int ST_BAT_Y = 200;

    // Safe area for circular display (radius = 120, center = 120,120)
    // Text should avoid corners
};

extern UIRenderer uiRenderer;

#endif // UI_RENDERER_H
