#include "gui.h"
#include "logo.h"

// Biến trạng thái GUI
bool isMenuOpen = false;
unsigned long menuStartTime = 0;
int menuSelectedIndex = 0;
int brightness = 80;

uint8_t statusStyle = 0; // 0=S1 Sport Chrono Radar (Mẫu 2 Mặc định), 1=S2 Dual Gauges, 2=S3 Minimalist, 3=S4 Sport Telemetry
uint8_t hudStyle = 0;    // 0=H1 Classic Boxed, 1=H2 Split Dash, 2=H3 Big Arrow, 3=H4 Racing Bar, 4=H5 Banner, 5=H6 Dual Pill
uint8_t notifStyle = 0;  // 0=N1 Rounded Card, 1=N2 Split App Focus, 2=N3 Top Banner
uint8_t mapStyle = 0;    // 0=M1 Fullscreen Map + Mini HUD, 1=Pure Map 100%, 2=M2 Split Map + Turn HUD

// Bộ đệm sóng Oscilloscope thời gian thực
float voltHistory[80] = {0};
uint8_t voltHistoryIdx = 0;
float voltMin = 99.0f;
float voltMax = 0.0f;
uint16_t autoSampleIntervalMs = 30;
static float prevSampleVolt = 12.5f;
static float avgSlewRate = 0.0f;

void pushVoltSample(float v)
{
    static bool bufferFilled = false;
    if (!bufferFilled)
    {
        for (int k = 0; k < 80; k++)
        {
            voltHistory[k] = v;
        }
        bufferFilled = true;
        prevSampleVolt = v;
    }

    // Tính độ biến thiên tức thời (Slew rate) để điều chỉnh Auto-Timebase:
    float delta = fabsf(v - prevSampleVolt);
    prevSampleVolt = v;
    avgSlewRate = avgSlewRate + 0.12f * (delta - avgSlewRate);

    // THUẬT TOÁN AUTO-TIMEBASE:
    // - Biến thiên nhanh / xung nhọn / AC ripple (avgSlew > 0.35V) -> Lấy mẫu nhanh 15ms (giãn rộng chi tiết sóng)
    // - Biến thiên vừa (avgSlew 0.12V -> 0.35V) -> Lấy mẫu 25ms - 30ms
    // - Điện áp tĩnh / trôi chậm (avgSlew < 0.08V) -> Lấy mẫu 50ms (mở rộng cửa sổ theo dõi xu hướng 4 giây)
    if (avgSlewRate > 0.35f) autoSampleIntervalMs = 15;
    else if (avgSlewRate > 0.12f) autoSampleIntervalMs = 25;
    else if (avgSlewRate > 0.05f) autoSampleIntervalMs = 35;
    else autoSampleIntervalMs = 50;

    voltHistory[voltHistoryIdx] = v;
    voltHistoryIdx = (voltHistoryIdx + 1) % 80;
    if (v < voltMin || voltMin > 50.0f)
        voltMin = v;
    if (v > voltMax)
        voltMax = v;
}

// Vẽ icon rẽ 1bpp monochrome 48x48
void drawCustomIcon(const uint8_t *bitmap, int xOffset, int yOffset, int scale)
{
    if (!bitmap)
        return;
    for (int y = 0; y < 48; y++)
    {
        for (int x = 0; x < 48; x++)
        {
            int byteIdx = (y * 48 + x) / 8;
            int bitPos = 7 - (x % 8);
            bool isPixel = (bitmap[byteIdx] & (1 << bitPos)) != 0;
            if (isPixel)
            {
                if (scale == 1)
                {
                    u8g2.setDrawColor(1);
                    u8g2.drawPixel(xOffset + x, yOffset + y);
                }
                else
                {
                    u8g2.setDrawColor(1);
                    u8g2.drawBox(xOffset + x * scale, yOffset + y * scale, scale, scale);
                }
            }
        }
    }
}

// Helper: Lấy số byte của 1 ký tự UTF-8 (an toàn tiếng Việt)
static inline int getUtf8CharLen(uint8_t c)
{
    if ((c & 0x80) == 0)
        return 1;
    if ((c & 0xE0) == 0xC0)
        return 2;
    if ((c & 0xF0) == 0xE0)
        return 3;
    if ((c & 0xF8) == 0xF0)
        return 4;
    return 1;
}

// Helper: Tách chuỗi thành các dòng theo chiều rộng maxW (ngắt theo từ)
static int splitTextToLines(const String &text, int maxW, String *outLines, int maxCapacity)
{
    if (text.length() == 0 || maxCapacity <= 0)
        return 0;

    int lineCount = 0;
    int idx = 0;
    int len = text.length();

    while (idx < len && lineCount < maxCapacity)
    {
        while (idx < len && (text[idx] == ' ' || text[idx] == '\r' || text[idx] == '\n'))
        {
            idx++;
        }
        if (idx >= len)
            break;

        String line = "";
        int startIdx = idx;

        while (idx < len)
        {
            if (text[idx] == '\n')
            {
                idx++;
                break;
            }

            int spacePos = text.indexOf(' ', idx);
            int nlPos = text.indexOf('\n', idx);
            if (spacePos == -1 || (nlPos != -1 && nlPos < spacePos))
                spacePos = (nlPos != -1) ? nlPos : len;

            String word = text.substring(idx, spacePos);
            String testLine = (line.length() == 0) ? word : (line + " " + word);

            if (myFont.getLength(testLine.c_str()) <= maxW)
            {
                line = testLine;
                idx = (spacePos < len) ? spacePos + 1 : len;
            }
            else
            {
                if (line.length() == 0)
                {
                    // Từ quá dài không vừa 1 dòng -> cắt theo ký tự UTF-8 an toàn
                    int charIdx = 0;
                    int wordByteLen = word.length();
                    while (charIdx < wordByteLen)
                    {
                        int cLen = getUtf8CharLen((uint8_t)word[charIdx]);
                        if (cLen <= 0)
                            cLen = 1;
                        String testChar = word.substring(0, charIdx + cLen);
                        if (myFont.getLength(testChar.c_str()) <= maxW)
                        {
                            charIdx += cLen;
                        }
                        else
                        {
                            break;
                        }
                    }
                    if (charIdx == 0)
                    {
                        charIdx = getUtf8CharLen((uint8_t)word[0]);
                        if (charIdx <= 0)
                            charIdx = 1;
                    }
                    line = word.substring(0, charIdx);
                    idx += charIdx;
                }
                break;
            }
        }

        if (line.length() > 0)
        {
            outLines[lineCount++] = line;
        }

        // Đảm bảo idx luôn tăng để tránh vòng lặp vô hạn
        if (idx == startIdx)
        {
            idx++;
        }
    }

    return lineCount;
}

