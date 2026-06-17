#include "ui_renderer.h"
#include "icons.h"
#include "MyFont.h"

// -------------------------------------------------------------------
// Định nghĩa màu sắc
// -------------------------------------------------------------------
#define COLOR_HUD_BG TFT_BLACK
#define COLOR_HUD_TEXT TFT_WHITE
#define COLOR_HUD_ACCENT TFT_CYAN
#define COLOR_SPEED TFT_GREENYELLOW
#define COLOR_ETA TFT_ORANGE
#define COLOR_STATUS_BG TFT_BLACK
#define COLOR_TIME TFT_WHITE
#define COLOR_TEMP TFT_YELLOW

// Ngưỡng điện áp ắc quy xe máy (12V system)
#define BATT_FULL 14.5f  // Khi nổ máy, máy phát sạc ~14.5V
#define BATT_EMPTY 11.0f // Ắc quy yếu, cần đề phòng

UiRenderer::UiRenderer(TFT_eSPI *tft) : _tft(tft) {}

// ===================================================================
// Chế độ HUD
// ===================================================================
void UiRenderer::drawHUD(const char *navString, uint8_t iconIndex, const char *speed)
{
    _tft->fillScreen(COLOR_HUD_BG);

    // --- Tốc độ (lớn, trung tâm dưới) ---
    _tft->setTextColor(COLOR_SPEED, COLOR_HUD_BG);
    _tft->setTextFont(FONT_SPEED); // font 7 là font large
    _tft->setTextSize(1);
    _tft->setTextDatum(BC_DATUM);
    _tft->drawString(speed, 120, 200);
    _tft->setTextFont(FONT_SMALL);
    _tft->setTextDatum(BC_DATUM);
    _tft->drawString("km/h", 120, 218);

    // --- Icon rẽ (trên cùng) ---
    drawTurnIcon(*_tft, 120, 55, iconIndex, COLOR_HUD_ACCENT);

    // --- Thông tin dẫn đường (parse navString) ---
    String nextRd = _getNavValue(navString, "nextRd");
    String distToNext = _getNavValue(navString, "distToNext");
    String eta = _getNavValue(navString, "eta");
    String ete = _getNavValue(navString, "ete");

    // Tên đường (giữa)
    if (nextRd.length() > 0)
    {
        _tft->setTextColor(COLOR_HUD_TEXT, COLOR_HUD_BG);
        _tft->setTextFont(FONT_MEDIUM);
        _tft->setTextSize(1);
        _tft->setTextDatum(TC_DATUM);
        // Rút gọn nếu quá dài
        if (_tft->textWidth(nextRd) > 220)
        {
            nextRd = nextRd.substring(0, 14) + "...";
        }
        _tft->drawString(nextRd, 120, 90);
    }

    // Khoảng cách đến lần rẽ tiếp theo
    if (distToNext.length() > 0)
    {
        _tft->setTextColor(COLOR_HUD_ACCENT, COLOR_HUD_BG);
        _tft->setTextFont(FONT_LARGE);
        _tft->setTextSize(1);
        _tft->setTextDatum(TC_DATUM);
        _tft->drawString(distToNext, 120, 115);
    }

    // ETA / ETE (góc dưới)
    String etaLine;
    if (eta.length() > 0)
        etaLine = "ETA: " + eta;
    if (ete.length() > 0)
    {
        if (etaLine.length() > 0)
            etaLine += "  ";
        etaLine += "(" + ete + ")";
    }
    if (etaLine.length() > 0)
    {
        _tft->setTextColor(COLOR_ETA, COLOR_HUD_BG);
        _tft->setTextFont(FONT_SMALL);
        _tft->setTextSize(1);
        _tft->setTextDatum(TC_DATUM);
        _tft->drawString(etaLine, 120, 145);
    }
}

