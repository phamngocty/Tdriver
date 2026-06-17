#ifndef UI_RENDERER_H
#define UI_RENDERER_H

#include <Arduino.h>
#include <TFT_eSPI.h>
#include "mode_manager.h"

// -------------------------------------------------------------------
// Vẽ giao diện cho 3 chế độ HUD / MAP / STATUS
// -------------------------------------------------------------------

class UiRenderer
{
public:
    UiRenderer(TFT_eSPI *tft);

    void drawHUD(const char *navString, uint8_t iconIndex, const char *speed);
    void drawStatus(const char *weatherJson, uint32_t unixTime, float batteryVolt);
    // MAP mode: ảnh được vẽ trực tiếp từ JPEG callback, không cần hàm riêng

private:
    TFT_eSPI *_tft;

    // Parse navString (key=value \n separated)
    String _getNavValue(const char *navString, const char *key);

    // Vẽ icon thời tiết từ mã OpenWeather
    void _drawWeatherIcon(int16_t cx, int16_t cy, const char *iconCode, uint16_t color);
};

#endif // UI_RENDERER_H