// Hàm ngắt dòng và hiển thị tiếng Việt bằng FontMaker (hỗ trợ cuộn mượt từng pixel và xuống hàng thông minh)
void drawWrappedTextMyFont(int startX, int startY, int maxW, int lineHeight, int maxLines, const String &text)
{
    if (text.length() == 0 || maxLines <= 0)
    {
        u8g2.setDrawColor(1);
        return;
    }

    // Đảm bảo nạp font Tiếng Việt
    myFont.set_font(FONT_VIETNAMESE_BODY);

    // 1. Chế độ 1 dòng (maxLines == 1): Tự động cuộn Marquee mượt mà từng pixel nếu tràn chiều ngang
    if (maxLines == 1)
    {
        int textW = myFont.getLength(text.c_str());
        if (textW <= maxW)
        {
            myFont.print(startX, startY, (char *)text.c_str(), 1, 0);
        }
        else
        {
            // Thêm 6px đệm cuối để ký tự và dấu tiếng Việt cuối cùng hiển thị trọn vẹn 100%, không bị cụt đuôi
            int overflow = (textW - maxW) + 6;
            int scrollDuration = overflow * 28; // 28ms mỗi pixel cho tốc độ lướt vừa mắt
            int totalPeriod = 1200 + scrollDuration + 1000;
            int progressMs = millis() % totalPeriod;
            int scrollX = 0;

            if (progressMs > 1200 && progressMs <= (1200 + scrollDuration))
            {
                scrollX = (progressMs - 1200) / 28;
            }
            else if (progressMs > (1200 + scrollDuration))
            {
                scrollX = overflow;
            }

            clipMinX = startX;
            clipMaxX = min((int16_t)128, (int16_t)(startX + maxW));
            clipMinY = max((int16_t)0, (int16_t)(startY - 2));
            clipMaxY = min((int16_t)64, (int16_t)(startY + lineHeight + 2));

            myFont.print(startX - scrollX, startY, (char *)text.c_str(), 1, 0);

            // Phục hồi khung vẽ toàn màn hình
            clipMinX = 0;
            clipMaxX = 128;
            clipMinY = 0;
            clipMaxY = 64;
        }
        u8g2.setDrawColor(1);
        return;
    }

    // 2. Chế độ nhiều dòng (maxLines > 1): Xuống hàng tối ưu
    const int MAX_CACHE_LINES = 10;
    String lines[MAX_CACHE_LINES];
    int totalLines = splitTextToLines(text, maxW, lines, MAX_CACHE_LINES);

    if (totalLines <= maxLines)
    {
        // Vừa vặn trong khung -> Hiển thị cố định rõ nét
        for (int i = 0; i < totalLines; i++)
        {
            myFont.print(startX, startY + i * lineHeight, (char *)lines[i].c_str(), 1, 0);
        }
    }
    else
    {
        // Vượt quá maxLines -> Cuộn trượt dọc mượt mà từng pixel (Smooth Vertical Pixel Glide)
        int totalH = totalLines * lineHeight;
        int visibleH = maxLines * lineHeight;
        // Thêm 2px đệm để dòng cuối cùng hiển thị trọn vẹn dấu nặng và chân chữ
        int overflowY = (totalH - visibleH) + 2;
        int scrollDuration = overflowY * 40; // 40ms mỗi pixel cuộn dọc
        int totalPeriod = 1500 + scrollDuration + 1200;
        int progressMs = millis() % totalPeriod;
        int scrollY = 0;

        if (progressMs > 1500 && progressMs <= (1500 + scrollDuration))
        {
            scrollY = (progressMs - 1500) / 40;
        }
        else if (progressMs > (1500 + scrollDuration))
        {
            scrollY = overflowY;
        }

        clipMinX = startX;
        clipMaxX = min((int16_t)128, (int16_t)(startX + maxW));
        clipMinY = max((int16_t)0, (int16_t)(startY - 1));
        clipMaxY = min((int16_t)64, (int16_t)(startY + visibleH + 2)); // Mở rộng vùng clip dọc để hiển thị trọn vẹn chân chữ dòng cuối

        for (int i = 0; i < totalLines; i++)
        {
            int lineY = startY + i * lineHeight - scrollY;
            if (lineY + lineHeight + 2 >= startY && lineY <= startY + visibleH + 2)
            {
                myFont.print(startX, lineY, (char *)lines[i].c_str(), 1, 0);
            }
        }

        // Phục hồi khung vẽ toàn màn hình
        clipMinX = 0;
        clipMaxX = 128;
        clipMinY = 0;
        clipMaxY = 64;
    }

    u8g2.setDrawColor(1);
}

// Vẽ icon thời tiết vector sắc nét (Nắng, Mây, Mưa)
void drawWeatherIconVector(int xOffset, int yOffset, const String &icon)
{
    if (icon.indexOf("rain") != -1 || icon.indexOf("mưa") != -1)
    {
        // Đám mây
        u8g2.drawDisc(xOffset + 14, yOffset + 14, 8);
        u8g2.drawDisc(xOffset + 24, yOffset + 12, 10);
        u8g2.drawDisc(xOffset + 32, yOffset + 16, 7);
        u8g2.drawBox(xOffset + 12, yOffset + 16, 22, 6);

        // Hạt mưa rơi
        u8g2.drawLine(xOffset + 14, yOffset + 26, xOffset + 10, yOffset + 34);
        u8g2.drawLine(xOffset + 22, yOffset + 26, xOffset + 18, yOffset + 34);
        u8g2.drawLine(xOffset + 30, yOffset + 26, xOffset + 26, yOffset + 34);
    }
    else if (icon.indexOf("cloud") != -1 || icon.indexOf("mây") != -1 || icon.indexOf("overcast") != -1)
    {
        // Đám mây lớn
        u8g2.drawDisc(xOffset + 12, yOffset + 18, 9);
        u8g2.drawDisc(xOffset + 23, yOffset + 14, 12);
        u8g2.drawDisc(xOffset + 34, yOffset + 19, 8);
        u8g2.drawBox(xOffset + 10, yOffset + 20, 26, 7);
    }
    else
    {
        // Mặc định: Nắng / Mặt trời (Sun)
        u8g2.drawCircle(xOffset + 20, yOffset + 20, 8);
        u8g2.drawDisc(xOffset + 20, yOffset + 20, 5);
        // Tia nắng 8 hướng
        u8g2.drawVLine(xOffset + 20, yOffset + 6, 4);  // Top
        u8g2.drawVLine(xOffset + 20, yOffset + 30, 4); // Bottom
        u8g2.drawHLine(xOffset + 6, yOffset + 20, 4);  // Left
        u8g2.drawHLine(xOffset + 30, yOffset + 20, 4); // Right
        u8g2.drawLine(xOffset + 10, yOffset + 10, xOffset + 13, yOffset + 13);
        u8g2.drawLine(xOffset + 27, yOffset + 13, xOffset + 30, yOffset + 10);
        u8g2.drawLine(xOffset + 10, yOffset + 30, xOffset + 13, yOffset + 27);
        u8g2.drawLine(xOffset + 27, yOffset + 27, xOffset + 30, yOffset + 30);
    }
}