// ===================================================================
// Chế độ STATUS
// ===================================================================
void UiRenderer::drawStatus(const char *weatherJson, uint32_t unixTime, float batteryVolt)
{
    _tft->fillScreen(COLOR_STATUS_BG);

    // --- Đồng hồ (lớn, trung tâm) ---
    if (unixTime > 100000)
    {
        time_t t = (time_t)unixTime;
        struct tm *ti = localtime(&t);
        char timeStr[6];
        snprintf(timeStr, sizeof(timeStr), "%02d:%02d", ti->tm_hour, ti->tm_min);

        _tft->setTextColor(COLOR_TIME, COLOR_STATUS_BG);
        _tft->setTextFont(FONT_SPEED);
        _tft->setTextSize(1);
        _tft->setTextDatum(MC_DATUM);
        _tft->drawString(timeStr, 120, 80);

        char dateStr[16];
        snprintf(dateStr, sizeof(dateStr), "%02d/%02d/%04d",
                 ti->tm_mday, ti->tm_mon + 1, ti->tm_year + 1900);
        _tft->setTextFont(FONT_SMALL);
        _tft->setTextDatum(TC_DATUM);
        _tft->drawString(dateStr, 120, 120);
    }

    // --- Thời tiết ---
    if (weatherJson && strlen(weatherJson) > 0)
    {
        // Parse JSON đơn giản (không dùng thư viện để tránh phụ thuộc)
        // {"t":30,"i":"01d"}
        String json(weatherJson);
        int tPos = json.indexOf("\"t\":");
        int iPos = json.indexOf("\"i\":");
        int temp = 0;
        String iconCode;

        if (tPos > 0)
        {
            int start = json.indexOf(':', tPos) + 1;
            int end = json.indexOf(',', start);
            if (end < 0)
                end = json.indexOf('}', start);
            if (end > start)
            {
                temp = json.substring(start, end).toInt();
            }
        }
        if (iPos > 0)
        {
            int start = json.indexOf('"', iPos + 3) + 1;
            int end = json.indexOf('"', start);
            if (end > start)
            {
                iconCode = json.substring(start, end);
            }
        }

        // Vẽ icon thời tiết
        if (iconCode.length() > 0)
        {
            _drawWeatherIcon(80, 185, iconCode.c_str(), COLOR_TEMP);
        }

        // Vẽ nhiệt độ
        _tft->setTextColor(COLOR_TEMP, COLOR_STATUS_BG);
        _tft->setTextFont(FONT_LARGE);
        _tft->setTextSize(1);
        _tft->setTextDatum(ML_DATUM);
        char tempStr[8];
        snprintf(tempStr, sizeof(tempStr), "%d°C", temp);
        _tft->drawString(tempStr, 105, 175);
    }

    // --- Điện áp ắc quy (góc dưới phải) ---
    if (batteryVolt > 0)
    {
        _tft->setTextFont(FONT_SMALL);
        _tft->setTextColor(TFT_GREEN, COLOR_STATUS_BG);
        _tft->setTextDatum(BR_DATUM);
        char voltStr[12];
        snprintf(voltStr, sizeof(voltStr), "%.2fV", batteryVolt);
        _tft->drawString(voltStr, 230, 235);

        // Thanh pin
        uint16_t battColor = (batteryVolt > BATT_FULL) ? TFT_GREEN : (batteryVolt > 3.7f) ? TFT_YELLOW
                                                                                          : TFT_RED;
        int barWidth = map((int)(batteryVolt * 100), (int)(BATT_EMPTY * 100),
                           (int)(BATT_FULL * 100), 0, 50);
        barWidth = constrain(barWidth, 0, 50);
        _tft->fillRect(175, 225, barWidth, 8, battColor);
        _tft->drawRect(174, 224, 52, 10, TFT_WHITE);
    }
}

// ===================================================================
// Helpers
// ===================================================================

String UiRenderer::_getNavValue(const char *navString, const char *key)
{
    if (!navString || strlen(navString) == 0)
        return "";

    String s(navString);
    String searchKey = String(key) + "=";
    int start = s.indexOf(searchKey);
    if (start < 0)
        return "";

    start += searchKey.length();
    int end = s.indexOf('\n', start);
    if (end < 0)
        end = s.length();

    return s.substring(start, end);
}

void UiRenderer::_drawWeatherIcon(int16_t cx, int16_t cy, const char *iconCode, uint16_t color)
{
    // Vẽ icon thời tiết đơn giản dựa trên mã OpenWeather
    if (!iconCode)
        return;

    char code = iconCode[0];

    // Mặt trời (dùng cho 01d, 02d)
    if (code == '0')
    {
        _tft->fillCircle(cx, cy, 12, TFT_YELLOW);
        // Tia nắng
        for (int a = 0; a < 360; a += 45)
        {
            float rad = a * DEG_TO_RAD;
            int16_t x1 = cx + cos(rad) * 14;
            int16_t y1 = cy + sin(rad) * 14;
            int16_t x2 = cx + cos(rad) * 20;
            int16_t y2 = cy + sin(rad) * 20;
            _tft->drawLine(x1, y1, x2, y2, TFT_YELLOW);
        }
    }
    // Mây (02d, 03d, 04d)
    else if (code == '2' || code == '3' || code == '4')
    {
        _tft->fillCircle(cx - 8, cy, 10, TFT_LIGHTGREY);
        _tft->fillCircle(cx + 6, cy - 4, 12, TFT_LIGHTGREY);
        _tft->fillCircle(cx + 10, cy + 2, 8, TFT_LIGHTGREY);
    }
    // Mưa (09d, 10d)
    else if (code == '9' || code == '1')
    {
        if (code == '1' && iconCode[1] == '0')
        {
            // 10d = mưa
            _tft->fillCircle(cx, cy, 10, TFT_LIGHTGREY);
            for (int i = -6; i <= 6; i += 6)
            {
                _tft->drawLine(cx + i, cy + 8, cx + i - 2, cy + 16, color);
                _tft->drawLine(cx + i, cy + 8, cx + i + 2, cy + 16, color);
            }
        }
        else
        {
            // 09d = mưa nhẹ
            for (int i = -8; i <= 8; i += 8)
            {
                _tft->drawLine(cx + i, cy - 6, cx + i - 2, cy + 6, color);
            }
        }
    }
    // Tuyết (13d)
    else if (code == '1' && iconCode[1] == '3')
    {
        for (int i = -1; i <= 1; i++)
        {
            _tft->fillCircle(cx + i * 10, cy, 4, TFT_WHITE);
        }
    }
    // Sương mù (50d)
    else if (code == '5')
    {
        for (int i = 0; i < 3; i++)
        {
            _tft->drawLine(cx - 15, cy - 6 + i * 6, cx + 15, cy - 6 + i * 6, TFT_LIGHTGREY);
        }
    }
    // Mặc định
    else
    {
        _tft->drawCircle(cx, cy, 12, color);
    }
}
