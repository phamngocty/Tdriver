#include "ui_renderer.h"
#include "ble_manager.h"
#include "icons.h"
#include <ESP32Time.h>

UIRenderer uiRenderer;

extern ESP32Time rtc;
extern TFT_eSPI tft;
extern MakeFont myfont;

void UIRenderer::init(TFT_eSPI *t)
{
    m_tft = t;
    setBacklight(g_backlightLevel);
}

void UIRenderer::setBacklight(uint8_t level)
{
    // PWM on channel 0, TFT_BL, 5kHz, 8-bit
    ledcSetup(0, 5000, 8);
    ledcAttachPin(TFT_BL, 0);
    ledcWrite(0, level);
}

// ============================================================
// HUD Mode rendering
// ============================================================
void UIRenderer::renderHUD()
{
    m_tft->fillScreen(COLOR_BLACK);

    // Draw turn icon (top-center)
    if (g_tbtBitmapValid)
    {
        // Draw bitmap received from app (48x48 at center)
        int iconX = (240 - 48) / 2;
        int iconY = 10;
        m_tft->drawBitmap(iconX, iconY, g_tbtBitmap, 48, 48, COLOR_WHITE, COLOR_BLACK);
    }
    else if (g_tbtIconIndex > 0 && g_tbtIconIndex <= 20)
    {
        // Draw from PROGMEM icon array
        int iconX = (240 - ICON_WIDTH) / 2;
        int iconY = HUD_ICON_Y;
        m_tft->drawBitmap(iconX, iconY, turnIcons[g_tbtIconIndex], ICON_WIDTH, ICON_HEIGHT, COLOR_WHITE, COLOR_BLACK);
    }

    // Draw speed (large text, center)
    char speedBuf[32];
    snprintf(speedBuf, sizeof(speedBuf), "%s km/h", g_gpsSpeedStr);
    int speedLen = myfont.getLength(speedBuf);
    myfont.print((240 - speedLen) / 2, HUD_SPEED_Y, speedBuf, COLOR_WHITE, COLOR_BLACK);

    // Draw distance to next turn
    if (strlen(g_navData.dist) > 0)
    {
        int distLen = myfont.getLength(g_navData.dist);
        myfont.print((240 - distLen) / 2, HUD_DIST_Y, g_navData.dist, COLOR_YELLOW, COLOR_BLACK);
    }

    // Draw street/road name
    if (strlen(g_navData.road) > 0)
    {
        int roadLen = myfont.getLength(g_navData.road);
        myfont.print((240 - roadLen) / 2, HUD_ROAD_Y, g_navData.road, COLOR_CYAN, COLOR_BLACK);
    }

    // Draw ETA
    if (strlen(g_navData.eta) > 0)
    {
        char etaBuf[64];
        snprintf(etaBuf, sizeof(etaBuf), "ETA: %s", g_navData.eta);
        int etaLen = myfont.getLength(etaBuf);
        myfont.print((240 - etaLen) / 2, HUD_ETA_Y, etaBuf, COLOR_GREEN, COLOR_BLACK);
    }
}

// ============================================================
// MAP Mode (handled by jpegHandler, just clear screen)
// ============================================================
void UIRenderer::renderMap()
{
    // Map mode is handled by JPEG decoder, nothing to draw here from UI
    // The screen will be filled by the JPEG decode callback
}