// -------------------------------------------------------------
// 1. MÀN HÌNH STATUS (4 CHỦ ĐỀ CHUYÊN BIỆT: THỜI GIAN, TỐC ĐỘ, ĐIỆN ÁP, THỜI TIẾT)
// -------------------------------------------------------------
void drawSTATUS()
{
    u8g2.clearBuffer();
    u8g2.setFontPosTop();
    u8g2.setDrawColor(1);

    String timeStr = rtc.getTime("%H:%M");
    String dateStr = rtc.getTime("%d/%m");

    if (statusStyle == 0)
    {
        // ================= S1: CHỦ ĐỀ THỜI GIAN (BIG CLOCK FOCUS - MÀN HÌNH GỐC) =================
        // Header
        if (bleConnected)
        {
            u8g2.setDrawColor(1);
            u8g2.drawDisc(5, 6, 2);
        }
        else
        {
            u8g2.drawCircle(5, 6, 2);
        }

        u8g2.setFont(FONT_U8G2_SMALL);
        u8g2.drawStr(12, 2, dateStr.c_str());

        if (weatherTemp > -50.0f)
        {
            char wBuf[16];
            snprintf(wBuf, sizeof(wBuf), "%.0fC", weatherTemp);
            int wLen = u8g2.getStrWidth(wBuf);
            u8g2.drawStr(128 - wLen - 2, 2, wBuf);
        }

        u8g2.drawHLine(0, 13, 128);

        // Đồng hồ trung tâm lớn 28pt
        u8g2.setFont(FONT_U8G2_BIG_CLOCK);
        int timeLen = u8g2.getStrWidth(timeStr.c_str());
        int timeX = (128 - timeLen) / 2;
        if (timeX < 0)
            timeX = 0;
        u8g2.drawStr(timeX, 17, timeStr.c_str());

        // Footer: Điện áp & Pin
        u8g2.drawHLine(0, 49, 128);
        u8g2.setFont(FONT_U8G2_SMALL);

        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "XE:%.1fV", batteryVoltage);
        u8g2.drawStr(2, 52, vBuf);

        char pBuf[16];
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        snprintf(pBuf, sizeof(pBuf), "DT:%d%%%s", pBat, phoneBatteryCharging ? "+" : "");
        int pLen = u8g2.getStrWidth(pBuf);
        u8g2.drawStr(128 - pLen - 2, 52, pBuf);
    }
    else if (statusStyle == 4)
    {
        // ================= MẪU 2: SPORT CHRONO & REALTIME VOLTAGE RADAR (OLED 128x64) =================
        // 1. HEADER BAR (y = 0..12, Dải trên cùng)
        u8g2.setFont(FONT_U8G2_SMALL);
        u8g2.drawStr(1, 1, timeStr.c_str());

        u8g2.setFont(FONT_U8G2_TINY);
        u8g2.drawStr(40, 2, dateStr.c_str());

        if (bleConnected)
            u8g2.drawDisc(78, 6, 2);
        else
            u8g2.drawCircle(78, 6, 2);

        u8g2.setFont(FONT_U8G2_SMALL);
        char wBuf[16];
        if (weatherTemp > -50.0f && weatherTemp < 60.0f)
            snprintf(wBuf, sizeof(wBuf), "%.0fC", weatherTemp);
        else
            snprintf(wBuf, sizeof(wBuf), "32C");
        int wLen = u8g2.getStrWidth(wBuf);
        u8g2.drawStr(127 - wLen, 1, wBuf);

        // Kẻ đường ngang phân cách Header
        u8g2.drawHLine(0, 13, 128);

        // 2. KHU VỰC THÔNG SỐ XE (Cột trái: x = 0..44, y = 15..63)
        u8g2.setFont(FONT_U8G2_LABEL_BOLD);
        char vBuf[16];
        if (batteryVoltage < 10.0f)
            snprintf(vBuf, sizeof(vBuf), "%.2fV", batteryVoltage);
        else
            snprintf(vBuf, sizeof(vBuf), "%.1fV", batteryVoltage);
        u8g2.drawStr(1, 16, vBuf);

        // Cực trị Min / Max thoáng đãng
        u8g2.setFont(FONT_U8G2_SMALL);
        char minBuf[16], maxBuf[16];
        snprintf(minBuf, sizeof(minBuf), "L:%.1fV", (voltMin > 50.0f) ? batteryVoltage : voltMin);
        snprintf(maxBuf, sizeof(maxBuf), "H:%.1fV", (voltMax < 5.0f) ? batteryVoltage : voltMax);
        u8g2.drawStr(1, 33, minBuf);
        u8g2.drawStr(1, 46, maxBuf);

        // Nhãn chu kỳ quét Auto-Time tự động
        u8g2.setFont(FONT_U8G2_TINY);
        char tBuf[16];
        snprintf(tBuf, sizeof(tBuf), "%dms/D", autoSampleIntervalMs);
        u8g2.drawStr(1, 57, tBuf);

        // 3. KHU VỰC KHUNG MÁY HIỆN SÓNG OSCILLOSCOPE (Cột phải: x = 46..127, y = 15..63, 82x48)
        int ox = 46, oy = 15, ow = 82, oh = 48;
        u8g2.drawFrame(ox, oy, ow, oh);

        // THUẬT TOÁN AUTO-ZOOM (DYNAMIC DSO AUTO-SCALING):
        float localMin = 99.0f, localMax = -99.0f;
        for (int i = 0; i < 80; i++)
        {
            float val = voltHistory[i];
            if (val < localMin) localMin = val;
            if (val > localMax) localMax = val;
        }
        if (localMin > 50.0f) localMin = batteryVoltage - 0.5f;
        if (localMax < -50.0f) localMax = batteryVoltage + 0.5f;

        float span = localMax - localMin;
        if (span < 1.2f)
        {
            float mid = (localMax + localMin) * 0.5f;
            localMin = mid - 0.6f;
            localMax = mid + 0.6f;
        }
        else
        {
            float margin = span * 0.18f;
            localMin -= margin;
            localMax += margin;
        }

        // Bắt tức thì khi sụt áp đề máy hoặc sạc nổ máy, mượt khi tĩnh
        static float smoothMin = 11.0f, smoothMax = 13.5f;
        static bool isScaleInitC3 = false;
        if (!isScaleInitC3)
        {
            smoothMin = localMin;
            smoothMax = localMax;
            isScaleInitC3 = true;
        }
        if (localMin < smoothMin)
            smoothMin = localMin;
        else
            smoothMin += 0.15f * (localMin - smoothMin);

        if (localMax > smoothMax)
            smoothMax = localMax;
        else
            smoothMax += 0.15f * (localMax - smoothMax);

        if (smoothMax - smoothMin < 0.6f)
            smoothMax = smoothMin + 0.6f;

        // Tâm định vị toạ độ (Subtle Center Crosshair)
        int cx = ox + ow / 2;
        int cy = oy + oh / 2;
        u8g2.drawPixel(cx, cy);
        u8g2.drawPixel(cx - 1, cy);
        u8g2.drawPixel(cx + 1, cy);
        u8g2.drawPixel(cx, cy - 1);
        u8g2.drawPixel(cx, cy + 1);

        // Vẽ đường sóng liên tục không bị đứt đoạn, không thay thế giá trị (80 điểm từ ox+1 đến ox+ow-2)
        int plotW = ow - 2; // 80 điểm
        int prevPx = -1, prevPy = -1;
        for (int i = 0; i < plotW; i++)
        {
            int bufIdx = (voltHistoryIdx + i) % 80;
            float val = voltHistory[bufIdx];

            int py = oy + oh - 2 - (int)(((val - smoothMin) / (smoothMax - smoothMin)) * (oh - 4));
            if (py < oy + 1) py = oy + 1;
            if (py > oy + oh - 2) py = oy + oh - 2;
            int px = ox + 1 + i;

            if (i == 0)
            {
                prevPx = px;
                prevPy = py;
            }
            else
            {
                u8g2.drawLine(prevPx, prevPy, px, py);
                prevPx = px;
                prevPy = py;
            }
        }
    }
    else if (statusStyle == 1)
    {
        // ================= S2: CHỦ ĐỀ TỐC ĐỘ (SPEEDOMETER & RPM GAUGE) =================
        // Header
        u8g2.setFont(FONT_U8G2_SMALL);
        u8g2.drawStr(4, 1, timeStr.c_str());

        if (bleConnected)
            u8g2.drawDisc(50, 6, 2);
        else
            u8g2.drawCircle(50, 6, 2);

        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "%.1fV", batteryVoltage);
        int vLen = u8g2.getStrWidth(vBuf);
        u8g2.drawStr(124 - vLen, 1, vBuf);
        u8g2.drawHLine(0, 13, 128);

        // Tốc độ số lớn 28pt
        char sBuf[16];
        snprintf(sBuf, sizeof(sBuf), "%d", gpsSpeed);
        u8g2.setFont(FONT_U8G2_BIG_CLOCK);
        u8g2.drawStr(20, 17, sBuf);

        u8g2.setFont(FONT_U8G2_LABEL_BOLD);
        u8g2.drawStr(80, 28, "KM/H");

        // Thanh Gauge tốc độ 128px toàn màn hình ở đáy
        u8g2.drawFrame(0, 53, 128, 9);
        int barW = (gpsSpeed * 128) / 120;
        if (barW > 128)
            barW = 128;
        if (barW > 0)
            u8g2.drawBox(0, 53, barW, 9);
    }
    else if (statusStyle == 2)
    {
        // ================= S3: CHỦ ĐỀ ĐIỆN ÁP & NĂNG LƯỢNG (VOLTAGE & BATTERY MONITOR) =================
        // Header: Giờ & Mức pin điện thoại
        u8g2.setFont(FONT_U8G2_SMALL);
        u8g2.drawStr(4, 1, timeStr.c_str());

        if (bleConnected)
            u8g2.drawDisc(50, 6, 2);
        else
            u8g2.drawCircle(50, 6, 2);

        char pBuf[16];
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        snprintf(pBuf, sizeof(pBuf), "DT:%d%%%s", pBat, phoneBatteryCharging ? "+" : "");
        int pLen = u8g2.getStrWidth(pBuf);
        u8g2.drawStr(124 - pLen, 1, pBuf);
        u8g2.drawHLine(0, 13, 128);

        // Điện áp ắc quy xe số lớn 28pt ở trung tâm
        char vBigBuf[16];
        snprintf(vBigBuf, sizeof(vBigBuf), "%.1f", batteryVoltage);
        u8g2.setFont(FONT_U8G2_BIG_CLOCK);
        int vBigLen = u8g2.getStrWidth(vBigBuf);
        int startVX = (128 - vBigLen - 32) / 2;
        if (startVX < 4)
            startVX = 4;
        u8g2.drawStr(startVX, 17, vBigBuf);

        u8g2.setFont(FONT_U8G2_LABEL_BOLD);
        u8g2.drawStr(startVX + vBigLen + 4, 28, "VOLT");

        // Thước đo điện áp dải 10.0V - 14.8V
        u8g2.drawFrame(0, 52, 128, 10);
        u8g2.drawVLine(42, 52, 10); // Vạch 11.6V (Ngưỡng bình yếu)
        u8g2.drawVLine(88, 52, 10); // Vạch 13.5V (Ngưỡng đang nạp)
        int vBar = (int)((batteryVoltage - 10.0f) * 128.0f / 4.8f);
        if (vBar < 0)
            vBar = 0;
        if (vBar > 128)
            vBar = 128;
        if (vBar > 0)
            u8g2.drawBox(0, 54, vBar, 6);
    }
    else
    {
        // ================= S4: CHỦ ĐỀ THỜI TIẾT & MÔI TRƯỜNG (WEATHER FOCUS) =================
        // Cột trái: Icon thời tiết Vector lớn
        drawWeatherIconVector(4, 6, weatherIcon);

        // Đường phân cách dọc
        u8g2.drawVLine(48, 0, 64);

        if (bleConnected)
            u8g2.drawDisc(42, 6, 2);
        else
            u8g2.drawCircle(42, 6, 2);

        // Cột phải: Nhiệt độ số lớn + Ngày giờ + Điện áp
        char tBuf[16];
        snprintf(tBuf, sizeof(tBuf), "%.0fC", weatherTemp > -50.0f ? weatherTemp : 29.0f);
        u8g2.setFont(FONT_U8G2_MID_CLOCK);
        u8g2.drawStr(54, 4, tBuf);

        u8g2.setFont(FONT_U8G2_SMALL);
        u8g2.drawStr(54, 32, dateStr.c_str());

        char subBuf[20];
        snprintf(subBuf, sizeof(subBuf), "%s | %.1fV", timeStr.c_str(), batteryVoltage);
        u8g2.drawStr(54, 48, subBuf);
    }

    // Vẽ Overlay Cảnh báo giao thông (nếu có)
    drawTrafficWarningOverlay();

    u8g2.sendBuffer();
}