// ============================================================
// STATUS Mode rendering
// ============================================================
void UIRenderer::renderStatus()
{
    m_tft->fillScreen(COLOR_BLACK);

    // --- Clock (HH:MM) ---
    char timeStr[16];
    int hour = rtc.getHour(true); // 24h format
    int minute = rtc.getMinute();
    sprintf(timeStr, "%02d:%02d", hour, minute);
    int timeLen = myfont.getLength(timeStr);
    myfont.print((240 - timeLen) / 2, ST_TIME_Y, timeStr, COLOR_WHITE, COLOR_BLACK);

    // --- Weather icon ---
    int wthrIdx = getWeatherIconIndex(g_weatherData.iconCode);
    const uint8_t *wthrData = nullptr;
    switch (wthrIdx)
    {
    case 0:
        wthrData = weatherIconSunny;
        break;
    case 1:
        wthrData = weatherIconCloudy;
        break;
    case 2:
        wthrData = weatherIconRainy;
        break;
    case 3:
        wthrData = weatherIconSnowy;
        break;
    default:
        break;
    }
    if (wthrData)
    {
        int wthrX = (240 - WTHR_ICON_WIDTH) / 2;
        m_tft->drawBitmap(wthrX, ST_WTHR_Y, wthrData, WTHR_ICON_WIDTH, WTHR_ICON_HEIGHT, COLOR_WHITE, COLOR_BLACK);
    }

    // --- Temperature ---
    char tempStr[16];
    sprintf(tempStr, "%.1f°C", g_weatherData.temperature);
    int tempLen = myfont.getLength(tempStr);
    myfont.print((240 - tempLen) / 2, ST_TEMP_Y, tempStr, COLOR_YELLOW, COLOR_BLACK);

    // --- Battery voltage ---
    int adcRaw = analogReadMilliVolts(4); // GPIO4 = BAT_ADC (ADC1_CH3)
    float vAdc = adcRaw / 1000.0f;
    float vBat = vAdc * 6.0f; // Voltage divider ratio 1:6
    char batStr[32];
    snprintf(batStr, sizeof(batStr), "Bat: %.1fV", vBat);
    int batLen = myfont.getLength(batStr);
    myfont.print((240 - batLen) / 2, ST_BAT_Y, batStr, COLOR_GREEN, COLOR_BLACK);
}

// ============================================================
// BLE status indicator
// ============================================================
void UIRenderer::drawBLEIndicator(bool connected, int8_t rssi)
{
    int x = 220, y = 4;
    if (connected)
    {
        // Green dot + RSSI bars
        m_tft->fillCircle(x, y, 4, COLOR_GREEN);
        int bars = 1;
        if (rssi > -60)
            bars = 3;
        else if (rssi > -75)
            bars = 2;
        for (int i = 0; i < bars; i++)
        {
            m_tft->fillRect(x + 6 + i * 5, y - 2 - i * 3, 3, 4 + i * 3, COLOR_GREEN);
        }
    }
    else
    {
        // Red dot
        m_tft->fillCircle(x, y, 4, COLOR_RED);
    }
}

// ============================================================
// Status bar (bottom of screen)
// ============================================================
void UIRenderer::drawStatusBar(uint8_t mode, float voltage)
{
    // Bottom line: show current mode name and BLE status
    const char *modeNames[] = {"HUD", "MAP", "STT"};
    char bar[32];
    snprintf(bar, sizeof(bar), "%s  %.1fV", modeNames[mode % 3], voltage);

    int barLen = myfont.getLength(bar);
    myfont.print((240 - barLen) / 2, 228, bar, COLOR_GRAY, COLOR_BLACK);
}

// ============================================================
// Overlay menu
// ============================================================
void UIRenderer::drawMenuOverlay(bool active, int selection)
{
    if (!active)
        return;

    // Create a sprite for the overlay using standard heap allocation
    TFT_eSprite spr(m_tft);
    spr.createSprite(240, 240);

    // Semi-transparent background: dark gray overlay
    spr.fillSprite(0x2108);

    // Menu items
    const char *labels[] = {"HUD", "MAP", "STATUS"};
    int menuY[] = {70, 110, 150};

    for (int i = 0; i < 3; i++)
    {
        if (i == selection)
        {
            // Highlighted item
            spr.fillRoundRect(40, menuY[i] - 10, 160, 36, 8, COLOR_BLUE);
            spr.setTextColor(COLOR_WHITE, COLOR_BLUE);
        }
        else
        {
            spr.setTextColor(COLOR_GRAY, 0x2108);
        }
        spr.setTextSize(2);
        spr.setTextDatum(TC_DATUM);
        spr.drawString(labels[i], 120, menuY[i], 2);
    }

    // Instructions
    spr.setTextSize(1);
    spr.setTextColor(COLOR_WHITE, 0x2108);
    spr.setTextDatum(TC_DATUM);
    spr.drawString("Short: cycle   Long: select", 120, 200, 1);

    // Push sprite to screen
    spr.pushSprite(0, 0);
    spr.deleteSprite();
}