void drawVectorTurnIcon(int dirIdx, int xOffset, int yOffset)
{
    // dirIdx theo chuẩn TYMAP & Android Navigation:
    // 0: Thẳng, 1: Chếch phải, 2: Phải, 3: Gắt phải, 4: Chếch trái, 5: Trái, 6: Gắt trái
    // 7: U-turn trái, 8: U-turn phải, 9, 11, 12, 13: Vòng xuyến, 10, 14: Đích
    // 15: Nhánh/sát trái, 16: Nhánh/sát phải
    if (dirIdx == 2 || dirIdx == 3)
    {
        // Rẽ phải 90 độ / Rẽ gắt phải (Right / Sharp Right)
        u8g2.drawBox(xOffset + 12, yOffset + 14, 5, 26);
        u8g2.drawBox(xOffset + 14, yOffset + 14, 20, 5);
        u8g2.drawTriangle(xOffset + 38, yOffset + 16, xOffset + 26, yOffset + 6, xOffset + 26, yOffset + 26);
    }
    else if (dirIdx == 1 || dirIdx == 16)
    {
        // Chếch phải / Sát phải / Nhánh phải (45 độ - Slight Right)
        u8g2.drawBox(xOffset + 14, yOffset + 24, 5, 16);
        for (int i = 0; i < 5; i++) {
            u8g2.drawLine(xOffset + 14 + i, yOffset + 24, xOffset + 28 + i, yOffset + 10);
        }
        u8g2.drawTriangle(xOffset + 38, yOffset + 12, xOffset + 26, yOffset + 4, xOffset + 24, yOffset + 20);
    }
    else if (dirIdx == 5 || dirIdx == 6)
    {
        // Rẽ trái 90 độ / Rẽ gắt trái (Left / Sharp Left)
        u8g2.drawBox(xOffset + 26, yOffset + 14, 5, 26);
        u8g2.drawBox(xOffset + 8, yOffset + 14, 20, 5);
        u8g2.drawTriangle(xOffset + 2, yOffset + 16, xOffset + 14, yOffset + 6, xOffset + 14, yOffset + 26);
    }
    else if (dirIdx == 4 || dirIdx == 15)
    {
        // Chếch trái / Sát trái / Nhánh trái (45 độ - Slight Left)
        u8g2.drawBox(xOffset + 24, yOffset + 24, 5, 16);
        for (int i = 0; i < 5; i++) {
            u8g2.drawLine(xOffset + 24 + i, yOffset + 24, xOffset + 10 + i, yOffset + 10);
        }
        u8g2.drawTriangle(xOffset + 4, yOffset + 12, xOffset + 16, yOffset + 4, xOffset + 18, yOffset + 20);
    }
    else if (dirIdx == 7 || dirIdx == 8)
    {
        // Quay đầu (U-Turn: 7 = Trái, 8 = Phải)
        int leftX = (dirIdx == 7) ? xOffset + 8 : xOffset + 14;
        int rightX = leftX + 16;
        u8g2.drawBox(rightX, yOffset + 18, 5, 22);
        u8g2.drawBox(leftX, yOffset + 18, 5, 22);
        u8g2.drawBox(leftX, yOffset + 12, 18, 5);
        if (dirIdx == 7) {
            u8g2.drawTriangle(leftX + 2, yOffset + 42, leftX - 6, yOffset + 30, leftX + 10, yOffset + 30);
        } else {
            u8g2.drawTriangle(rightX + 2, yOffset + 42, rightX - 6, yOffset + 30, rightX + 10, yOffset + 30);
        }
    }
    else if (dirIdx == 9 || dirIdx == 11 || dirIdx == 12 || dirIdx == 13)
    {
        // Vòng xuyến (Roundabout)
        u8g2.drawCircle(xOffset + 22, yOffset + 22, 12);
        u8g2.drawCircle(xOffset + 22, yOffset + 22, 8);
        u8g2.drawTriangle(xOffset + 22, yOffset + 4, xOffset + 16, yOffset + 14, xOffset + 28, yOffset + 14);
    }
    else if (dirIdx == 10 || dirIdx == 14)
    {
        // Đến đích (Flag icon / Arrive)
        u8g2.drawBox(xOffset + 12, yOffset + 8, 3, 32);
        u8g2.drawTriangle(xOffset + 15, yOffset + 8, xOffset + 32, yOffset + 16, xOffset + 15, yOffset + 24);
    }
    else
    {
        // Mặc định: Đi thẳng (Straight arrow)
        u8g2.drawBox(xOffset + 18, yOffset + 16, 5, 24);
        u8g2.drawTriangle(xOffset + 20, yOffset + 4, xOffset + 8, yOffset + 18, xOffset + 32, yOffset + 18);
    }
}

// -------------------------------------------------------------
// 2. MÀN HÌNH HUD (DẪN ĐƯỜNG TURN-BY-TURN - 4 PHONG CÁCH TÙY BIẾN)
// -------------------------------------------------------------
void drawHUD()
{
    u8g2.clearBuffer();
    u8g2.setFontPosTop();
    u8g2.setDrawColor(1);

    // Kiểm tra trạng thái cảnh báo tốc độ / camera (nhấp nháy phần tốc độ thay vì hiện popup văn bản)
    // Kiểm tra trạng thái cảnh báo tốc độ / camera (nhấp nháy phần tốc độ thay vì hiện popup văn bản)
    if (isTrafficWarningActive && (millis() - trafficWarningStartTime > 8000))
    {
        isTrafficWarningActive = false;
    }
    bool isWarningBlink = isTrafficWarningActive && ((millis() / 250) % 2 == 0);
    int limitVal = (trafficWarningValue > 0) ? trafficWarningValue : 60;

    String street = nextStreet.length() > 0 ? nextStreet : "Đang dẫn đường...";
    String dStr = distToNext.length() > 0 ? distToNext : "0M";
    dStr.toUpperCase();

    if (hudStyle == 0)
    {
        // ---------------- H1: CLASSIC BOXED (ƯU TIÊN TÊN ĐƯỜNG & KHOẢNG CÁCH) ----------------
        // 1. Phía trên bên trái: LUÔN CÓ Icon rẽ chuẩn 48x48 (Bitmap hoặc Vector)
        if (hasCustomIcon)
        {
            drawCustomIcon(customIconBitmap, 0, 0, 1);
        }
        else
        {
            drawVectorTurnIcon(navDirIdx, 0, 0);
        }

        // 2. Khu vực bên phải: TÊN ĐƯỜNG TIẾNG VIỆT (Tối ưu 3 dòng, thoáng đãng)
        myFont.set_font(FONT_VIETNAMESE_BODY);
        drawWrappedTextMyFont(50, 0, 78, 14, 3, street);

        // 3. Phía dưới bên trái: KHOẢNG CÁCH RẼ LỚN (Ưu tiên số 1, font đậm rõ nét)
        u8g2.setFont(FONT_U8G2_DIST);
        u8g2.drawStr(2, 48, dStr.c_str());

        // 4. Phía dưới bên phải: TỐC ĐỘ XE & CẢNH BÁO TỐC ĐỘ GIỚI HẠN
        char spdBuf[24];
        if (isTrafficWarningActive)
        {
            snprintf(spdBuf, sizeof(spdBuf), "[%d] %dkm", limitVal, gpsSpeed);
        }
        else
        {
            snprintf(spdBuf, sizeof(spdBuf), "%d km/h", gpsSpeed);
        }
        u8g2.setFont(FONT_U8G2_LABEL_BOLD);
        int spdLen = u8g2.getStrWidth(spdBuf);
        int spdX = 128 - spdLen - 2;
        int spdY = 48;

        if (isTrafficWarningActive && isWarningBlink)
        {
            // Nhấp nháy đảo màu vùng tốc độ khi có cảnh báo
            u8g2.drawRBox(spdX - 3, spdY - 1, spdLen + 5, 16, 2);
            u8g2.setDrawColor(0);
            u8g2.drawStr(spdX, spdY, spdBuf);
            u8g2.setDrawColor(1);
        }
        else
        {
            u8g2.drawStr(spdX, spdY, spdBuf);
        }
    }
    else if (hudStyle == 1)
    {
        // ---------------- H2: SPLIT DASHBOARD (Chia đôi đối xứng) ----------------
        // Cột trái (x=0..54): Tốc độ xe lớn + KM/H + mini speed gauge
        char sBuf[16];
        snprintf(sBuf, sizeof(sBuf), "%d", gpsSpeed);
        u8g2.setFont(FONT_U8G2_MID_CLOCK);
        int sLen = u8g2.getStrWidth(sBuf);
        u8g2.setFont(FONT_U8G2_LABEL_BOLD);
        int kmhLen = u8g2.getStrWidth("KM/H");

        if (isTrafficWarningActive)
        {
            char limBuf[16];
            snprintf(limBuf, sizeof(limBuf), "MAX %d", limitVal);
            u8g2.setFont(FONT_U8G2_SMALL);
            int limLen = u8g2.getStrWidth(limBuf);

            if (isWarningBlink)
            {
                u8g2.drawRBox(2, 2, 50, 48, 3);
                u8g2.setDrawColor(0);
                u8g2.drawStr((54 - limLen) / 2, 4, limBuf);
                u8g2.setFont(FONT_U8G2_MID_CLOCK);
                u8g2.drawStr((54 - sLen) / 2, 16, sBuf);
                u8g2.setFont(FONT_U8G2_LABEL_BOLD);
                u8g2.drawStr((54 - kmhLen) / 2, 36, "KM/H");
                u8g2.setDrawColor(1);
            }
            else
            {
                u8g2.drawStr((54 - limLen) / 2, 4, limBuf);
                u8g2.setFont(FONT_U8G2_MID_CLOCK);
                u8g2.drawStr((54 - sLen) / 2, 16, sBuf);
                u8g2.setFont(FONT_U8G2_LABEL_BOLD);
                u8g2.drawStr((54 - kmhLen) / 2, 36, "KM/H");
            }
        }
        else
        {
            u8g2.setFont(FONT_U8G2_MID_CLOCK);
            u8g2.drawStr((54 - sLen) / 2, 6, sBuf);
            u8g2.setFont(FONT_U8G2_LABEL_BOLD);
            u8g2.drawStr((54 - kmhLen) / 2, 34, "KM/H");
        }

        // Vạch tốc độ
        u8g2.drawFrame(4, 52, 46, 6);
        int barW = (gpsSpeed * 46) / 120;
        if (barW > 46)
            barW = 46;
        if (barW > 0)
            u8g2.drawBox(4, 52, barW, 6);

        // Đường phân cách dọc
        u8g2.drawVLine(54, 0, 64);

        // Cột phải (x=56..127): LUÔN CÓ ICON RẼ + KHOẢNG CÁCH + TÊN ĐƯỜNG
        if (hasCustomIcon)
        {
            drawCustomIcon(customIconBitmap, 58, 0, 1);
        }
        else
        {
            drawVectorTurnIcon(navDirIdx, 56, 0);
        }

        u8g2.setFont(FONT_U8G2_DIST);
        u8g2.drawStr(94, 8, dStr.c_str());

        // Tên đường tiếng Việt 2 dòng
        myFont.set_font(FONT_VIETNAMESE_BODY);
        drawWrappedTextMyFont(56, 35, 72, 14, 2, street);
    }
    else if (hudStyle == 2)
    {
        // ---------------- H3: BIG ARROW FOCUS (Mũi tên lớn + Khoảng cách + Tên đường) ----------------
        // Cột trái: Mũi tên rẽ
        if (hasCustomIcon)
        {
            drawCustomIcon(customIconBitmap, 2, 2, 1);
        }
        else
        {
            drawVectorTurnIcon(navDirIdx, 6, 2);
        }

        u8g2.setFont(FONT_U8G2_DIST);
        int dLen = u8g2.getStrWidth(dStr.c_str());
        u8g2.drawStr((48 - dLen) / 2, 48, dStr.c_str());

        // Cột phải: Tên đường + Tốc độ + ETA
        myFont.set_font(FONT_VIETNAMESE_BODY);
        drawWrappedTextMyFont(50, 0, 78, 14, 2, street);

        char spdBuf[24];
        if (isTrafficWarningActive)
        {
            snprintf(spdBuf, sizeof(spdBuf), "[%d] %d km/h", limitVal, gpsSpeed);
        }
        else
        {
            snprintf(spdBuf, sizeof(spdBuf), "%d km/h", gpsSpeed);
        }
        u8g2.setFont(FONT_U8G2_LABEL_BOLD);
        int sLen = u8g2.getStrWidth(spdBuf);

        if (isTrafficWarningActive && isWarningBlink)
        {
            u8g2.drawRBox(48, 29, sLen + 6, 16, 2);
            u8g2.setDrawColor(0);
            u8g2.drawStr(51, 30, spdBuf);
            u8g2.setDrawColor(1);
        }
        else
        {
            u8g2.drawStr(50, 30, spdBuf);
        }

        if (eta.length() > 0)
        {
            char etaBuf[16];
            snprintf(etaBuf, sizeof(etaBuf), "ETA %s", eta.c_str());
            u8g2.setFont(FONT_U8G2_SMALL);
            int eLen = u8g2.getStrWidth(etaBuf);
            u8g2.drawStr(128 - eLen - 2, 49, etaBuf);
        }
    }
    else
    {
        // ---------------- H4: RACING TELEMETRY (Khoảng cách + Tên đường + RPM) ----------------
        // Header: Thanh tốc độ RPM
        int barW = (gpsSpeed * 76) / 120;
        if (barW > 76)
            barW = 76;
        u8g2.drawFrame(0, 0, 76, 6);
        if (barW > 0)
            u8g2.drawBox(0, 0, barW, 6);

        char spdBuf[24];
        if (isTrafficWarningActive)
        {
            snprintf(spdBuf, sizeof(spdBuf), "MAX:%d %dK", limitVal, gpsSpeed);
        }
        else
        {
            snprintf(spdBuf, sizeof(spdBuf), "%d KM/H", gpsSpeed);
        }
        u8g2.setFont(FONT_U8G2_SMALL);
        int sLen = u8g2.getStrWidth(spdBuf);
        int spdX = 128 - sLen - 2;

        if (isTrafficWarningActive && isWarningBlink)
        {
            u8g2.drawRBox(spdX - 2, 0, sLen + 4, 10, 2);
            u8g2.setDrawColor(0);
            u8g2.drawStr(spdX, 0, spdBuf);
            u8g2.setDrawColor(1);
        }
        else
        {
            u8g2.drawStr(spdX, 0, spdBuf);
        }

        // Thân giữa: Icon rẽ + khoảng cách rẽ lớn + tên đường tiếng Việt
        if (hasCustomIcon)
        {
            drawCustomIcon(customIconBitmap, 2, 10, 1);
        }
        else
        {
            drawVectorTurnIcon(navDirIdx, 2, 8);
        }

        u8g2.setFont(FONT_U8G2_DIST);
        u8g2.drawStr(46, 10, dStr.c_str());

        myFont.set_font(FONT_VIETNAMESE_BODY);
        drawWrappedTextMyFont(46, 22, 82, 14, 2, street);

        // Footer: Điện áp xe & ETA
        u8g2.drawHLine(0, 51, 128);
        u8g2.setFont(FONT_U8G2_SMALL);
        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "XE:%.1fV", batteryVoltage);
        u8g2.drawStr(2, 53, vBuf);

        if (eta.length() > 0)
        {
            char etaBuf[16];
            snprintf(etaBuf, sizeof(etaBuf), "ETA %s", eta.c_str());
            int eLen = u8g2.getStrWidth(etaBuf);
            u8g2.drawStr(128 - eLen - 2, 53, etaBuf);
        }
    }

    u8g2.sendBuffer();
}

void drawMiniTurnIcon(int dirIdx, int cx, int cy)
{
    // dirIdx theo chuẩn TYMAP & Android Navigation:
    // 2, 3: Phải; 1, 16: Chếch phải; 5, 6: Trái; 4, 15: Chếch trái; 7, 8: U-turn; 9, 11, 12, 13: Vòng xuyến; 10, 14: Đích; else: Đi thẳng
    if (dirIdx == 5 || dirIdx == 6) {
        // Rẽ trái 90 độ / gắt trái
        u8g2.drawBox(cx + 3, cy - 2, 3, 14);
        u8g2.drawBox(cx - 5, cy - 2, 10, 3);
        u8g2.drawTriangle(cx - 8, cy - 1, cx - 3, cy - 6, cx - 3, cy + 4);
    } else if (dirIdx == 4 || dirIdx == 15) {
        // Chếch trái 45 độ
        u8g2.drawBox(cx + 2, cy + 3, 3, 9);
        u8g2.drawLine(cx + 3, cy + 3, cx - 4, cy - 4);
        u8g2.drawLine(cx + 2, cy + 3, cx - 5, cy - 4);
        u8g2.drawTriangle(cx - 7, cy - 4, cx - 2, cy - 8, cx - 1, cy + 1);
    } else if (dirIdx == 2 || dirIdx == 3) {
        // Rẽ phải 90 độ / gắt phải
        u8g2.drawBox(cx - 5, cy - 2, 3, 14);
        u8g2.drawBox(cx - 3, cy - 2, 10, 3);
        u8g2.drawTriangle(cx + 8, cy - 1, cx + 3, cy - 6, cx + 3, cy + 4);
    } else if (dirIdx == 1 || dirIdx == 16) {
        // Chếch phải 45 độ
        u8g2.drawBox(cx - 5, cy + 3, 3, 9);
        u8g2.drawLine(cx - 4, cy + 3, cx + 3, cy - 4);
        u8g2.drawLine(cx - 3, cy + 3, cx + 4, cy - 4);
        u8g2.drawTriangle(cx + 7, cy - 4, cx + 2, cy - 8, cx + 1, cy + 1);
    } else if (dirIdx == 7 || dirIdx == 8) {
        // Quay đầu U-turn (7: Trái, 8: Phải)
        u8g2.drawFrame(cx - 5, cy - 4, 11, 15);
        if (dirIdx == 7) {
            u8g2.drawTriangle(cx - 5, cy + 11, cx - 9, cy + 6, cx - 1, cy + 6);
        } else {
            u8g2.drawTriangle(cx + 5, cy + 11, cx + 1, cy + 6, cx + 9, cy + 6);
        }
    } else if (dirIdx == 9 || dirIdx == 11 || dirIdx == 12 || dirIdx == 13) {
        // Vòng xuyến
        u8g2.drawCircle(cx, cy + 2, 6);
        u8g2.drawTriangle(cx, cy - 6, cx - 3, cy - 2, cx + 3, cy - 2);
    } else if (dirIdx == 10 || dirIdx == 14) {
        // Đích
        u8g2.drawVLine(cx - 3, cy - 6, 16);
        u8g2.drawTriangle(cx - 2, cy - 6, cx + 5, cy - 2, cx - 2, cy + 2);
    } else {
        // Đi thẳng
        u8g2.drawBox(cx - 1, cy - 1, 3, 13);
        u8g2.drawTriangle(cx, cy - 7, cx - 5, cy - 1, cx + 5, cy - 1);
    }
}

// -------------------------------------------------------------
// 3. MÀN HÌNH MAP (BẢN ĐỒ 1BPP STREAMING TỪ APP + HUD BÊN PHẢI)
// -------------------------------------------------------------
void drawMAP()
{
    u8g2.clearBuffer();
    u8g2.setFontPosTop();
    u8g2.setDrawColor(1);

    if (hasActiveOledImage)
    {
        // 1. oledBuffer chứa 1024 bytes (128x64 1bpp theo layout buffer bộ nhớ U8g2 / Page vertical LSB)
        memcpy(u8g2.getBufferPtr(), oledBuffer, 1024);
    }
    else
    {
        // Layout chờ bản đồ đồng bộ toàn màn hình 128x64
        u8g2.drawFrame(0, 0, 127, 63);
        myFont.set_font(FONT_VIETNAMESE_BODY);
        myFont.print(16, 24, (char *)"Đang tải map...", 1, 0);
    }

    // 2. Chỉ khi đang trong lộ trình dẫn đường (isNavigating), vẽ overlay HUD theo mapStyle
    if (isNavigating)
    {
        if (mapStyle == 0) // MẪU 1: Thuần Map Toàn Màn Hình 128x64 kèm Mini HUD & Tốc độ nổi
        {
            // Mini HUD: Hộp chỉ dẫn bo góc nhỏ gọn góc trên phải (x=78..126, y=1..22)
            u8g2.setDrawColor(0);
            u8g2.drawRBox(78, 1, 49, 21, 2);
            u8g2.setDrawColor(1);
            u8g2.drawRFrame(78, 1, 49, 21, 2);

            // Mũi tên rẽ nhỏ gọn (drawMiniTurnIcon)
            drawMiniTurnIcon(navDirIdx, 116, 10);

            // Khoảng cách rẽ
            u8g2.setFont(FONT_U8G2_SMALL);
            String dStr = distToNext.length() > 0 ? distToNext : "0m";
            dStr.toUpperCase();
            u8g2.drawStr(82, 7, dStr.c_str());

            // Tốc độ xe nhỏ gọn ở góc dưới phải
            u8g2.setDrawColor(0);
            u8g2.drawRBox(74, 46, 53, 17, 2);
            u8g2.setDrawColor(1);
            u8g2.drawRFrame(74, 46, 53, 17, 2);

            u8g2.setFont(FONT_U8G2_LABEL_BOLD);
            char spdBuf[8];
            snprintf(spdBuf, sizeof(spdBuf), "%d", gpsSpeed);
            u8g2.drawStr(78, 48, spdBuf);

            u8g2.setFont(FONT_U8G2_TINY);
            u8g2.drawStr(78 + u8g2.getStrWidth(spdBuf) + 3, 52, "km/h");
        }
        else if (mapStyle == 1) // MẪU 2: Chỉ có MAP không (Pure Map 100%)
        {
            // 100% diện tích cho bản đồ lộ trình, không vẽ đè bất kỳ HUD hay tốc độ nào
        }
        else // MẪU 3 (mapStyle == 2): Map Chia Đôi Kèm HUD (M2 - Cũ)
        {
            // Xóa nền đen vùng HUD bên phải để không bị lem pixel từ map
            u8g2.setDrawColor(0);
            u8g2.drawBox(89, 0, 39, 64);
            u8g2.setDrawColor(1);

            // Đường kẻ dọc phân cách sắc nét (x = 88)
            u8g2.drawVLine(88, 0, 64);

            // --- PHẦN TRÊN: Icon mũi tên rẽ & Khoảng cách rẽ (lấy từ dữ liệu BLE Navigation) ---
            drawMiniTurnIcon(navDirIdx, 108, 9);

            u8g2.setFont(FONT_U8G2_SMALL);
            String dStr = distToNext.length() > 0 ? distToNext : "0m";
            dStr.toUpperCase();
            int dLen = u8g2.getStrWidth(dStr.c_str());
            int dX = 89 + (39 - dLen) / 2;
            if (dX < 90) dX = 90;
            u8g2.drawStr(dX, 21, dStr.c_str());

            // Đường gạch ngang phân cách nhẹ giữa HUD trên và dưới
            u8g2.drawHLine(91, 33, 35);

            // --- PHẦN DƯỚI: Tốc độ xe thực tế (lấy từ dữ liệu BLE GPS) ---
            u8g2.setFont(FONT_U8G2_DIST);
            char spdBuf[8];
            snprintf(spdBuf, sizeof(spdBuf), "%d", gpsSpeed);
            int sLen = u8g2.getStrWidth(spdBuf);
            int sX = 89 + (39 - sLen) / 2;
            if (sX < 90) sX = 90;
            u8g2.drawStr(sX, 36, spdBuf);

            u8g2.setFont(FONT_U8G2_TINY);
            int kLen = u8g2.getStrWidth("km/h");
            u8g2.drawStr(89 + (39 - kLen) / 2, 53, "km/h");
        }
    }

    // 3. Vẽ Overlay Cảnh báo giao thông (nếu có)
    drawTrafficWarningOverlay();

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 4. MÀN HÌNH NOTIFICATION (THÔNG BÁO CUỘC GỌI / ZALO / SMS)
// -------------------------------------------------------------
void drawNOTIF()
{
    u8g2.clearBuffer();
    u8g2.setFontPosTop();
    u8g2.setDrawColor(1);

    if (notifCount > 0)
    {
        NotificationItem &item = notifList[notifViewIndex % notifCount];

        // Khung viền thông báo
        u8g2.drawRFrame(0, 0, 128, 64, 3);
        u8g2.drawHLine(0, 15, 128);

        // Header: Tên Ứng Dụng (FontMaker tiếng Việt)
        myFont.set_font(FONT_VIETNAMESE_TITLE);
        String appName = item.app.length() > 0 ? item.app : "THÔNG BÁO";
        appName.toUpperCase();
        myFont.print(4, 1, (char *)appName.c_str(), 1, 0);

        // Người gửi / Tiêu đề (FontMaker tiếng Việt - tự co gọn nếu quá dài)
        myFont.set_font(FONT_VIETNAMESE_TITLE);
        String title = item.title;
        while (title.length() > 0 && myFont.getLength(title.c_str()) > 120)
        {
            title = title.substring(0, title.length() - 1);
        }
        if (title.length() < item.title.length() && title.length() > 2)
        {
            title = title.substring(0, title.length() - 2) + "..";
        }
        myFont.print(4, 16, (char *)title.c_str(), 1, 0);

        // Nội dung tin nhắn (FontMaker tiếng Việt - ngắt dòng tự động)
        myFont.set_font(FONT_VIETNAMESE_BODY);
        drawWrappedTextMyFont(4, 32, 120, 14, 2, item.msg);
    }
    else
    {
        myFont.set_font(FONT_VIETNAMESE_BODY);
        myFont.print(14, 25, (char *)"Không có thông báo", 1, 0);
    }

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 5. MÀN HÌNH THÔNG TIN HỆ THỐNG (INFO)
// -------------------------------------------------------------
void drawINFO()
{
    u8g2.clearBuffer();
    u8g2.setFontPosTop();
    u8g2.setDrawColor(1);

    u8g2.drawFrame(0, 0, 128, 64);
    u8g2.setFont(FONT_U8G2_LABEL_BOLD);
    int titleW = u8g2.getStrWidth("TYMAP v1.0.9");
    u8g2.drawStr((128 - titleW) / 2, 1, "TYMAP v1.0.9");
    u8g2.drawHLine(0, 15, 128);

    u8g2.setFont(FONT_U8G2_SMALL);

    // RAM khả dụng
    char ramBuf[32];
    snprintf(ramBuf, sizeof(ramBuf), "Free Heap: %d KB", ESP.getFreeHeap() / 1024);
    u8g2.drawStr(4, 18, ramBuf);

    // Điện áp & Pin
    char batBuf[32];
    snprintf(batBuf, sizeof(batBuf), "XE: %.1fV | DT: %d%%", batteryVoltage, phoneBatteryLevel >= 0 ? phoneBatteryLevel : 100);
    u8g2.drawStr(4, 32, batBuf);

    // Uptime
    char upBuf[32];
    unsigned long sec = millis() / 1000;
    snprintf(upBuf, sizeof(upBuf), "Uptime: %02lu:%02lu:%02lu", sec / 3600, (sec % 3600) / 60, sec % 60);
    u8g2.drawStr(4, 46, upBuf);

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 6. CẢNH BÁO GIAO THÔNG OVERLAY (TỐC ĐỘ / CAMERA)
// -------------------------------------------------------------
void drawTrafficWarningOverlay()
{
    if (!isTrafficWarningActive)
        return;

    if (millis() - trafficWarningStartTime > 6000)
    {
        isTrafficWarningActive = false;
        return;
    }

    bool isBlink = ((millis() - trafficWarningStartTime) / 250) % 2 == 0;
    u8g2.setFontPosTop();

    // Hộp cảnh báo góc trên phải
    u8g2.setDrawColor(isBlink ? 1 : 0);
    u8g2.drawBox(74, 0, 54, 22);
    u8g2.setDrawColor(isBlink ? 0 : 1);
    u8g2.drawFrame(74, 0, 54, 22);

    u8g2.setFont(FONT_U8G2_LABEL_BOLD);
    if (trafficWarningType == 1 || trafficWarningType == 3)
    {
        int w = u8g2.getStrWidth("CAMERA");
        u8g2.drawStr(74 + (54 - w) / 2, 4, "CAMERA");
    }
    else
    {
        char spdWarn[16];
        snprintf(spdWarn, sizeof(spdWarn), "MAX %d", trafficWarningValue > 0 ? trafficWarningValue : 60);
        int w = u8g2.getStrWidth(spdWarn);
        u8g2.drawStr(74 + (54 - w) / 2, 4, spdWarn);
    }
    u8g2.setDrawColor(1);
}

// -------------------------------------------------------------
// 7. TIẾN TRÌNH CẬP NHẬT BLE OTA
// -------------------------------------------------------------
void drawOtaProgressScreen()
{
    u8g2.clearBuffer();
    u8g2.setFontPosTop();

    u8g2.drawFrame(0, 0, 128, 64);
    myFont.set_font(FONT_VIETNAMESE_TITLE);
    myFont.print(10, 2, (char *)"NÂNG CẤP FIRMWARE", 1, 0);
    u8g2.drawHLine(0, 17, 128);

    int pct = 0;
    if (otaExpectedSize > 0)
    {
        pct = (int)((otaWritten * 100) / otaExpectedSize);
    }

    // Thanh Progress Bar
    u8g2.drawFrame(14, 25, 100, 12);
    u8g2.drawBox(16, 27, (pct * 96) / 100, 8);

    // Phần trăm
    u8g2.setFont(FONT_U8G2_SMALL);
    char pBuf[32];
    snprintf(pBuf, sizeof(pBuf), "%d%% (%d/%d KB)", pct, otaWritten / 1024, otaExpectedSize / 1024);
    int pLen = u8g2.getStrWidth(pBuf);
    u8g2.drawStr((128 - pLen) / 2, 44, pBuf);

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 8. LOGO KHỞI ĐỘNG (KÈM THANH TIẾN TRÌNH LOADING 3 GIÂY)
// -------------------------------------------------------------
void drawLogoSplash(unsigned long currentTime, unsigned long startTime, unsigned long introDuration)
{
    u8g2.clearBuffer();

    // 1. Vẽ Logo 64x64 căn chính giữa màn hình (x = 32, y = 0, width = 64, height = 64)
    u8g2.setDrawColor(1);
    u8g2.drawXBMP(32, 0, 64, 64, logo_pnt_64x64);

    // 2. Tính toán tiến trình thanh Loading (0.0 -> 1.0)
    float progress = 0.0f;
    if (introDuration > 0 && currentTime >= startTime)
    {
        progress = (float)(currentTime - startTime) / (float)introDuration;
    }
    if (progress > 1.0f)
        progress = 1.0f;
    if (progress < 0.0f)
        progress = 0.0f;

    // 3. Khung viền thanh Loading (x: 14, y: 57, w: 100, h: 5)
    int barX = 14;
    int barY = 57;
    int barW = 100;
    int barH = 5;

    u8g2.drawFrame(barX, barY, barW, barH);

    int fillW = (int)(progress * (barW - 2));
    if (fillW > (barW - 2))
        fillW = barW - 2;
    if (fillW > 0)
    {
        u8g2.drawBox(barX + 1, barY + 1, fillW, barH - 2);
    }

    u8g2.sendBuffer();
}
