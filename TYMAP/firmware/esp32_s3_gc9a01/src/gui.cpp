#include "gui.h"
#include "logo.h"

bool showMapHudCard = true;
uint8_t statusStyle = 0;          // 0=S4 Sport Chrono Radar (Mẫu 2 Mặc định), 1=S5 Classic Analog, 2=S3 Dual Pill
uint8_t notifStyle = 1;           // 0=N1 Floating Card 3D, 1=N2 Fullscreen Focus (THUẦN NOTIF - Mặc định)
uint8_t settingCategoryIndex = 0; // 0=Mặt đồng hồ (statusStyle), 1=HUD Bản đồ (mapHudStyle), 2=Thông báo (notifStyle)

// Bộ đệm sóng Oscilloscope thời gian thực
float voltHistory[VOLT_HISTORY_SIZE] = {0};
uint16_t voltHistoryIdx = 0;
float voltMin = 99.0f;
float voltMax = 0.0f;
uint16_t autoSampleIntervalMs = 10; // Chu kỳ lấy mẫu 10ms (100Hz)
static float prevSampleVoltS3 = 12.5f;
static float avgSlewRateS3 = 0.0f;

// Dải điện áp thích ứng thông minh (Adaptive Peak Tracking)
// Tự động nhận diện đỉnh điện áp sạc tối đa của xe (ví dụ 14V khi nổ máy) làm màu Cyber cuối cùng
float dynVoltMin = 10.8f;
float dynVoltMax = 14.0f;

// Bộ đệm sóng tốc độ GPS thời gian thực
float speedHistory[SPEED_HISTORY_SIZE] = {0};
uint16_t speedHistoryIdx = 0;

void pushSpeedSample(float spd)
{
    static bool spdBufferFilled = false;
    if (!spdBufferFilled)
    {
        for (int k = 0; k < SPEED_HISTORY_SIZE; k++)
        {
            speedHistory[k] = spd;
        }
        spdBufferFilled = true;
    }

    speedHistory[speedHistoryIdx] = spd;
    speedHistoryIdx = (speedHistoryIdx + 1) % SPEED_HISTORY_SIZE;
}

void pushVoltSample(float v)
{
    static bool bufferFilled = false;
    if (!bufferFilled)
    {
        for (int k = 0; k < VOLT_HISTORY_SIZE; k++)
        {
            voltHistory[k] = v;
        }
        bufferFilled = true;
        prevSampleVoltS3 = v;
        voltMin = v;
        voltMax = v;
    }

    autoSampleIntervalMs = 10; // Ổn định chu kỳ lấy mẫu 10ms (100Hz), buffer 200 điểm = 2 giây quan sát 1:1

    voltHistory[voltHistoryIdx] = v;
    voltHistoryIdx = (voltHistoryIdx + 1) % VOLT_HISTORY_SIZE;

    // Cập nhật min/max động theo cửa sổ trượt
    static uint16_t scanCounter = 0;
    if (++scanCounter >= 20)
    {
        scanCounter = 0;
        float curMin = 99.0f, curMax = 0.0f;
        for (int k = 0; k < VOLT_HISTORY_SIZE; k++)
        {
            float val = voltHistory[k];
            if (val < curMin) curMin = val;
            if (val > curMax) curMax = val;
        }
        if (curMin < 50.0f) voltMin = curMin;
        if (curMax > 0.0f) voltMax = curMax;
    }
    else
    {
        if (v < voltMin || voltMin > 50.0f)
            voltMin = v;
        if (v > voltMax)
            voltMax = v;
    }
}

// Hàm chuyển đổi màu Neon Gradient mượt mà liên tục theo điện áp thích ứng thông minh
// Tự động nhận diện đỉnh điện áp xe (ví dụ 14V khi nổ máy sạc) làm màu Cyber cuối cùng
uint16_t getVoltNeonColor(float v)
{
    if (isnan(v) || isinf(v) || v <= 0.5f) return TFT_CYAN;

    // Tự động nhận diện đỉnh điện áp max thực tế của xe (Adaptive Peak Tracking)
    if (v > dynVoltMax && v < 18.0f)
    {
        dynVoltMax = v;
    }

    // Cho phép đỉnh sạc từ từ trôi nhẹ về 13.8V nếu trước đó có xung gai vọt áp tạm thời
    static unsigned long lastPeakDecay = 0;
    if (millis() - lastPeakDecay > 10000)
    {
        lastPeakDecay = millis();
        if (dynVoltMax > 13.8f && v < dynVoltMax - 0.3f)
        {
            dynVoltMax -= 0.05f;
        }
    }

    // Tự động bám đáy sụt áp (nhưng không dưới 9.5V để giữ độ an toàn)
    if (v < dynVoltMin && v >= 9.5f)
    {
        dynVoltMin = v;
    }

    float span = dynVoltMax - dynVoltMin;
    if (span < 1.2f) span = 1.2f;

    // Chuẩn hóa theo dải thích ứng thực tế của xe
    float t = (v - dynVoltMin) / span;
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;

    uint8_t r = 0, g = 0, b = 0;
    if (t < 0.25f) // Đáy sụt áp / đề máy: Chuyển từ Neon Red (#FF0055) sang Neon Amber (#FF7700)
    {
        float f = t / 0.25f;
        r = 255;
        g = (uint8_t)(119.0f * f);
        b = (uint8_t)(85.0f * (1.0f - f));
    }
    else if (t < 0.50f) // Bình tĩnh bật khóa (~12V): Chuyển từ Neon Amber (#FF7700) sang Neon Cyan (#00F0FF)
    {
        float f = (t - 0.25f) / 0.25f;
        r = (uint8_t)(255.0f * (1.0f - f));
        g = (uint8_t)(119.0f + (240.0f - 119.0f) * f);
        b = (uint8_t)(255.0f * f);
    }
    else if (t < 0.75f) // Nổ máy dòng sạc lên (~13V): Chuyển từ Neon Cyan (#00F0FF) sang Neon Lime (#00FF66)
    {
        float f = (t - 0.50f) / 0.25f;
        r = 0;
        g = (uint8_t)(240.0f + (255.0f - 240.0f) * f);
        b = (uint8_t)(255.0f * (1.0f - f) + 102.0f * f);
    }
    else // Đạt đỉnh sạc tối đa của xe (ví dụ 14V): Chuyển từ Neon Lime (#00FF66) sang Ultra Cyber Pink (#FF00D4)
    {
        float f = (t - 0.75f) / 0.25f;
        r = (uint8_t)(255.0f * f);
        g = (uint8_t)(255.0f * (1.0f - f));
        b = (uint8_t)(102.0f + (212.0f - 102.0f) * f);
    }

    return color565(r, g, b);
}

// Hàm chuyển đổi màu Neon Gradient mượt mà liên tục theo tốc độ GPS
uint16_t getSpeedNeonColor(float spd)
{
    // Chuẩn hóa dải tốc độ: 0 -> 80 km/h (có thể đạt trên 100 km/h)
    float t = spd / 80.0f;
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;

    uint8_t r = 0, g = 0, b = 0;
    if (t < 0.33f) // 0 -> 26 km/h: Neon Cyan (#00F0FF) -> Neon Lime (#00FF66)
    {
        float f = t / 0.33f;
        r = 0;
        g = (uint8_t)(240.0f + 15.0f * f);
        b = (uint8_t)(255.0f * (1.0f - f) + 102.0f * f);
    }
    else if (t < 0.66f) // 26 -> 53 km/h: Neon Lime (#00FF66) -> Neon Amber (#FFAA00)
    {
        float f = (t - 0.33f) / 0.33f;
        r = (uint8_t)(255.0f * f);
        g = (uint8_t)(255.0f * (1.0f - f) + 170.0f * f);
        b = (uint8_t)(102.0f * (1.0f - f));
    }
    else // 53 -> 80+ km/h: Neon Amber (#FFAA00) -> Cyber Pink (#FF007F)
    {
        float f = (t - 0.66f) / 0.34f;
        r = 255;
        g = (uint8_t)(170.0f * (1.0f - f));
        b = (uint8_t)(127.0f * f);
    }

    return color565(r, g, b);
}

// Vẽ icon 1bpp monochrome custom LÊN SPRITE (TỰ ĐỘNG KHÔNG VẼ NỀN ĐEN TRANSPARENT)
void drawCustomIcon(TFT_eSprite &sprite, const uint8_t *bitmap, int xOffset, int yOffset, int scale, uint16_t fgColor)
{
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
                    sprite.drawPixel(xOffset + x, yOffset + y, fgColor);
                }
                else
                {
                    sprite.fillRect(xOffset + x * scale, yOffset + y * scale, scale, scale, fgColor);
                }
            }
        }
    }
}

void drawCustomIconResized(TFT_eSprite &sprite, const uint8_t *bitmap, int xOffset, int yOffset, int targetW, int targetH, uint16_t fgColor)
{
    for (int y = 0; y < targetH; y++)
    {
        for (int x = 0; x < targetW; x++)
        {
            int srcX = x * 48 / targetW;
            int srcY = y * 48 / targetH;
            if (srcX >= 48)
                srcX = 47;
            if (srcY >= 48)
                srcY = 47;

            int byteIdx = (srcY * 48 + srcX) / 8;
            int bitPos = 7 - (srcX % 8);
            bool isPixel = (bitmap[byteIdx] & (1 << bitPos)) != 0;
            if (isPixel)
            {
                sprite.drawPixel(xOffset + x, yOffset + y, fgColor);
            }
        }
    }
}

// ==========================================
// 1. GIAO DIỆN DẪN ĐƯỜNG (HUD) - PHONG CÁCH GALAXY WATCH 7
// ==========================================
void drawHUD()
{
    String localStreet = "";
    String localDist = "";
    String localTotal = "";
    String localEta = "";
    String localEte = "";
    if (navMutex != NULL && xSemaphoreTake(navMutex, pdMS_TO_TICKS(10)) == pdTRUE)
    {
        localStreet = nextStreet;
        localDist = distToNext;
        localTotal = totalDist;
        localEta = eta;
        localEte = ete;
        xSemaphoreGive(navMutex);
    }
    else
    {
        localStreet = nextStreet;
        localDist = distToNext;
        localTotal = totalDist;
        localEta = eta;
        localEte = ete;
    }

    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Vẽ chỉ dẫn (dir) và ETA/Giờ (màu trắng) ở đỉnh màn hình
    myFont.set_font(FONT_HUD_INFO);
    String part1 = (localEte.length() > 0) ? localEte : ((localTotal.length() > 0) ? localTotal : "20 min");
    String part2 = "  ·  " + ((localEta.length() > 0) ? localEta : rtc.getTime("%H:%M"));
    uint16_t len1 = myFont.getLength(part1);
    uint16_t len2 = myFont.getLength(part2);
    uint16_t totalLen = len1 + len2;
    int startX = 120 - totalLen / 2;

    myFont.print(startX, 22, part1, TFT_SKYBLUE, TFT_BLACK);
    myFont.print(startX + len1, 22, part2, TFT_WHITE, TFT_BLACK);

    // Hiển thị icon thời tiết góc trên bên phải nếu có dữ liệu
    if (weatherIcon.length() > 0)
    {
        drawWeatherIcon(canvasSprite, weatherIcon, 50, 50);
    }

    // 2. Icon hướng rẽ trung tâm (cy=84)
    // Ưu tiên: 1bpp bitmap (Google Maps TBT); fallback: mũi tên vector theo navDirIdx
    if (hasCustomIcon)
    {
        drawCustomIcon(canvasSprite, customIconBitmap, 72, 36, 2);
    }
    else
    {
        int ax = 120, ay = 84; // tâm
        switch (navDirIdx)
        {
        case 4: // slight-left / keep-left
            canvasSprite.drawLine(ax, ay + 30, ax, ay, TFT_WHITE);
            canvasSprite.drawLine(ax, ay, ax - 16, ay - 30, TFT_WHITE);
            canvasSprite.fillTriangle(ax - 16, ay - 30, ax - 8, ay - 16, ax - 24, ay - 20, TFT_WHITE);
            break;
        case 5: // left
            canvasSprite.drawLine(ax, ay + 30, ax, ay, TFT_WHITE);
            canvasSprite.drawLine(ax, ay, ax - 30, ay, TFT_WHITE);
            canvasSprite.fillTriangle(ax - 30, ay, ax - 16, ay - 8, ax - 16, ay + 8, TFT_WHITE);
            break;
        case 6: // sharp-left
            canvasSprite.drawLine(ax, ay + 30, ax, ay, TFT_WHITE);
            canvasSprite.drawLine(ax, ay, ax - 26, ay - 26, TFT_WHITE);
            canvasSprite.fillTriangle(ax - 26, ay - 26, ax - 12, ay - 18, ax - 18, ay - 12, TFT_WHITE);
            break;
        case 1: // slight-right
            canvasSprite.drawLine(ax, ay + 30, ax, ay, TFT_WHITE);
            canvasSprite.drawLine(ax, ay, ax + 16, ay - 30, TFT_WHITE);
            canvasSprite.fillTriangle(ax + 16, ay - 30, ax + 8, ay - 16, ax + 24, ay - 20, TFT_WHITE);
            break;
        case 2: // right
            canvasSprite.drawLine(ax, ay + 30, ax, ay, TFT_WHITE);
            canvasSprite.drawLine(ax, ay, ax + 30, ay, TFT_WHITE);
            canvasSprite.fillTriangle(ax + 30, ay, ax + 16, ay - 8, ax + 16, ay + 8, TFT_WHITE);
            break;
        case 3: // sharp-right
            canvasSprite.drawLine(ax, ay + 30, ax, ay, TFT_WHITE);
            canvasSprite.drawLine(ax, ay, ax + 26, ay - 26, TFT_WHITE);
            canvasSprite.fillTriangle(ax + 26, ay - 26, ax + 12, ay - 18, ax + 18, ay - 12, TFT_WHITE);
            break;
        case 7:
        case 8: // uturn
            canvasSprite.drawArc(ax, ay - 10, 30, 26, 0, 180, TFT_WHITE, TFT_BLACK);
            canvasSprite.drawLine(ax + 30, ay - 10, ax + 30, ay + 28, TFT_WHITE);
            canvasSprite.fillTriangle(ax + 30, ay + 36, ax + 22, ay + 20, ax + 38, ay + 20, TFT_WHITE);
            break;
        default: // 0 straight / arrive / roundabout / unknown
            canvasSprite.drawLine(ax, ay + 30, ax, ay - 30, TFT_WHITE);
            canvasSprite.fillTriangle(ax, ay - 38, ax - 10, ay - 22, ax + 10, ay - 22, TFT_WHITE);
            break;
        }
    }

    // 3. Vẽ tên đường chỉ dẫn ở giữa (tự động cuộn nếu quá dài)
    myFont.set_font(FONT_HUD_STREET);
    uint16_t streetLen = myFont.getLength(localStreet);
    int visibleWidth = 200;
    if (streetLen > visibleWidth)
    {
        clipMinX = 120 - visibleWidth / 2;
        clipMaxX = 120 + visibleWidth / 2;
        clipMinY = 130;
        clipMaxY = 160;

        int range = streetLen - visibleWidth + 40;
        int scrollMs = millis() % (range * 30 + 1000);
        int scrollX = 0;
        if (scrollMs > 1000)
            scrollX = (scrollMs - 1000) / 30;

        myFont.print(120 - visibleWidth / 2 - scrollX, 135, localStreet, TFT_WHITE, TFT_BLACK);

        clipMinX = 0;
        clipMaxX = 240;
        clipMinY = 0;
        clipMaxY = 240;
    }
    else
    {
        myFont.print(120 - streetLen / 2, 135, localStreet, TFT_WHITE, TFT_BLACK);
    }

    // 4. Vẽ khoảng cách rẽ ở dưới
    myFont.set_font(FONT_HUD_DIST);
    uint16_t distLen = myFont.getLength(localDist);
    myFont.print(120 - distLen / 2, 170, localDist, TFT_WHITE, TFT_BLACK);

    // 5. Ô hiển thị Tốc độ GPS (km/h) ở đáy (Tích hợp Biển báo mini khi có cảnh báo)
    char speedBuf[16];
    snprintf(speedBuf, sizeof(speedBuf), "%d km/h", gpsSpeed);
    myFont.set_font(FONT_HUD_INFO);

    if (isTrafficWarningActive)
    {
        bool isBlink = ((millis() - trafficWarningStartTime) / 250) % 2 == 0;
        bool isOverSpeed = (trafficWarningValue > 0 && gpsSpeed > trafficWarningValue);

        // Khung huy hiệu mở rộng chứa cả biển báo tốc độ mini và tốc độ xe GPS
        int badgeW = (trafficWarningType == 3) ? 148 : 132;
        int badgeH = 26;
        int badgeX = 120 - badgeW / 2;
        int badgeY = 202;

        canvasSprite.fillRoundRect(badgeX, badgeY, badgeW, badgeH, 13, color565(15, 23, 42));
        canvasSprite.drawRoundRect(badgeX, badgeY, badgeW, badgeH, 13, (isOverSpeed && isBlink) ? TFT_RED : color565(51, 65, 85));

        // 1. Biển báo mini bên trái trong khung (cx = badgeX + 15, cy = badgeY + 13, r = 10)
        if (trafficWarningType == 0x02 || trafficWarningType == 3)
        {
            drawSpeedLimitSignCompact(canvasSprite, badgeX + 15, badgeY + 13, 10, trafficWarningValue, isBlink && isOverSpeed);
        }
        else
        {
            drawCameraSignCompact(canvasSprite, badgeX + 15, badgeY + 13, 10, isBlink);
        }

        // 2. Tốc độ GPS ở giữa
        uint16_t spdCol = isOverSpeed ? TFT_RED : TFT_GREEN;
        myFont.print(badgeX + 30, badgeY + 6, speedBuf, spdCol, color565(15, 23, 42));

        // 3. Nếu là cả 2 (Speed + Camera) -> vẽ thêm icon camera nhỏ bên phải
        if (trafficWarningType == 3)
        {
            drawCameraSignCompact(canvasSprite, badgeX + badgeW - 15, badgeY + 13, 9, isBlink);
        }
    }
    else
    {
        uint16_t spdLen = myFont.getLength(speedBuf);
        uint16_t badgeW = spdLen + 16;
        if (badgeW < 64)
            badgeW = 64;
        canvasSprite.fillRoundRect(120 - badgeW / 2, 205, badgeW, 22, 10, color565(30, 41, 59));
        canvasSprite.drawRoundRect(120 - badgeW / 2, 205, badgeW, 22, 10, TFT_GREEN);
        myFont.print(120 - spdLen / 2, 209, speedBuf, TFT_GREEN, color565(30, 41, 59));
    }

    // Vẽ Overlay Cảnh báo Giao thông (nếu có)
    drawTrafficWarningOverlay();

    canvasSprite.pushSprite(0, 0);
}

uint8_t mapHudStyle = 0; // 0 = MH1 Compact Floating Pill (85%), 1 = MH3 Minimalist Badge (92%)

// ==========================================
// 1C. GIAO DIỆN BẢN ĐỒ + THẺ NỔI HUD (MAP_HUD_MODE - MẪU MH1 & MH3)
// ==========================================
void drawMapHudOverlay()
{
    if (!showMapHudCard)
        return;

    if (mapHudStyle == 4)
    {
        // ================= MẪU MH5: BẢN ĐỒ THUẦN (Tắt toàn bộ HUD) =================
        return; // Không vẽ đè bất kỳ UI nào lên bản đồ
    }

    String localStreet = "";
    String localDist = "";
    String localEta = "";
    String localEte = "";
    String localTotal = "";
    if (navMutex != NULL && xSemaphoreTake(navMutex, pdMS_TO_TICKS(10)) == pdTRUE)
    {
        localStreet = nextStreet;
        localDist = distToNext;
        localEta = eta;
        localEte = ete;
        localTotal = totalDist;
        xSemaphoreGive(navMutex);
    }
    else
    {
        localStreet = nextStreet;
        localDist = distToNext;
        localEta = eta;
        localEte = ete;
        localTotal = totalDist;
    }

    if (mapHudStyle == 1)
    {
        // ================= MẪU MH2: THANH DƯỚI =================
        uint16_t cardBgColor = color565(15, 23, 42);
        canvasSprite.fillRoundRect(10, 196, 220, 36, 18, cardBgColor);
        canvasSprite.drawRoundRect(10, 196, 220, 36, 18, color565(51, 65, 85));

        // 1. Icon Mũi tên Rẽ bên trái (cx=28, cy=214, r=12)
        int ax = 28, ay = 214;
        uint16_t accent = TFT_GREEN;
        if (hasCustomIcon)
        {
            drawCustomIconResized(canvasSprite, customIconBitmap, 16, 202, 24, 24, accent);
        }
        else
        {
            switch (navDirIdx)
            {
            case 4:
            case 5:
            case 6: // Turn Left
                canvasSprite.drawLine(ax + 4, ay + 5, ax + 4, ay - 2, accent);
                canvasSprite.drawLine(ax + 4, ay - 2, ax - 5, ay - 2, accent);
                canvasSprite.fillTriangle(ax - 5, ay - 2, ax - 1, ay - 5, ax - 1, ay + 1, accent);
                break;
            case 1:
            case 2:
            case 3: // Turn Right
                canvasSprite.drawLine(ax - 4, ay + 5, ax - 4, ay - 2, accent);
                canvasSprite.drawLine(ax - 4, ay - 2, ax + 5, ay - 2, accent);
                canvasSprite.fillTriangle(ax + 5, ay - 2, ax + 1, ay - 5, ax + 1, ay + 1, accent);
                break;
            default: // Straight / Arrow Up
                canvasSprite.drawLine(ax, ay + 5, ax, ay - 5, accent);
                canvasSprite.fillTriangle(ax, ay - 6, ax - 3, ay - 1, ax + 3, ay - 1, accent);
                break;
            }
        }

        // 2. Tên đường chỉ dẫn chữ trắng marquee bên phải (x=50, y=205)
        myFont.set_font(vietnamtimes12);
        uint16_t streetLen = myFont.getLength(localStreet);
        int visibleWidth = 160;
        if (streetLen > visibleWidth)
        {
            clipMinX = 50;
            clipMaxX = 50 + visibleWidth;
            clipMinY = 196;
            clipMaxY = 232;

            int range = streetLen - visibleWidth + 30;
            int scrollMs = millis() % (range * 35 + 1200);
            int scrollX = 0;
            if (scrollMs > 1200)
            {
                scrollX = (scrollMs - 1200) / 35;
            }

            myFont.print(50 - scrollX, 207, localStreet, TFT_WHITE, cardBgColor);

            clipMinX = 0;
            clipMaxX = 240;
            clipMinY = 0;
            clipMaxY = 240;
        }
        else
        {
            myFont.print(50, 207, localStreet, TFT_WHITE, cardBgColor);
        }
    }
    else if (mapHudStyle == 2)
    {
        // ================= MẪU MH3: MINIMALIST BADGE (HIỂN THỊ 92% BẢN ĐỒ) =================
        uint16_t cardBgColor = color565(15, 23, 42);
        uint16_t accent = TFT_ORANGE;

        // 1. Huy hiệu Mũi tên Rẽ ở Góc Trái Đỉnh (cx=42, cy=42, r=18)
        canvasSprite.fillCircle(42, 42, 18, cardBgColor);
        canvasSprite.drawCircle(42, 42, 18, accent);

        if (hasCustomIcon)
        {
            drawCustomIconResized(canvasSprite, customIconBitmap, 29, 29, 26, 26, accent);
        }
        else
        {
            int ax = 42, ay = 42;
            switch (navDirIdx)
            {
            case 4:
            case 5:
            case 6: // Turn Left
                canvasSprite.drawLine(ax + 6, ay + 7, ax + 6, ay - 2, accent);
                canvasSprite.drawLine(ax + 6, ay - 2, ax - 7, ay - 2, accent);
                canvasSprite.fillTriangle(ax - 7, ay - 2, ax - 1, ay - 6, ax - 1, ay + 2, accent);
                break;
            case 1:
            case 2:
            case 3: // Turn Right
                canvasSprite.drawLine(ax - 6, ay + 7, ax - 6, ay - 2, accent);
                canvasSprite.drawLine(ax - 6, ay - 2, ax + 7, ay - 2, accent);
                canvasSprite.fillTriangle(ax + 7, ay - 2, ax + 1, ay - 6, ax + 1, ay + 2, accent);
                break;
            default: // Straight / Arrow Up
                canvasSprite.drawLine(ax, ay + 7, ax, ay - 7, accent);
                canvasSprite.fillTriangle(ax, ay - 8, ax - 4, ay - 1, ax + 4, ay - 1, accent);
                break;
            }
        }

        // 2. Thẻ Pill Khoảng cách ở Đỉnh (x=70, y=28, w=90, h=28, r=14)
        canvasSprite.fillRoundRect(70, 28, 90, 28, 14, cardBgColor);
        canvasSprite.drawRoundRect(70, 28, 90, 28, 14, accent);
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t dLen = myFont.getLength(localDist);
        myFont.print(115 - dLen / 2, 33, localDist, accent, cardBgColor);

        // 3. Thanh Pill Tên đường tối giản ở Đáy (x=30, y=200, w=180, h=28, r=14)
        canvasSprite.fillRoundRect(30, 200, 180, 28, 14, cardBgColor);
        canvasSprite.drawRoundRect(30, 200, 180, 28, 14, color565(51, 65, 85));

        myFont.set_font(vietnamtimes12);
        uint16_t streetLen = myFont.getLength(localStreet);
        int visibleWidth = 160;
        if (streetLen > visibleWidth)
        {
            clipMinX = 40;
            clipMaxX = 40 + visibleWidth;
            clipMinY = 200;
            clipMaxY = 228;

            int range = streetLen - visibleWidth + 30;
            int scrollMs = millis() % (range * 35 + 1200);
            int scrollX = 0;
            if (scrollMs > 1200)
            {
                scrollX = (scrollMs - 1200) / 35;
            }

            myFont.print(40 - scrollX, 206, localStreet, TFT_WHITE, cardBgColor);

            clipMinX = 0;
            clipMaxX = 240;
            clipMinY = 0;
            clipMaxY = 240;
        }
        else
        {
            myFont.print(120 - streetLen / 2, 206, localStreet, TFT_WHITE, cardBgColor);
        }
    }
    else if (mapHudStyle == 3)
    {
        // ================= MẪU MH4: MINI HUD =================
        uint16_t cardBgColor = color565(15, 23, 42);

        // 1. Vẽ Icon điều hướng (cx=40, cy=105, r=20)
        int ax = 40, ay = 105;
        uint16_t accent = TFT_GREEN;
        canvasSprite.fillCircle(ax, ay, 20, cardBgColor);
        canvasSprite.drawCircle(ax, ay, 20, accent);
        if (hasCustomIcon)
        {
            drawCustomIconResized(canvasSprite, customIconBitmap, 26, 91, 28, 28, accent);
        }
        else
        {
            switch (navDirIdx)
            {
            case 4:
            case 5:
            case 6: // Turn Left
                canvasSprite.drawLine(ax + 6, ay + 7, ax + 6, ay - 2, accent);
                canvasSprite.drawLine(ax + 6, ay - 2, ax - 7, ay - 2, accent);
                canvasSprite.fillTriangle(ax - 7, ay - 2, ax - 1, ay - 6, ax - 1, ay + 2, accent);
                break;
            case 1:
            case 2:
            case 3: // Turn Right
                canvasSprite.drawLine(ax - 6, ay + 7, ax - 6, ay - 2, accent);
                canvasSprite.drawLine(ax - 6, ay - 2, ax + 7, ay - 2, accent);
                canvasSprite.fillTriangle(ax + 7, ay - 2, ax + 1, ay - 6, ax + 1, ay + 2, accent);
                break;
            default: // Straight / Arrow Up
                canvasSprite.drawLine(ax, ay + 7, ax, ay - 7, accent);
                canvasSprite.fillTriangle(ax, ay - 8, ax - 4, ay - 1, ax + 4, ay - 1, accent);
                break;
            }
        }

        // 2. Vẽ Tốc độ ở giữa (x=90, y=80)
        char spdBuf[16];
        snprintf(spdBuf, sizeof(spdBuf), "%d", gpsSpeed);
        myFont.set_font(FONT_CLOCK);
        myFont.print(90, 80, spdBuf, TFT_WHITE, TFT_BLACK);

        myFont.set_font(vietnamtimes12);
        myFont.print(165, 110, "KM/H", color565(148, 163, 184), TFT_BLACK);

        // 3. Vẽ Pin xe ở dưới (x=60, y=160, w=120, h=24)
        canvasSprite.fillRoundRect(60, 160, 120, 24, 12, cardBgColor);
        canvasSprite.drawRoundRect(60, 160, 120, 24, 12, TFT_CYAN);

        char batBuf[16];
        snprintf(batBuf, sizeof(batBuf), "PIN: %.1fV", batteryVoltage);
        myFont.set_font(vietnamtimes12);
        uint16_t batLen = myFont.getLength(batBuf);
        myFont.print(120 - batLen / 2, 166, batBuf, TFT_CYAN, cardBgColor);
    }
    else if (mapHudStyle == 5)
    {
        // ================= MẪU MH6: GALAXY WATCH (WEAROS MAP HUD) =================
        // 1. Cung / Thanh Thông Tin Thời Gian & ETA ở Đỉnh
        myFont.set_font(vietnamtimes12);
        String part1 = (localEte.length() > 0) ? localEte : ((localTotal.length() > 0) ? localTotal : "20 min");
        String part2 = "  ·  " + ((localEta.length() > 0) ? localEta : rtc.getTime("%H:%M"));
        uint16_t len1 = myFont.getLength(part1);
        uint16_t len2 = myFont.getLength(part2);
        uint16_t totalLen = len1 + len2;
        int pillW = totalLen + 20;
        if (pillW < 120)
            pillW = 120;
        int pillX = 120 - pillW / 2;
        int pillY = 12;

        uint16_t darkCushion = color565(8, 12, 22);
        canvasSprite.fillRoundRect(pillX, pillY, pillW, 22, 11, darkCushion);
        canvasSprite.drawRoundRect(pillX, pillY, pillW, 22, 11, color565(40, 52, 70));

        int textStartX = 120 - totalLen / 2;
        myFont.print(textStartX, pillY + 4, part1, TFT_CYAN, darkCushion);
        myFont.print(textStartX + len1, pillY + 4, part2, color565(226, 232, 240), darkCushion);

        // 2. Thẻ Nổi Chỉ Dẫn Điều Hướng Bo Tròn Ở Đáy (x=36, y=152, w=168, h=74, r=20)
        uint16_t cardBg = color565(9, 14, 25);
        canvasSprite.fillRoundRect(36, 152, 168, 74, 20, cardBg);
        canvasSprite.drawRoundRect(36, 152, 168, 74, 20, color565(51, 65, 85));

        // 2.1. Icon Điều Hướng & Khoảng Cách Rẽ (Luôn hiển thị rõ nét trên hàng 1 của thẻ)
        bool hasDist = (localDist.length() > 0);
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t distLen = hasDist ? myFont.getLength(localDist) : 0;
        int iconW = 16;
        int gap = 8;
        int rowW = hasDist ? (iconW + gap + distLen) : iconW;
        int startX = 120 - rowW / 2;
        int iconCenterX = startX + iconW / 2;
        int iconCenterY = 168;

        if (hasCustomIcon)
        {
            drawCustomIconResized(canvasSprite, customIconBitmap, iconCenterX - 10, iconCenterY - 10, 20, 20, TFT_WHITE);
        }
        else
        {
            uint16_t accent = TFT_WHITE;
            switch (navDirIdx)
            {
            case 4:
            case 5:
            case 6: // Rẽ Trái
                canvasSprite.drawLine(iconCenterX + 4, iconCenterY + 6, iconCenterX + 4, iconCenterY - 2, accent);
                canvasSprite.drawLine(iconCenterX + 4, iconCenterY - 2, iconCenterX - 5, iconCenterY - 2, accent);
                canvasSprite.fillTriangle(iconCenterX - 5, iconCenterY - 2, iconCenterX - 1, iconCenterY - 5, iconCenterX - 1, iconCenterY + 1, accent);
                break;
            case 1:
            case 2:
            case 3: // Rẽ Phải
                canvasSprite.drawLine(iconCenterX - 4, iconCenterY + 6, iconCenterX - 4, iconCenterY - 2, accent);
                canvasSprite.drawLine(iconCenterX - 4, iconCenterY - 2, iconCenterX + 5, iconCenterY - 2, accent);
                canvasSprite.fillTriangle(iconCenterX + 5, iconCenterY - 2, iconCenterX + 1, iconCenterY - 5, iconCenterX + 1, iconCenterY + 1, accent);
                break;
            default: // Đi thẳng hoặc vòng xuyến
                if (navDirIdx == 7)
                {
                    canvasSprite.drawCircle(iconCenterX - 2, iconCenterY + 1, 5, accent);
                    canvasSprite.drawLine(iconCenterX + 3, iconCenterY - 2, iconCenterX + 7, iconCenterY - 2, accent);
                    canvasSprite.drawLine(iconCenterX + 7, iconCenterY - 2, iconCenterX + 7, iconCenterY + 2, accent);
                }
                else
                {
                    // Đi thẳng
                    canvasSprite.drawLine(iconCenterX, iconCenterY + 7, iconCenterX, iconCenterY - 4, accent);
                    canvasSprite.drawLine(iconCenterX - 1, iconCenterY + 7, iconCenterX - 1, iconCenterY - 4, accent);
                    canvasSprite.fillTriangle(iconCenterX, iconCenterY - 6, iconCenterX - 4, iconCenterY - 1, iconCenterX + 4, iconCenterY - 1, accent);
                }
                break;
            }
        }

        // Vẽ Khoảng Cách Rẽ (ví dụ: "1,7 km" hoặc "150M") Font số đậm màu trắng sáng
        if (hasDist)
        {
            myFont.set_font(FONT_STATUS_INFO);
            myFont.print(startX + iconW + gap, 160, localDist, TFT_WHITE, cardBg);
        }

        // 2.2. Tên đường / Hướng di chuyển (vietnamtimes12 có marquee cuộn nếu quá dài)
        myFont.set_font(vietnamtimes12);
        uint16_t streetLen = myFont.getLength(localStreet);
        int visibleWidth = 146;
        int textY = 186;
        if (streetLen > visibleWidth)
        {
            clipMinX = 46;
            clipMaxX = 46 + visibleWidth;
            clipMinY = 152;
            clipMaxY = 226;

            int range = streetLen - visibleWidth + 30;
            int scrollMs = millis() % (range * 35 + 1200);
            int scrollX = 0;
            if (scrollMs > 1200)
            {
                scrollX = (scrollMs - 1200) / 35;
            }

            myFont.print(46 - scrollX, textY, localStreet, TFT_WHITE, cardBg);

            clipMinX = 0;
            clipMaxX = 240;
            clipMinY = 0;
            clipMaxY = 240;
        }
        else
        {
            myFont.print(120 - streetLen / 2, textY, localStreet, TFT_WHITE, cardBg);
        }

        // 2.3. Vạch Tiến Độ Lộ Trình Giao Thông (Đáy Thẻ Nổi, y = 215..216)
        // Đoạn 1: Xanh dương nhạt (Lộ trình thông thoáng)
        canvasSprite.fillRect(75, 215, 45, 2, color565(2, 132, 199));
        // Đoạn 2: Cam (Giao thông chậm vừa)
        canvasSprite.fillRect(122, 215, 22, 2, color565(249, 115, 22));
        // Đoạn 3: Đỏ (Ùn tắc nhẹ)
        canvasSprite.fillRect(146, 215, 18, 2, color565(239, 68, 68));
    }
    else
    {
        // ================= MẪU MH1: COMPACT FLOATING PILL (HIỂN THỊ 85% BẢN ĐỒ) =================
        // Ô hiển thị Tốc độ GPS (km/h) nổi phía trên Icon rẽ của Pill MH1
        char speedBuf[16];
        snprintf(speedBuf, sizeof(speedBuf), "%d km/h", gpsSpeed);
        myFont.set_font(FONT_HUD_INFO);
        uint16_t spdLen = myFont.getLength(speedBuf);
        uint16_t badgeW = spdLen + 14;
        if (badgeW < 56)
            badgeW = 56;
        int badgeX = 48 - badgeW / 2;
        if (badgeX < 18)
            badgeX = 18;
        int badgeY = 152;
        canvasSprite.fillRoundRect(badgeX, badgeY, badgeW, 22, 10, color565(30, 41, 59));
        canvasSprite.drawRoundRect(badgeX, badgeY, badgeW, 22, 10, TFT_GREEN);
        myFont.print(badgeX + (badgeW - spdLen) / 2, badgeY + 4, speedBuf, TFT_GREEN, color565(30, 41, 59));

        uint16_t cardBgColor = color565(15, 23, 42);
        canvasSprite.fillRoundRect(30, 180, 180, 44, 22, cardBgColor);
        canvasSprite.drawRoundRect(30, 180, 180, 44, 22, TFT_GREEN);

        // Icon Mũi Tên Rẽ Xanh Lá Neon (cx=48, cy=202, r=15)
        canvasSprite.fillCircle(48, 202, 15, TFT_GREEN);
        if (hasCustomIcon)
        {
            drawCustomIconResized(canvasSprite, customIconBitmap, 36, 190, 24, 24, TFT_BLACK);
        }
        else
        {
            int ax = 48, ay = 202;
            switch (navDirIdx)
            {
            case 4:
            case 5:
            case 6: // Turn Left
                canvasSprite.drawLine(ax + 6, ay + 7, ax + 6, ay - 2, TFT_BLACK);
                canvasSprite.drawLine(ax + 6, ay - 2, ax - 7, ay - 2, TFT_BLACK);
                canvasSprite.fillTriangle(ax - 7, ay - 2, ax - 1, ay - 6, ax - 1, ay + 2, TFT_BLACK);
                break;
            case 1:
            case 2:
            case 3: // Turn Right
                canvasSprite.drawLine(ax - 6, ay + 7, ax - 6, ay - 2, TFT_BLACK);
                canvasSprite.drawLine(ax - 6, ay - 2, ax + 7, ay - 2, TFT_BLACK);
                canvasSprite.fillTriangle(ax + 7, ay - 2, ax + 1, ay - 6, ax + 1, ay + 2, TFT_BLACK);
                break;
            default: // Straight / Arrow Up
                canvasSprite.drawLine(ax, ay + 7, ax, ay - 7, TFT_BLACK);
                canvasSprite.fillTriangle(ax, ay - 8, ax - 4, ay - 1, ax + 4, ay - 1, TFT_BLACK);
                break;
            }
        }

        // Khoảng cách rẽ Chữ Xanh Lá Neon ở Dòng Trên (y = 186)
        myFont.set_font(FONT_STATUS_INFO);
        myFont.print(70, 186, localDist, TFT_GREEN, cardBgColor);

        // Tên đường chỉ dẫn Chữ Trắng Tự Cuộn Marquee ở Dòng Dưới (y = 203)
        myFont.set_font(vietnamtimes12);
        uint16_t streetLen = myFont.getLength(localStreet);
        int visibleWidth = 125;
        if (streetLen > visibleWidth)
        {
            clipMinX = 70;
            clipMaxX = 70 + visibleWidth;
            clipMinY = 200;
            clipMaxY = 220;

            int range = streetLen - visibleWidth + 30;
            int scrollMs = millis() % (range * 35 + 1200);
            int scrollX = 0;
            if (scrollMs > 1200)
            {
                scrollX = (scrollMs - 1200) / 35;
            }

            myFont.print(70 - scrollX, 203, localStreet, TFT_WHITE, cardBgColor);

            clipMinX = 0;
            clipMaxX = 240;
            clipMinY = 0;
            clipMaxY = 240;
        }
        else
        {
            myFont.print(70, 203, localStreet, TFT_WHITE, cardBgColor);
        }
    }

    // Hiển thị icon thời tiết góc trên bên phải nếu có dữ liệu
    if (weatherIcon.length() > 0)
    {
        drawWeatherIcon(canvasSprite, weatherIcon, 50, 50);
    }

    // Vẽ Overlay Cảnh báo Giao thông (nếu có)
    drawTrafficWarningOverlay();
}

// ==========================================
// 1B. CÁC THÀNH PHẦN ĐÈ LÊN BẢN ĐỒ (MAP OVERLAY) - PHONG CÁCH GALAXY WATCH 7
// ==========================================
void drawMapOverlay()
{
    String localTotal = "";
    String localEta = "";
    String localEte = "";
    if (navMutex != NULL && xSemaphoreTake(navMutex, pdMS_TO_TICKS(10)) == pdTRUE)
    {
        localTotal = totalDist;
        localEta = eta;
        localEte = ete;
        xSemaphoreGive(navMutex);
    }
    else
    {
        localTotal = totalDist;
        localEta = eta;
        localEte = ete;
    }

    // 1. Vẽ dải nền tối mờ ở trên đỉnh màn hình để chữ dễ đọc
    canvasSprite.fillRoundRect(60, 10, 120, 24, 6, TFT_BLACK);
    canvasSprite.drawRoundRect(60, 10, 120, 24, 6, TFT_WHITE);

    // Vẽ ETE và ETA/Giờ ở đỉnh
    myFont.set_font(FONT_HUD_INFO);
    String part1 = (localEte.length() > 0) ? localEte : ((localTotal.length() > 0) ? localTotal : "20 min");
    String part2 = "  ·  " + ((localEta.length() > 0) ? localEta : rtc.getTime("%H:%M"));
    uint16_t len1 = myFont.getLength(part1);
    uint16_t len2 = myFont.getLength(part2);
    uint16_t totalLen = len1 + len2;
    int startX = 120 - totalLen / 2;

    myFont.print(startX, 15, part1, TFT_SKYBLUE, TFT_BLACK);
    myFont.print(startX + len1, 15, part2, TFT_WHITE, TFT_BLACK);

    // 3. Vẽ nút bản đồ dạng tròn ở dưới cùng (chuyển nhanh chế độ)
    canvasSprite.fillCircle(120, 212, 15, TFT_DARKGREY);
    canvasSprite.drawCircle(120, 212, 15, TFT_WHITE);
    // Vẽ icon bản đồ gấp khúc nhỏ bên trong
    int mcx = 120, mcy = 212;
    canvasSprite.drawRect(mcx - 6, mcy - 6, 4, 12, TFT_WHITE);
    canvasSprite.drawRect(mcx - 2, mcy - 4, 4, 12, TFT_WHITE);
    canvasSprite.drawRect(mcx + 2, mcy - 6, 4, 12, TFT_WHITE);

    // 4. Hiển thị icon thời tiết góc trên bên phải nếu có dữ liệu
    if (weatherIcon.length() > 0)
    {
        drawWeatherIcon(canvasSprite, weatherIcon, 204, 26);
    }

    // 5. Vẽ Overlay Cảnh báo Giao thông (nếu có)
    drawTrafficWarningOverlay();

    canvasSprite.pushSprite(0, 0);
}

// ==========================================
// HÀM VẼ BIỂN BÁO GIỚI HẠN TỐC ĐỘ MINI (P.127 TRÒN VIỀN ĐỎ NỀN TRẮNG SỐ ĐEN)
// ==========================================
void drawSpeedLimitSignCompact(TFT_eSprite &sprite, int cx, int cy, int r, int speedVal, bool isBlinkInvert)
{
    int drawSpeed = (speedVal > 0) ? speedVal : 60;

    if (isBlinkInvert)
    {
        sprite.fillCircle(cx, cy, r, TFT_YELLOW);
        sprite.drawCircle(cx, cy, r, TFT_RED);
        sprite.drawCircle(cx, cy, r - 1, TFT_RED);
        sprite.setTextColor(TFT_RED, TFT_YELLOW);
    }
    else
    {
        sprite.fillCircle(cx, cy, r, TFT_WHITE);
        sprite.drawCircle(cx, cy, r, TFT_RED);
        sprite.drawCircle(cx, cy, r - 1, TFT_RED);
        sprite.setTextColor(TFT_BLACK, TFT_WHITE);
    }

    sprite.setTextDatum(MC_DATUM);
    if (drawSpeed >= 100)
    {
        sprite.drawNumber(drawSpeed, cx, cy, 1);
    }
    else
    {
        sprite.drawNumber(drawSpeed, cx, cy, 2);
    }
}

// ==========================================
// HÀM VẼ BIỂN BÁO CAMERA PHẠT NGUỘI MINI
// ==========================================
void drawCameraSignCompact(TFT_eSprite &sprite, int cx, int cy, int r, bool isBlinkInvert)
{
    if (isBlinkInvert)
    {
        sprite.fillCircle(cx, cy, r, TFT_RED);
        sprite.drawCircle(cx, cy, r, TFT_WHITE);
        sprite.setTextColor(TFT_WHITE, TFT_RED);
    }
    else
    {
        sprite.fillCircle(cx, cy, r, TFT_WHITE);
        sprite.drawCircle(cx, cy, r, TFT_BLUE);
        sprite.drawCircle(cx, cy, r - 1, TFT_BLUE);
        sprite.setTextColor(TFT_BLUE, TFT_WHITE);
    }

    sprite.setTextDatum(MC_DATUM);
    sprite.drawString("CAM", cx, cy, 1);
}

// ==========================================
// CẢNH BÁO GIAO THÔNG (SPEED LIMIT & CAMERA PHẠT NGUỘI) - VIỀN NHÁP NHÁY 360° & BIỂN MINI VÙNG AN TOÀN
// ==========================================
void drawTrafficWarningOverlay()
{
    if (!isTrafficWarningActive)
        return;

    // Tự động hết hạn cảnh báo sau 6 giây nếu không có gói tin mới
    if (millis() - trafficWarningStartTime > 6000)
    {
        isTrafficWarningActive = false;
        return;
    }

    bool isBlink = ((millis() - trafficWarningStartTime) / 250) % 2 == 0;
    bool isOverSpeed = (trafficWarningValue > 0 && gpsSpeed > trafficWarningValue);

    // 1. Hiệu ứng viền tròn ngoài cùng màn hình nhấp nháy đỏ 360 độ khi có cảnh báo
    if (isBlink && (isOverSpeed || trafficWarningType == 0x01 || trafficWarningType == 0x02 || trafficWarningType == 3))
    {
        canvasSprite.drawCircle(120, 120, 119, TFT_RED);
        canvasSprite.drawCircle(120, 120, 118, TFT_RED);
        canvasSprite.drawCircle(120, 120, 117, TFT_RED);
        canvasSprite.drawCircle(120, 120, 116, TFT_RED);
    }

    // 2. Vẽ biển báo mini theo từng màn hình (nếu không phải HUD_MODE vì HUD_MODE đã tích hợp ở đáy)
    if (currentMode == HUD_MODE)
    {
        return;
    }
    else if (currentMode == STATUS_MODE)
    {
        if (statusStyle == 1)
        {
            if (trafficWarningType == 0x02 || trafficWarningType == 3)
                drawSpeedLimitSignCompact(canvasSprite, 52, 120, 13, trafficWarningValue, isBlink && isOverSpeed);
            else
                drawCameraSignCompact(canvasSprite, 52, 120, 13, isBlink);
        }
        else if (statusStyle == 2)
        {
            if (trafficWarningType == 0x02 || trafficWarningType == 3)
                drawSpeedLimitSignCompact(canvasSprite, 195, 103, 13, trafficWarningValue, isBlink && isOverSpeed);
            else
                drawCameraSignCompact(canvasSprite, 195, 103, 13, isBlink);
        }
        else
        {
            if (trafficWarningType == 0x02 || trafficWarningType == 3)
                drawSpeedLimitSignCompact(canvasSprite, 195, 42, 13, trafficWarningValue, isBlink && isOverSpeed);
            else
                drawCameraSignCompact(canvasSprite, 195, 42, 13, isBlink);
        }
    }
    else if (currentMode == MAP_HUD_MODE || currentMode == MAP_MODE)
    {
        if (trafficWarningType == 0x02 || trafficWarningType == 3)
            drawSpeedLimitSignCompact(canvasSprite, 195, 42, 13, trafficWarningValue, isBlink && isOverSpeed);
        else
            drawCameraSignCompact(canvasSprite, 195, 42, 13, isBlink);
    }
    else
    {
        if (trafficWarningType == 0x02 || trafficWarningType == 3)
            drawSpeedLimitSignCompact(canvasSprite, 195, 42, 13, trafficWarningValue, isBlink && isOverSpeed);
        else
            drawCameraSignCompact(canvasSprite, 195, 42, 13, isBlink);
    }
}

// Vẽ icon thời tiết dạng pixel tại vị trí (cx, cy) kích thước ~20px
void drawWeatherIcon(TFT_eSprite &sprite, const String &icon, int cx, int cy)
{
    String ic = icon.length() >= 2 ? icon.substring(0, 2) : "";

    if (ic == "01") // Nắng ☀
    {
        sprite.fillCircle(cx, cy, 7, TFT_YELLOW);
        for (int a = 0; a < 360; a += 45)
        {
            float ra = a * DEG_TO_RAD;
            sprite.drawLine(cx + 9 * cos(ra), cy + 9 * sin(ra), cx + 12 * cos(ra), cy + 12 * sin(ra), TFT_YELLOW);
        }
    }
    else if (ic == "02" || ic == "03") // Ít mây / Mây rải
    {
        sprite.fillCircle(cx, cy, 7, TFT_YELLOW); // mặt trời nhỏ
        sprite.fillCircle(cx + 6, cy + 5, 8, TFT_LIGHTGREY);
        sprite.fillCircle(cx + 2, cy + 6, 7, TFT_WHITE);
        sprite.fillCircle(cx - 3, cy + 6, 6, TFT_WHITE);
    }
    else if (ic == "04") // Nhiều mây
    {
        sprite.fillCircle(cx + 4, cy + 3, 9, TFT_LIGHTGREY);
        sprite.fillCircle(cx - 2, cy + 5, 8, TFT_DARKGREY);
        sprite.fillCircle(cx - 6, cy + 5, 6, TFT_LIGHTGREY);
    }
    else if (ic == "09" || ic == "10") // Mưa
    {
        sprite.fillCircle(cx + 2, cy, 9, TFT_LIGHTGREY);
        sprite.fillCircle(cx - 3, cy + 2, 7, TFT_DARKGREY);
        // Hạt mưa
        for (int i = -2; i <= 4; i += 3)
        {
            sprite.drawLine(cx + i, cy + 12, cx + i - 2, cy + 17, TFT_CYAN);
        }
    }
    else if (ic == "11") // Sấm sét
    {
        sprite.fillCircle(cx, cy, 9, TFT_DARKGREY);
        // Tia sét
        sprite.fillTriangle(cx + 2, cy + 9, cx - 4, cy + 18, cx + 1, cy + 18, TFT_YELLOW);
        sprite.fillTriangle(cx + 1, cy + 18, cx + 5, cy + 18, cx - 1, cy + 27, TFT_YELLOW);
    }
    else if (ic == "13") // Tuyết
    {
        for (int a = 0; a < 360; a += 60)
        {
            float ra = a * DEG_TO_RAD;
            sprite.drawLine(cx, cy, cx + 9 * cos(ra), cy + 9 * sin(ra), TFT_WHITE);
        }
        sprite.fillCircle(cx, cy, 3, TFT_WHITE);
    }
    else if (ic == "50") // Sương mù
    {
        for (int i = 0; i < 4; i++)
        {
            sprite.drawLine(cx - 10, cy - 6 + i * 4, cx + 10, cy - 6 + i * 4, TFT_LIGHTGREY);
        }
    }
    else // Mặc định: dấu chấm hỏi
    {
        sprite.drawCircle(cx, cy, 9, TFT_DARKGREY);
        sprite.fillCircle(cx, cy + 5, 2, TFT_DARKGREY);
    }
}

// Vẽ icon pin điện thoại tại (x, y) width=22 height=12
void drawPhoneBatteryIcon(TFT_eSprite &sprite, int x, int y, int level, bool charging)
{
    // Thân pin
    sprite.drawRect(x, y, 20, 11, TFT_WHITE);
    // Đầu pin
    sprite.fillRect(x + 20, y + 3, 2, 5, TFT_WHITE);

    // Màu fill tùy mức pin
    uint16_t fillColor = TFT_GREEN;
    if (level < 20)
        fillColor = TFT_RED;
    else if (level < 40)
        fillColor = TFT_ORANGE;
    else if (level < 60)
        fillColor = TFT_YELLOW;

    // Fill thanh pin bên trong
    int fillW = (int)((level / 100.0f) * 18);
    if (fillW > 0)
        sprite.fillRect(x + 1, y + 1, fillW, 9, fillColor);

    // Biểu tượng sạc
    if (charging)
    {
        sprite.fillTriangle(x + 12, y + 1, x + 8, y + 6, x + 11, y + 6, TFT_WHITE);
        sprite.fillTriangle(x + 8, y + 6, x + 12, y + 10, x + 12, y + 5, TFT_WHITE);
    }
}

// ==========================================
// 2. GIAO DIỆN TRẠNG THÁI (STATUS) - 3 MẪU S4 (MẶC ĐỊNH), S5, S3
// ==========================================
void drawSTATUS()
{
    canvasSprite.fillSprite(TFT_BLACK);

    if (statusStyle == 1)
    {
        // ---------------- S5: CLASSIC ANALOG WATCH ----------------
        // 1. 12 Nấc vạch giờ quanh viền
        for (int i = 0; i < 12; i++)
        {
            float rad = (i * 30.0f - 90.0f) * 0.0174532925f;
            int x1 = 120 + (int)(96.0f * cos(rad));
            int y1 = 120 + (int)(96.0f * sin(rad));
            int x2 = 120 + (int)(108.0f * cos(rad));
            int y2 = 120 + (int)(108.0f * sin(rad));
            uint16_t tCol = (i % 3 == 0) ? TFT_GREEN : color565(71, 85, 105);
            canvasSprite.drawLine(x1, y1, x2, y2, tCol);
            if (i % 3 == 0)
                canvasSprite.drawLine(x1 + 1, y1, x2 + 1, y2, tCol);
        }

        // 2. Ô Ngày tháng Tiếng Việt ở góc trên (y = 55)
        const char *dayOfWeekVi[] = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};
        int dow = rtc.getDayofWeek();
        if (dow < 0 || dow > 6)
            dow = 0;
        String dateStr = String(dayOfWeekVi[dow]) + ", " + rtc.getTime("%d/%m");
        myFont.set_font(vietnamtimes12);
        uint16_t dateLen = myFont.getLength(dateStr);
        canvasSprite.fillRoundRect(120 - dateLen / 2 - 8, 52, dateLen + 16, 24, 6, color565(15, 23, 42));
        canvasSprite.drawRoundRect(120 - dateLen / 2 - 8, 52, dateLen + 16, 24, 6, color565(51, 65, 85));
        myFont.print(120 - dateLen / 2, 57, dateStr, TFT_WHITE, color565(15, 23, 42));

        // 3. Kim đồng hồ kim Vector (Tâm 120, 120)
        int hours = rtc.getHour(true);
        int minutes = rtc.getMinute();
        float hRad = ((hours % 12) * 30.0f + minutes * 0.5f - 90.0f) * 0.0174532925f;
        float mRad = (minutes * 6.0f - 90.0f) * 0.0174532925f;

        int hx = 120 + (int)(42.0f * cos(hRad));
        int hy = 120 + (int)(42.0f * sin(hRad));
        int mx = 120 + (int)(68.0f * cos(mRad));
        int my = 120 + (int)(68.0f * sin(mRad));

        canvasSprite.drawLine(120, 120, hx, hy, TFT_WHITE);
        canvasSprite.drawLine(121, 120, hx + 1, hy, TFT_WHITE);
        canvasSprite.drawLine(120, 120, mx, my, TFT_GREEN);
        canvasSprite.fillCircle(120, 120, 4, TFT_GREEN);

        // 4. Badge Pin ở đáy (y = 165)
        char batBuf[32];
        snprintf(batBuf, sizeof(batBuf), "%.1fV  ·  %d%%", batteryVoltage, phoneBatteryLevel >= 0 ? phoneBatteryLevel : 100);
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t batLen = myFont.getLength(batBuf);
        myFont.print(120 - batLen / 2, 165, batBuf, TFT_GREEN, TFT_BLACK);
    }
    else if (statusStyle == 2)
    {
        // ---------------- S3: DUAL ENERGY PILL ----------------
        // 1. Giờ số ở tâm (y = 20)
        String timeStr = rtc.getTime("%H:%M:%S");
        myFont.set_font(FONT_CLOCK);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 20, timeStr, TFT_WHITE, TFT_BLACK);

        if (!timeSynced)
        {
            myFont.set_font(FONT_HUD_INFO);
            String excl = "!";
            myFont.print(120 + timeLen / 2 + 2, 20, excl, TFT_ORANGE, TFT_BLACK);
        }

        // 2. Thứ & Ngày tháng Tiếng Việt (y = 65)
        const char *dayOfWeekVi[] = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};
        int dow = rtc.getDayofWeek();
        if (dow < 0 || dow > 6)
            dow = 0;
        String dateStr = String(dayOfWeekVi[dow]) + ", " + rtc.getTime("%d/%m/%Y");
        myFont.set_font(vietnamtimes12);
        uint16_t dateLen = myFont.getLength(dateStr);
        myFont.print(120 - dateLen / 2, 65, dateStr, TFT_WHITE, TFT_BLACK);

        // 3. Thời tiết (y = 98)
        if (weatherIcon.length() > 0)
        {
            drawWeatherIcon(canvasSprite, weatherIcon, 45, 98);
            char wxBuf[32];
            snprintf(wxBuf, sizeof(wxBuf), "%.0f°C  %s", weatherTemp, weatherIcon.c_str());
            myFont.set_font(vietnamtimes12);
            myFont.print(68, 93, wxBuf, TFT_YELLOW, TFT_BLACK);
        }
        else
        {
            char tempBuf[32];
            snprintf(tempBuf, sizeof(tempBuf), "Thời tiết: 31°C Nắng");
            myFont.set_font(vietnamtimes12);
            uint16_t tempLen = myFont.getLength(tempBuf);
            myFont.print(120 - tempLen / 2, 95, tempBuf, TFT_YELLOW, TFT_BLACK);
        }

        // 4. Pill 1: Ắc quy xe (Trái)
        char batBuf[16];
        snprintf(batBuf, sizeof(batBuf), "Ắc quy: %.1fV", batteryVoltage);
        canvasSprite.fillRoundRect(18, 140, 98, 34, 10, color565(15, 23, 42));
        canvasSprite.drawRoundRect(18, 140, 98, 34, 10, TFT_GREEN);
        myFont.set_font(vietnamtimes12);
        myFont.print(24, 149, batBuf, TFT_GREEN, color565(15, 23, 42));

        // 5. Pill 2: Pin Phone (Phải)
        char phoneBuf[16];
        snprintf(phoneBuf, sizeof(phoneBuf), "Phone: %d%%", phoneBatteryLevel >= 0 ? phoneBatteryLevel : 100);
        canvasSprite.fillRoundRect(124, 140, 98, 34, 10, color565(15, 23, 42));
        canvasSprite.drawRoundRect(124, 140, 98, 34, 10, TFT_CYAN);
        myFont.print(130, 149, phoneBuf, TFT_CYAN, color565(15, 23, 42));
    }
    else if (statusStyle == 3)
    {
        // ==================== MẪU 4: MINIMALIST LUXURY HORIZON (CYBER PINK SPEED) ====================
        // 1. Màu sắc tốc độ GPS (Chữ số ở giữa dùng Cyber Pink theo yêu cầu, sóng đổi màu Neon mượt mà)
        uint16_t pinkColor = color565(255, 0, 127); // Cyber Pink (#FF007F)

        // 2. Header: Đồng hồ thời gian lớn (y = 12, Font lớn rõ nét) + Ngày & Thời tiết (y = 36)
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(FONT_HUD_DIST);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 12, timeStr, TFT_WHITE, TFT_BLACK);

        char wxBuf[16];
        if (weatherTemp > -50.0f && weatherTemp < 60.0f)
            snprintf(wxBuf, sizeof(wxBuf), "%.0f°C", weatherTemp);
        else
            snprintf(wxBuf, sizeof(wxBuf), "28°C");

        String dateStr = rtc.getTime("%d/%m");
        char subHeaderBuf[32];
        snprintf(subHeaderBuf, sizeof(subHeaderBuf), "%s | %s", dateStr.c_str(), wxBuf);
        myFont.set_font(vietnamtimes12);
        uint16_t shLen = myFont.getLength(subHeaderBuf);
        myFont.print(120 - shLen / 2, 36, subHeaderBuf, color565(148, 163, 184), TFT_BLACK);

        // 3. Chữ số tốc độ GPS nổi lơ lửng ở giữa (Màu Cyber Pink theo yêu cầu) (y = 50..84)
        char spdBuf[16];
        snprintf(spdBuf, sizeof(spdBuf), "%d", gpsSpeed);
        myFont.set_font(FONT_CLOCK);
        uint16_t spdLen = myFont.getLength(spdBuf);

        myFont.set_font(vietnamtimes12);
        uint16_t unitLen = myFont.getLength("km/h");

        int totalW = spdLen + 4 + unitLen;
        int startX = 120 - totalW / 2;

        myFont.set_font(FONT_CLOCK);
        myFont.print(startX, 50, spdBuf, pinkColor, TFT_BLACK);

        myFont.set_font(vietnamtimes12);
        myFont.print(startX + spdLen + 4, 66, "km/h", pinkColor, TFT_BLACK);

        // 4. Đường sóng tốc độ tràn viền an toàn tuyệt đối, không đè chữ, không lẹm viền tròn (x = 24, y = 96, w = 192, h = 82)
        int ox = 24, oy = 96, ow = 192, oh = 82;

        // Thuật toán Dynamic Auto-Zoom độ nhạy cao cho biến thiên tốc độ
        float localMin = 999.0f, localMax = -999.0f;
        for (int i = 0; i < SPEED_HISTORY_SIZE; i++)
        {
            float val = speedHistory[i];
            if (val < localMin) localMin = val;
            if (val > localMax) localMax = val;
        }
        if (localMin > 500.0f) localMin = (float)gpsSpeed;
        if (localMax < -500.0f) localMax = (float)gpsSpeed + 10.0f;

        // Giữ sàn tối thiểu 40 km/h để khi dừng xe thì sóng nằm êm ả ở đáy, khi tăng tốc sóng cuộn lên sống động
        if (localMin > 0.0f) localMin = 0.0f;
        if (localMax < 40.0f) localMax = 40.0f;

        static float smoothMinM4 = 0.0f, smoothMaxM4 = 40.0f;
        smoothMinM4 += 0.25f * (localMin - smoothMinM4);
        smoothMaxM4 += 0.25f * (localMax - smoothMaxM4);
        float currentSpanM4 = smoothMaxM4 - smoothMinM4;
        if (currentSpanM4 < 10.0f)
        {
            smoothMaxM4 = smoothMinM4 + 10.0f;
            currentSpanM4 = 10.0f;
        }

        // Vẽ đường sóng tràn viền đổi màu Neon Gradient mượt mà theo từng điểm (Smooth Neon Wave)
        int plotW = ow - 4; // 188 điểm nằm an toàn trong màn hình tròn
        int prevPx = -1, prevPy = -1;
        for (int i = 0; i < plotW; i++)
        {
            int histOffset = (i * (SPEED_HISTORY_SIZE - 1)) / (plotW - 1);
            int bufIdx = (speedHistoryIdx + histOffset) % SPEED_HISTORY_SIZE;
            float val = speedHistory[bufIdx];
            uint16_t segColor = getSpeedNeonColor(val);

            int py = oy + oh - 4 - (int)(((val - smoothMinM4) / currentSpanM4) * (oh - 8));
            if (py < oy + 2) py = oy + 2;
            if (py > oy + oh - 2) py = oy + oh - 2;
            int px = ox + 2 + i;

            if (i == 0)
            {
                prevPx = px;
                prevPy = py;
            }
            else
            {
                canvasSprite.drawLine(prevPx, prevPy, px, py, segColor);
                canvasSprite.drawLine(prevPx, prevPy + 1, px, py + 1, segColor);
                prevPx = px;
                prevPy = py;
            }
        }

        // 5. Footer tối giản sang trọng phân tầng cân đối (y = 198 - An toàn 100% trong đường tròn)
        static int sessionSpeedMax = 0;
        if (gpsSpeed > sessionSpeedMax) sessionSpeedMax = gpsSpeed;

        char footerBuf[32];
        snprintf(footerBuf, sizeof(footerBuf), "MAX: %d km/h   PIN: %.1fV", sessionSpeedMax, batteryVoltage);
        myFont.set_font(vietnamtimes12);
        uint16_t mmLen = myFont.getLength(footerBuf);
        myFont.print(120 - mmLen / 2, 198, footerBuf, color565(148, 163, 184), TFT_BLACK);
    }
    else if (statusStyle == 4)
    {
        // ---------------- S7: SPORT ACTIVITY ----------------
        // 1. Time at top (y=16) in Cyan
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(FONT_HUD_DIST);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 16, timeStr, TFT_CYAN, TFT_BLACK);

        // 2. Speed at center (y=70)
        char spdBuf[16];
        snprintf(spdBuf, sizeof(spdBuf), "%d", gpsSpeed);
        myFont.set_font(FONT_CLOCK);
        uint16_t spdLen = myFont.getLength(spdBuf);
        myFont.print(120 - spdLen / 2, 70, spdBuf, TFT_WHITE, TFT_BLACK);
        myFont.set_font(vietnamtimes12);
        myFont.print(120 + spdLen / 2 + 5, 100, "KM/H", color565(148, 163, 184), TFT_BLACK);

        // 3. Battery Bar at bottom (y=155)
        canvasSprite.fillRoundRect(30, 155, 180, 8, 4, color565(30, 41, 59));
        int vSpan = (int)((batteryVoltage / 15.0f) * 180.0f);
        if (vSpan > 180)
            vSpan = 180;
        uint16_t vColor = (batteryVoltage > 12.0f) ? TFT_GREEN : ((batteryVoltage > 11.0f) ? TFT_YELLOW : TFT_RED);
        canvasSprite.fillRoundRect(30, 155, vSpan, 8, 4, vColor);

        // 4. Battery Text (y=180)
        char batBuf[32];
        snprintf(batBuf, sizeof(batBuf), "PIN XE: %.1fV", batteryVoltage);
        uint16_t batLen = myFont.getLength(batBuf);
        myFont.print(120 - batLen / 2, 180, batBuf, TFT_WHITE, TFT_BLACK);
    }
    else if (statusStyle == 5)
    {
        // ==================== MẪU 1: SPORT CHRONO & REALTIME VOLTAGE RADAR (TỐI ƯU CÂN ĐỐI) ====================
        // 1. Vành cung Analog đo điện áp ngoài cùng (R = 112, 135° -> 405°)
        float vNorm = (batteryVoltage - 10.0f) / 6.0f;
        if (vNorm < 0.0f) vNorm = 0.0f;
        if (vNorm > 1.0f) vNorm = 1.0f;

        drawArcSegment(canvasSprite, 120, 120, 112, 135, 405, color565(20, 30, 45)); // Nền vành
        
        uint16_t voltColor = getVoltNeonColor(batteryVoltage); // Màu Neon mượt mà theo điện áp
        int curAngle = 135 + (int)(vNorm * 270.0f);
        drawArcSegment(canvasSprite, 120, 120, 112, 135, curAngle, voltColor);

        // 2. 12 Cọc số thể thao quanh viền (Bỏ cọc 12h và 6h để không đè vào chữ trên/dưới)
        for (int i = 0; i < 12; i++)
        {
            if (i == 0 || i == 6) continue; // Tránh đè giờ ở đỉnh và footer ở đáy
            float rad = (i * 30.0f - 90.0f) * 0.0174532925f;
            bool isMajor = (i % 3 == 0);
            int rInner = isMajor ? 100 : 104;
            int x1 = 120 + (int)(rInner * cos(rad));
            int y1 = 120 + (int)(rInner * sin(rad));
            int x2 = 120 + (int)(110.0f * cos(rad));
            int y2 = 120 + (int)(110.0f * sin(rad));
            uint16_t tCol = isMajor ? TFT_CYAN : color565(51, 65, 85);
            canvasSprite.drawLine(x1, y1, x2, y2, tCol);
        }

        // 3. Top Header: Đồng hồ thời gian lớn (y = 12) + Ngày | Thời tiết | Pin ĐT (y = 36)
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(FONT_HUD_DIST);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 12, timeStr, TFT_WHITE, TFT_BLACK);

        char wxBuf[16];
        if (weatherTemp > -50.0f && weatherTemp < 60.0f)
            snprintf(wxBuf, sizeof(wxBuf), "%.0f°C", weatherTemp);
        else
            snprintf(wxBuf, sizeof(wxBuf), "28°C");

        String dateStr = rtc.getTime("%d/%m");
        char pBuf[16];
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        snprintf(pBuf, sizeof(pBuf), "%d%%", pBat);

        char subHdrBuf[48];
        snprintf(subHdrBuf, sizeof(subHdrBuf), "%s | %s | %s", dateStr.c_str(), wxBuf, pBuf);
        myFont.set_font(vietnamtimes12);
        uint16_t shLen = myFont.getLength(subHdrBuf);
        myFont.print(120 - shLen / 2, 36, subHdrBuf, color565(148, 163, 184), TFT_BLACK);

        // 4. Cụm điện áp trung tâm (y = 52..88)
        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "%.2f", batteryVoltage);
        myFont.set_font(FONT_CLOCK);
        uint16_t vLen = myFont.getLength(vBuf);
        myFont.print(112 - vLen / 2, 52, vBuf, TFT_WHITE, TFT_BLACK);

        myFont.set_font(FONT_STATUS_INFO);
        myFont.print(118 + vLen / 2, 62, "V", voltColor, TFT_BLACK);

        // 5. Cửa sổ sóng Oscilloscope Radar mở rộng (x=26, y=96, w=188, h=80)
        int ox = 26, oy = 96, ow = 188, oh = 80;
        canvasSprite.fillRoundRect(ox, oy, ow, oh, 6, color565(8, 16, 28));
        canvasSprite.drawRoundRect(ox, oy, ow, oh, 6, color565(30, 45, 68));

        // THUẬT TOÁN AUTO-ZOOM CHỐNG RUNG GIẬT & ĐỘ NHẠY CAO CHO SÓNG DAO ĐỘNG:
        float localMin = 99.0f, localMax = -99.0f;
        for (int i = 0; i < VOLT_HISTORY_SIZE; i++)
        {
            float val = voltHistory[i];
            if (val < localMin) localMin = val;
            if (val > localMax) localMax = val;
        }
        if (localMin > 50.0f) localMin = batteryVoltage - 0.25f;
        if (localMax < -50.0f) localMax = batteryVoltage + 0.25f;

        float span = localMax - localMin;
        const float MIN_DSO_SPAN = 0.50f; // Sàn quan sát 500mV zoom to sóng rõ nét
        if (span < MIN_DSO_SPAN)
        {
            float mid = (localMax + localMin) * 0.5f;
            localMin = mid - (MIN_DSO_SPAN * 0.5f);
            localMax = mid + (MIN_DSO_SPAN * 0.5f);
        }
        else
        {
            float margin = span * 0.08f;
            localMin -= margin;
            localMax += margin;
        }

        // Bắt tức thì khi sụt áp đề xe hoặc tăng áp nổ máy, mượt khi tĩnh
        static float smoothMinS3 = 11.0f, smoothMaxS3 = 13.5f;
        static bool isScaleInitS3 = false;
        if (!isScaleInitS3)
        {
            smoothMinS3 = localMin;
            smoothMaxS3 = localMax;
            isScaleInitS3 = true;
        }

        if (localMin < smoothMinS3)
            smoothMinS3 = localMin; // Bắt ngay đáy sụt áp đề máy không trễ
        else if (fabsf(localMin - smoothMinS3) > 0.02f)
            smoothMinS3 += 0.12f * (localMin - smoothMinS3);

        if (localMax > smoothMaxS3)
            smoothMaxS3 = localMax; // Bắt ngay đỉnh sạc ga nổ máy
        else if (fabsf(localMax - smoothMaxS3) > 0.02f)
            smoothMaxS3 += 0.12f * (localMax - smoothMaxS3);

        float currentSpanS3 = smoothMaxS3 - smoothMinS3;
        if (currentSpanS3 < MIN_DSO_SPAN)
        {
            float midS3 = (smoothMaxS3 + smoothMinS3) * 0.5f;
            smoothMinS3 = midS3 - (MIN_DSO_SPAN * 0.5f);
            smoothMaxS3 = midS3 + (MIN_DSO_SPAN * 0.5f);
            currentSpanS3 = MIN_DSO_SPAN;
        }

        // Tâm định vị toạ độ Graticule tinh tế (Subtle Center Crosshair)
        int cx = ox + ow / 2;
        int cy = oy + oh / 2;
        canvasSprite.drawPixel(cx, cy, color565(80, 110, 150));
        canvasSprite.drawPixel(cx - 1, cy, color565(80, 110, 150));
        canvasSprite.drawPixel(cx + 1, cy, color565(80, 110, 150));
        canvasSprite.drawPixel(cx, cy - 1, color565(80, 110, 150));
        canvasSprite.drawPixel(cx, cy + 1, color565(80, 110, 150));

        // Vẽ đường sóng 1:1 liên tục, không bị đứt đoạn hay nhấp nháy bỏ mẫu
        int plotW = ow - 4; // 184 điểm
        int prevPx = -1, prevPy = -1;
        int offsetStart = (VOLT_HISTORY_SIZE >= plotW) ? (VOLT_HISTORY_SIZE - plotW) : 0;
        for (int i = 0; i < plotW; i++)
        {
            int bufIdx = (voltHistoryIdx + offsetStart + i) % VOLT_HISTORY_SIZE;
            float val = voltHistory[bufIdx];
            uint16_t segColor = getVoltNeonColor(val);

            int py = oy + oh - 3 - (int)(((val - smoothMinS3) / currentSpanS3) * (oh - 6));
            if (py < oy + 2) py = oy + 2;
            if (py > oy + oh - 2) py = oy + oh - 2;
            int px = ox + 2 + i;

            if (i == 0)
            {
                prevPx = px;
                prevPy = py;
            }
            else
            {
                canvasSprite.drawLine(prevPx, prevPy, px, py, segColor);
                canvasSprite.drawLine(prevPx, prevPy + 1, px, py + 1, segColor);
                prevPx = px;
                prevPy = py;
            }
        }

        // 6. Footer Min / Max Tracker (y = 198 - Căn chỉnh an toàn bên trong đường tròn)
        char minMaxBuf[32];
        snprintf(minMaxBuf, sizeof(minMaxBuf), "MIN:%.1fV   MAX:%.1fV", (voltMin > 50.0f) ? batteryVoltage : voltMin, (voltMax < 5.0f) ? batteryVoltage : voltMax);
        myFont.set_font(vietnamtimes12);
        uint16_t mmLen = myFont.getLength(minMaxBuf);
        myFont.print(120 - mmLen / 2, 198, minMaxBuf, color565(148, 163, 184), TFT_BLACK);
    }
    else if (statusStyle == 6)
    {
        // ==================== MẪU 4A CHÍNH THỨC: CYBER SUPERBIKE 3D PRO (SMOOTH NEON VOLTAGE COLOR) ====================
        // 1. Dynamic Voltage Color (Chuyển tiếp mượt mà dải màu Neon RGB)
        uint16_t voltColor = getVoltNeonColor(batteryVoltage);

        // 2. Viền Bezel ngoài cùng phát sáng
        canvasSprite.drawCircle(120, 120, 118, voltColor);
        canvasSprite.drawCircle(120, 120, 117, color565(15, 23, 42));

        // 3. Header: Đồng hồ thời gian lớn (y = 12) + Ngày | Thời tiết | Pin ĐT (y = 36)
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(FONT_HUD_DIST);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 12, timeStr, TFT_WHITE, TFT_BLACK);

        char wxBuf[16];
        if (weatherTemp > -50.0f && weatherTemp < 60.0f)
            snprintf(wxBuf, sizeof(wxBuf), "%.0f°C", weatherTemp);
        else
            snprintf(wxBuf, sizeof(wxBuf), "28°C");

        String dateStr = rtc.getTime("%d/%m");
        char pBuf[16];
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        snprintf(pBuf, sizeof(pBuf), "%d%%", pBat);

        char subHdrBuf[48];
        snprintf(subHdrBuf, sizeof(subHdrBuf), "%s | %s | %s", dateStr.c_str(), wxBuf, pBuf);
        myFont.set_font(vietnamtimes12);
        uint16_t shLen = myFont.getLength(subHdrBuf);
        myFont.print(120 - shLen / 2, 36, subHdrBuf, color565(148, 163, 184), TFT_BLACK);

        // BLE status dot
        canvasSprite.fillCircle(120 + shLen / 2 + 6, 40, 2, bleConnected ? TFT_CYAN : color565(71, 85, 105));

        // 4. Đường lưới 3D Perspective Road Grid chuyển động theo tốc độ GPS
        const int horizonY = 104;
        static float gridOffsetS3 = 0.0f;
        float speedFactor = (gpsSpeed / 60.0f) * 3.5f;
        if (speedFactor < 0.2f && gpsSpeed > 0) speedFactor = 0.2f;
        gridOffsetS3 = fmodf(gridOffsetS3 + speedFactor, 22.0f);

        // Các tia lưới không gian tỏa ra từ tâm (120, horizonY)
        for (int x = 10; x <= 230; x += 24)
        {
            canvasSprite.drawLine(120, horizonY, x, 240, color565(15, 30, 50));
        }
        // Các vạch ngang chuyển động theo tốc độ
        for (int y = horizonY + 8; y < 240; y += 18)
        {
            int actualY = y + (int)(gridOffsetS3 * ((float)(y - horizonY) / 135.0f));
            if (actualY <= 238)
            {
                canvasSprite.drawLine(20, actualY, 220, actualY, color565(20, 40, 65));
            }
        }

        // 5. Thang đo cao độ điện áp dọc 2 bên sườn (Ladder Scales: 11V..15V)
        for (int ly = 48; ly <= 118; ly += 14)
        {
            canvasSprite.drawLine(18, ly, 26, ly, voltColor);
            canvasSprite.drawLine(214, ly, 222, ly, voltColor);
        }

        // 6. Cụm điện áp trung tâm (y = 52..88)
        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "%.2f", batteryVoltage);
        myFont.set_font(FONT_CLOCK);
        uint16_t vLen = myFont.getLength(vBuf);
        myFont.print(112 - vLen / 2, 50, vBuf, TFT_WHITE, TFT_BLACK);

        myFont.set_font(FONT_STATUS_INFO);
        myFont.print(118 + vLen / 2, 60, "V", voltColor, TFT_BLACK);

        // 7. Vẽ Xe Moto Phân Khối Lớn 3D Siêu Chi Tiết (Kawasaki Ninja H2 Cyberpunk)
        const int bx = 120, by = 172;
        // Bóng đổ xe
        canvasSprite.fillEllipse(bx, by + 14, 24, 5, color565(6, 12, 20));

        // Tia lửa phản lực ống pô khi xe đang chạy
        if (gpsSpeed > 0)
        {
            int flameH = (int)((gpsSpeed / 120.0f) * 18.0f) + (millis() % 4);
            canvasSprite.fillTriangle(bx - 12, by + 6, bx - 9, by + 6 + flameH, bx - 6, by + 6, voltColor);
            canvasSprite.fillTriangle(bx + 6, by + 6, bx + 9, by + 6 + flameH, bx + 12, by + 6, voltColor);
        }

        // Lốp béo thể thao 200/55
        canvasSprite.fillRoundRect(bx - 9, by - 5, 18, 20, 4, color565(15, 23, 42));
        canvasSprite.drawRoundRect(bx - 9, by - 5, 18, 20, 4, color565(30, 41, 59));
        // Rãnh lốp
        canvasSprite.drawLine(bx - 5, by - 1, bx + 5, by - 1, voltColor);
        canvasSprite.drawLine(bx - 5, by + 5, bx + 5, by + 5, voltColor);

        // Ống pô kép Titanium Carbon
        canvasSprite.fillRect(bx - 14, by - 2, 5, 10, color565(51, 65, 85));
        canvasSprite.fillRect(bx + 9, by - 2, 5, 10, color565(51, 65, 85));

        // Khung sườn xe khí động học Ninja H2
        canvasSprite.fillTriangle(bx, by - 24, bx - 15, by - 6, bx + 15, by - 6, color565(15, 23, 42));
        canvasSprite.drawLine(bx, by - 22, bx - 13, by - 5, voltColor);
        canvasSprite.drawLine(bx, by - 22, bx + 13, by - 5, voltColor);

        // Đèn hậu LED cánh én chữ V
        uint16_t tailCol = (batteryVoltage < 11.8f) ? TFT_RED : TFT_RED;
        canvasSprite.drawLine(bx - 12, by - 10, bx - 2, by - 7, tailCol);
        canvasSprite.drawLine(bx - 12, by - 9, bx - 2, by - 6, tailCol);
        canvasSprite.drawLine(bx + 12, by - 10, bx + 2, by - 7, tailCol);
        canvasSprite.drawLine(bx + 12, by - 9, bx + 2, by - 6, tailCol);

        // Nón bảo hiểm Rider Cyber Visor
        canvasSprite.fillCircle(bx, by - 28, 5, color565(30, 41, 59));
        canvasSprite.drawCircle(bx, by - 28, 4, voltColor);

        // 8. Cửa sổ sóng Oscilloscope Radar 3D (x=24, y=100, w=192, oh=64)
        int ox = 24, oy = 100, ow = 192, oh = 64;

        // THUẬT TOÁN AUTO-ZOOM CHỐNG RUNG GIẬT & ĐỘ NHẠY CAO CHO SÓNG DAO ĐỘNG:
        float localMin = 99.0f, localMax = -99.0f;
        for (int i = 0; i < VOLT_HISTORY_SIZE; i++)
        {
            float val = voltHistory[i];
            if (val < localMin) localMin = val;
            if (val > localMax) localMax = val;
        }
        if (localMin > 50.0f) localMin = batteryVoltage - 0.25f;
        if (localMax < -50.0f) localMax = batteryVoltage + 0.25f;

        float span = localMax - localMin;
        const float MIN_DSO_SPAN = 0.50f; // Sàn quan sát 500mV zoom to sóng rõ nét
        if (span < MIN_DSO_SPAN)
        {
            float mid = (localMax + localMin) * 0.5f;
            localMin = mid - (MIN_DSO_SPAN * 0.5f);
            localMax = mid + (MIN_DSO_SPAN * 0.5f);
        }
        else
        {
            float margin = span * 0.08f;
            localMin -= margin;
            localMax += margin;
        }

        // Bắt tức thì khi sụt áp đề máy hoặc sạc nổ máy, mượt khi tĩnh
        static float smoothMinM4A = 11.0f, smoothMaxM4A = 13.5f;
        static bool isScaleInitM4A = false;
        if (!isScaleInitM4A)
        {
            smoothMinM4A = localMin;
            smoothMaxM4A = localMax;
            isScaleInitM4A = true;
        }

        if (localMin < smoothMinM4A)
            smoothMinM4A = localMin; // Bắt ngay đáy sụt áp đề máy không trễ
        else if (fabsf(localMin - smoothMinM4A) > 0.02f)
            smoothMinM4A += 0.12f * (localMin - smoothMinM4A);

        if (localMax > smoothMaxM4A)
            smoothMaxM4A = localMax; // Bắt ngay đỉnh sạc ga nổ máy
        else if (fabsf(localMax - smoothMaxM4A) > 0.02f)
            smoothMaxM4A += 0.12f * (localMax - smoothMaxM4A);

        float currentSpanM4A = smoothMaxM4A - smoothMinM4A;
        if (currentSpanM4A < MIN_DSO_SPAN)
        {
            float midM4A = (smoothMaxM4A + smoothMinM4A) * 0.5f;
            smoothMinM4A = midM4A - (MIN_DSO_SPAN * 0.5f);
            smoothMaxM4A = midM4A + (MIN_DSO_SPAN * 0.5f);
            currentSpanM4A = MIN_DSO_SPAN;
        }

        // Sóng Oscilloscope 3D 1:1 liên tục, không bị đứt đoạn hay nhấp nháy bỏ mẫu
        int plotW = ow - 4; // 188 điểm
        int prevPx = -1, prevPy = -1;
        int offsetStart = (VOLT_HISTORY_SIZE >= plotW) ? (VOLT_HISTORY_SIZE - plotW) : 0;
        for (int i = 0; i < plotW; i++)
        {
            int bufIdx = (voltHistoryIdx + offsetStart + i) % VOLT_HISTORY_SIZE;
            float val = voltHistory[bufIdx];
            uint16_t segColor = getVoltNeonColor(val);

            int py = oy + oh - 3 - (int)(((val - smoothMinM4A) / currentSpanM4A) * (oh - 6));
            if (py < oy + 2) py = oy + 2;
            if (py > oy + oh - 2) py = oy + oh - 2;
            int px = ox + 2 + i;

            if (i == 0)
            {
                prevPx = px;
                prevPy = py;
            }
            else
            {
                canvasSprite.drawLine(prevPx, prevPy, px, py, segColor);
                canvasSprite.drawLine(prevPx, prevPy + 1, px, py + 1, segColor);
                prevPx = px;
                prevPy = py;
            }
        }

        // 9. Footer Telemetry phân tầng thoáng đãng (y = 184 & 202 - Tuyệt đối không chồng lấn!)
        char spdBuf[16];
        snprintf(spdBuf, sizeof(spdBuf), "%d KM/H", gpsSpeed);
        myFont.set_font(vietnamtimes12);
        uint16_t spdLen = myFont.getLength(spdBuf);
        myFont.print(120 - spdLen / 2, 184, spdBuf, TFT_WHITE, TFT_BLACK);

        char minMaxBuf[32];
        snprintf(minMaxBuf, sizeof(minMaxBuf), "MIN:%.1fV   MAX:%.1fV", (voltMin > 50.0f) ? batteryVoltage : voltMin, (voltMax < 5.0f) ? batteryVoltage : voltMax);
        myFont.set_font(vietnamtimes12);
        uint16_t mmLen = myFont.getLength(minMaxBuf);
        myFont.print(120 - mmLen / 2, 202, minMaxBuf, color565(148, 163, 184), TFT_BLACK);
    }
    else if (statusStyle == 7)
    {
        // ==================== MẪU 4B CHÍNH THỨC: CYBER SUPERBIKE 3D SPEED PRO (GPS SPEED FOCUS) ====================
        // 1. Dynamic Speed Color (Chuyển tiếp mượt mà dải màu Neon RGB theo tốc độ GPS)
        uint16_t spdColor = getSpeedNeonColor(gpsSpeed);

        // 2. Viền Bezel ngoài cùng phát sáng theo dải tốc độ GPS
        canvasSprite.drawCircle(120, 120, 118, spdColor);
        canvasSprite.drawCircle(120, 120, 117, color565(15, 23, 42));

        // 3. Header: Đồng hồ thời gian lớn (y = 12) + Ngày | Thời tiết | Pin ĐT (y = 36)
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(FONT_HUD_DIST);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 12, timeStr, TFT_WHITE, TFT_BLACK);

        char wxBuf[16];
        if (weatherTemp > -50.0f && weatherTemp < 60.0f)
            snprintf(wxBuf, sizeof(wxBuf), "%.0f°C", weatherTemp);
        else
            snprintf(wxBuf, sizeof(wxBuf), "28°C");

        String dateStr = rtc.getTime("%d/%m");
        char pBuf[16];
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        snprintf(pBuf, sizeof(pBuf), "%d%%", pBat);

        char subHdrBuf[48];
        snprintf(subHdrBuf, sizeof(subHdrBuf), "%s | %s | %s", dateStr.c_str(), wxBuf, pBuf);
        myFont.set_font(vietnamtimes12);
        uint16_t shLen = myFont.getLength(subHdrBuf);
        myFont.print(120 - shLen / 2, 36, subHdrBuf, color565(148, 163, 184), TFT_BLACK);

        // BLE status dot
        canvasSprite.fillCircle(120 + shLen / 2 + 6, 40, 2, bleConnected ? TFT_CYAN : color565(71, 85, 105));

        // 4. Đường lưới 3D Perspective Road Grid chuyển động theo tốc độ GPS
        const int horizonY = 104;
        static float gridOffsetS7 = 0.0f;
        float speedFactor = (gpsSpeed / 60.0f) * 3.5f;
        if (speedFactor < 0.2f && gpsSpeed > 0) speedFactor = 0.2f;
        gridOffsetS7 = fmodf(gridOffsetS7 + speedFactor, 22.0f);

        for (int x = 10; x <= 230; x += 24)
        {
            canvasSprite.drawLine(120, horizonY, x, 240, color565(15, 30, 50));
        }
        for (int y = horizonY + 8; y < 240; y += 18)
        {
            int actualY = y + (int)(gridOffsetS7 * ((float)(y - horizonY) / 135.0f));
            if (actualY <= 238)
            {
                canvasSprite.drawLine(20, actualY, 220, actualY, color565(20, 40, 65));
            }
        }

        // 5. Thang đo cao độ tốc độ dọc 2 bên sườn (0..120 KM/H)
        for (int ly = 48; ly <= 118; ly += 14)
        {
            canvasSprite.drawLine(18, ly, 26, ly, spdColor);
            canvasSprite.drawLine(214, ly, 222, ly, spdColor);
        }

        // 6. Cụm Tốc Độ Trung Tâm (Focus lớn nhất)
        char sBuf[16];
        snprintf(sBuf, sizeof(sBuf), "%d", gpsSpeed);
        myFont.set_font(FONT_CLOCK);
        uint16_t sLen = myFont.getLength(sBuf);
        myFont.print(112 - sLen / 2, 50, sBuf, TFT_WHITE, TFT_BLACK);

        myFont.set_font(FONT_STATUS_INFO);
        myFont.print(116 + sLen / 2, 60, "KM/H", spdColor, TFT_BLACK);

        // 7. Xe Moto Phân Khối Lớn 3D Ninja H2 (Lửa pô theo tốc độ)
        const int bx = 120, by = 172;
        canvasSprite.fillEllipse(bx, by + 14, 24, 5, color565(6, 12, 20));
        if (gpsSpeed > 0)
        {
            int flameH = (int)((gpsSpeed / 120.0f) * 18.0f) + (millis() % 4);
            canvasSprite.fillTriangle(bx - 12, by + 6, bx - 9, by + 6 + flameH, bx - 6, by + 6, spdColor);
            canvasSprite.fillTriangle(bx + 6, by + 6, bx + 9, by + 6 + flameH, bx + 12, by + 6, spdColor);
        }
        canvasSprite.fillRoundRect(bx - 9, by - 5, 18, 20, 4, color565(15, 23, 42));
        canvasSprite.drawRoundRect(bx - 9, by - 5, 18, 20, 4, color565(30, 41, 59));
        canvasSprite.drawLine(bx - 5, by - 1, bx + 5, by - 1, spdColor);
        canvasSprite.drawLine(bx - 5, by + 5, bx + 5, by + 5, spdColor);

        canvasSprite.fillRect(bx - 14, by - 2, 5, 10, color565(51, 65, 85));
        canvasSprite.fillRect(bx + 9, by - 2, 5, 10, color565(51, 65, 85));

        canvasSprite.fillTriangle(bx, by - 24, bx - 15, by - 6, bx + 15, by - 6, color565(15, 23, 42));
        canvasSprite.drawLine(bx, by - 22, bx - 13, by - 5, spdColor);
        canvasSprite.drawLine(bx, by - 22, bx + 13, by - 5, spdColor);

        canvasSprite.drawLine(bx - 12, by - 10, bx - 2, by - 7, TFT_RED);
        canvasSprite.drawLine(bx - 12, by - 9, bx - 2, by - 6, TFT_RED);
        canvasSprite.drawLine(bx + 12, by - 10, bx + 2, by - 7, TFT_RED);
        canvasSprite.drawLine(bx + 12, by - 9, bx + 2, by - 6, TFT_RED);

        canvasSprite.fillCircle(bx, by - 28, 5, color565(30, 41, 59));
        canvasSprite.drawCircle(bx, by - 28, 4, spdColor);

        // 8. Cửa sổ sóng Oscilloscope TỐC ĐỘ GPS (x=24, y=100, w=192, oh=64)
        int ox = 24, oy = 100, ow = 192, oh = 64;
        float localMinSpd = 999.0f, localMaxSpd = -999.0f;
        for (int i = 0; i < SPEED_HISTORY_SIZE; i++)
        {
            float val = speedHistory[i];
            if (val < localMinSpd) localMinSpd = val;
            if (val > localMaxSpd) localMaxSpd = val;
        }
        if (localMinSpd > 500.0f) localMinSpd = (float)gpsSpeed;
        if (localMaxSpd < -500.0f) localMaxSpd = (float)gpsSpeed;

        float spanSpd = localMaxSpd - localMinSpd;
        const float MIN_SPEED_SPAN = 20.0f; // Sàn quan sát tối thiểu 20 km/h
        if (spanSpd < MIN_SPEED_SPAN)
        {
            float mid = (localMaxSpd + localMinSpd) * 0.5f;
            localMinSpd = (mid - (MIN_SPEED_SPAN * 0.5f) < 0.0f) ? 0.0f : (mid - (MIN_SPEED_SPAN * 0.5f));
            localMaxSpd = localMinSpd + MIN_SPEED_SPAN;
            spanSpd = MIN_SPEED_SPAN;
        }

        static float smoothMinM4B = 0.0f, smoothMaxM4B = 60.0f;
        static bool isScaleInitM4B = false;
        if (!isScaleInitM4B)
        {
            smoothMinM4B = localMinSpd;
            smoothMaxM4B = localMaxSpd;
            isScaleInitM4B = true;
        }

        if (localMinSpd < smoothMinM4B) smoothMinM4B = localMinSpd;
        else if (fabsf(localMinSpd - smoothMinM4B) > 0.5f) smoothMinM4B += 0.15f * (localMinSpd - smoothMinM4B);

        if (localMaxSpd > smoothMaxM4B) smoothMaxM4B = localMaxSpd;
        else if (fabsf(localMaxSpd - smoothMaxM4B) > 0.5f) smoothMaxM4B += 0.15f * (localMaxSpd - smoothMaxM4B);

        float currentSpanM4B = smoothMaxM4B - smoothMinM4B;
        if (currentSpanM4B < MIN_SPEED_SPAN) currentSpanM4B = MIN_SPEED_SPAN;

        int plotW = ow - 4; // 188 điểm
        int prevPx = -1, prevPy = -1;
        int offsetStart = (SPEED_HISTORY_SIZE >= plotW) ? (SPEED_HISTORY_SIZE - plotW) : 0;
        for (int i = 0; i < plotW; i++)
        {
            int bufIdx = (speedHistoryIdx + offsetStart + i) % SPEED_HISTORY_SIZE;
            float val = speedHistory[bufIdx];
            uint16_t segColor = getSpeedNeonColor(val);

            int py = oy + oh - 3 - (int)(((val - smoothMinM4B) / currentSpanM4B) * (oh - 6));
            if (py < oy + 2) py = oy + 2;
            if (py > oy + oh - 2) py = oy + oh - 2;
            int px = ox + 2 + i;

            if (i == 0)
            {
                prevPx = px;
                prevPy = py;
            }
            else
            {
                canvasSprite.drawLine(prevPx, prevPy, px, py, segColor);
                canvasSprite.drawLine(prevPx, prevPy + 1, px, py + 1, segColor);
                prevPx = px;
                prevPy = py;
            }
        }

        // 9. Footer Telemetry: Điện Áp Ắc Quy & Max Speed
        uint16_t vCol = getVoltNeonColor(batteryVoltage);
        char vFootBuf[16];
        snprintf(vFootBuf, sizeof(vFootBuf), "%.2f V", batteryVoltage);
        myFont.set_font(vietnamtimes12);
        uint16_t vfLen = myFont.getLength(vFootBuf);
        myFont.print(120 - vfLen / 2, 184, vFootBuf, vCol, TFT_BLACK);

        char spdStatBuf[32];
        snprintf(spdStatBuf, sizeof(spdStatBuf), "SPD MAX:%d   AVG:%d", (int)smoothMaxM4B, (int)((smoothMaxM4B + smoothMinM4B) * 0.5f));
        myFont.set_font(vietnamtimes12);
        uint16_t ssLen = myFont.getLength(spdStatBuf);
        myFont.print(120 - ssLen / 2, 202, spdStatBuf, color565(148, 163, 184), TFT_BLACK);
    }
    else if (statusStyle == 8)
    {
        // ==================== MẪU 4C CHÍNH THỨC: CYBER SUPERBIKE 3D DUAL-TRACE PRO (VỪA ĐO VOL VỪA GPS 2 SÓNG) ====================
        // 1. Dual Dynamic Colors
        uint16_t voltColor = getVoltNeonColor(batteryVoltage);
        uint16_t spdColor = getSpeedNeonColor(gpsSpeed);

        // 2. Viền Bezel ngoài kép phát sáng (Nửa trái: Volt Color, Nửa phải: Speed Color)
        for (int a = 90; a < 270; a += 4)
        {
            float rad = a * 0.0174533f;
            int x1 = 120 + (int)(118 * cosf(rad));
            int y1 = 120 + (int)(118 * sinf(rad));
            canvasSprite.drawPixel(x1, y1, voltColor);
        }
        for (int a = -90; a < 90; a += 4)
        {
            float rad = a * 0.0174533f;
            int x1 = 120 + (int)(118 * cosf(rad));
            int y1 = 120 + (int)(118 * sinf(rad));
            canvasSprite.drawPixel(x1, y1, spdColor);
        }

        // 3. Header: Đồng hồ thời gian + Ngày/Nhiệt độ/Pin
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(FONT_HUD_DIST);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 12, timeStr, TFT_WHITE, TFT_BLACK);

        char wxBuf[16];
        if (weatherTemp > -50.0f && weatherTemp < 60.0f)
            snprintf(wxBuf, sizeof(wxBuf), "%.0f°C", weatherTemp);
        else
            snprintf(wxBuf, sizeof(wxBuf), "28°C");

        String dateStr = rtc.getTime("%d/%m");
        char pBuf[16];
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        snprintf(pBuf, sizeof(pBuf), "%d%%", pBat);

        char subHdrBuf[48];
        snprintf(subHdrBuf, sizeof(subHdrBuf), "%s | %s | %s", dateStr.c_str(), wxBuf, pBuf);
        myFont.set_font(vietnamtimes12);
        uint16_t shLen = myFont.getLength(subHdrBuf);
        myFont.print(120 - shLen / 2, 36, subHdrBuf, color565(148, 163, 184), TFT_BLACK);

        // BLE status dot
        canvasSprite.fillCircle(120 + shLen / 2 + 6, 40, 2, bleConnected ? TFT_CYAN : color565(71, 85, 105));

        // 4. Perspective Road Grid
        const int horizonY = 104;
        static float gridOffsetS8 = 0.0f;
        float speedFactor = (gpsSpeed / 60.0f) * 3.5f;
        if (speedFactor < 0.2f && gpsSpeed > 0) speedFactor = 0.2f;
        gridOffsetS8 = fmodf(gridOffsetS8 + speedFactor, 22.0f);

        for (int x = 10; x <= 230; x += 24)
        {
            canvasSprite.drawLine(120, horizonY, x, 240, color565(15, 30, 50));
        }
        for (int y = horizonY + 8; y < 240; y += 18)
        {
            int actualY = y + (int)(gridOffsetS8 * ((float)(y - horizonY) / 135.0f));
            if (actualY <= 238)
            {
                canvasSprite.drawLine(20, actualY, 220, actualY, color565(20, 40, 65));
            }
        }

        // 5. Thang đo kép 2 bên sườn: Trái đo Volt, Phải đo Tốc độ
        for (int ly = 48; ly <= 118; ly += 14)
        {
            canvasSprite.drawLine(18, ly, 26, ly, voltColor); // Thang Volt
            canvasSprite.drawLine(214, ly, 222, ly, spdColor); // Thang Speed
        }

        // 6. Cụm Số Đo Song Song (Speed số lớn + Volt sắc nét bên cạnh)
        char sBuf[16];
        snprintf(sBuf, sizeof(sBuf), "%d", gpsSpeed);
        myFont.set_font(FONT_CLOCK);
        uint16_t sLen = myFont.getLength(sBuf);
        myFont.print(102 - sLen / 2, 50, sBuf, TFT_WHITE, TFT_BLACK);

        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "%.2fV", batteryVoltage);
        myFont.set_font(vietnamtimes12);
        myFont.print(112 + sLen / 2, 56, "KM/H", spdColor, TFT_BLACK);
        myFont.print(112 + sLen / 2, 70, vBuf, voltColor, TFT_BLACK);

        // 7. Xe Moto Phân Khối Lớn 3D Ninja H2
        const int bx = 120, by = 172;
        canvasSprite.fillEllipse(bx, by + 14, 24, 5, color565(6, 12, 20));
        if (gpsSpeed > 0)
        {
            int flameH = (int)((gpsSpeed / 120.0f) * 18.0f) + (millis() % 4);
            canvasSprite.fillTriangle(bx - 12, by + 6, bx - 9, by + 6 + flameH, bx - 6, by + 6, spdColor);
            canvasSprite.fillTriangle(bx + 6, by + 6, bx + 9, by + 6 + flameH, bx + 12, by + 6, spdColor);
        }
        canvasSprite.fillRoundRect(bx - 9, by - 5, 18, 20, 4, color565(15, 23, 42));
        canvasSprite.drawRoundRect(bx - 9, by - 5, 18, 20, 4, color565(30, 41, 59));
        canvasSprite.drawLine(bx - 5, by - 1, bx + 5, by - 1, spdColor);
        canvasSprite.drawLine(bx - 5, by + 5, bx + 5, by + 5, spdColor);

        canvasSprite.fillRect(bx - 14, by - 2, 5, 10, color565(51, 65, 85));
        canvasSprite.fillRect(bx + 9, by - 2, 5, 10, color565(51, 65, 85));

        canvasSprite.fillTriangle(bx, by - 24, bx - 15, by - 6, bx + 15, by - 6, color565(15, 23, 42));
        canvasSprite.drawLine(bx, by - 22, bx - 13, by - 5, spdColor);
        canvasSprite.drawLine(bx, by - 22, bx + 13, by - 5, spdColor);

        canvasSprite.drawLine(bx - 12, by - 10, bx - 2, by - 7, TFT_RED);
        canvasSprite.drawLine(bx - 12, by - 9, bx - 2, by - 6, TFT_RED);
        canvasSprite.drawLine(bx + 12, by - 10, bx + 2, by - 7, TFT_RED);
        canvasSprite.drawLine(bx + 12, by - 9, bx + 2, by - 6, TFT_RED);

        canvasSprite.fillCircle(bx, by - 28, 5, color565(30, 41, 59));
        canvasSprite.drawCircle(bx, by - 28, 4, spdColor);

        // 8. Cửa sổ Oscilloscope Radar DUAL-TRACE (2 SÓNG HIỆN CÙNG LÚC!)
        int ox = 24, oy = 100, ow = 192, oh = 64;
        int plotW = ow - 4; // 188 điểm

        // Scale cho Sóng Điện Áp (Channel 1)
        float localMinV = 99.0f, localMaxV = -99.0f;
        for (int i = 0; i < VOLT_HISTORY_SIZE; i++)
        {
            float val = voltHistory[i];
            if (val < localMinV) localMinV = val;
            if (val > localMaxV) localMaxV = val;
        }
        if (localMinV > 50.0f) localMinV = batteryVoltage - 0.25f;
        if (localMaxV < -50.0f) localMaxV = batteryVoltage + 0.25f;
        float spanV = localMaxV - localMinV;
        if (spanV < 0.50f) {
            float mid = (localMaxV + localMinV) * 0.5f;
            localMinV = mid - 0.25f;
            localMaxV = mid + 0.25f;
            spanV = 0.50f;
        }

        // Scale cho Sóng Tốc Độ GPS (Channel 2)
        float localMinS = 999.0f, localMaxS = -999.0f;
        for (int i = 0; i < SPEED_HISTORY_SIZE; i++)
        {
            float val = speedHistory[i];
            if (val < localMinS) localMinS = val;
            if (val > localMaxS) localMaxS = val;
        }
        if (localMinS > 500.0f) localMinS = (float)gpsSpeed;
        if (localMaxS < -500.0f) localMaxS = (float)gpsSpeed;
        float spanS = localMaxS - localMinS;
        if (spanS < 20.0f) {
            float mid = (localMaxS + localMinS) * 0.5f;
            localMinS = (mid - 10.0f < 0.0f) ? 0.0f : (mid - 10.0f);
            localMaxS = localMinS + 20.0f;
            spanS = 20.0f;
        }

        int offsetStartV = (VOLT_HISTORY_SIZE >= plotW) ? (VOLT_HISTORY_SIZE - plotW) : 0;
        int offsetStartS = (SPEED_HISTORY_SIZE >= plotW) ? (SPEED_HISTORY_SIZE - plotW) : 0;

        // VẼ SÓNG KÊNH 1: ĐIỆN ÁP (VOLT TRACE - Nét liền kép)
        int prevVx = -1, prevVy = -1;
        for (int i = 0; i < plotW; i++)
        {
            int bufIdx = (voltHistoryIdx + offsetStartV + i) % VOLT_HISTORY_SIZE;
            float val = voltHistory[bufIdx];
            uint16_t segCol = getVoltNeonColor(val);

            int py = oy + oh - 4 - (int)(((val - localMinV) / spanV) * (oh - 8));
            if (py < oy + 2) py = oy + 2;
            if (py > oy + oh - 2) py = oy + oh - 2;
            int px = ox + 2 + i;

            if (i == 0) { prevVx = px; prevVy = py; }
            else
            {
                canvasSprite.drawLine(prevVx, prevVy, px, py, segCol);
                prevVx = px; prevVy = py;
            }
        }

        // VẼ SÓNG KÊNH 2: TỐC ĐỘ GPS (SPEED TRACE - Nét đứt phân biệt)
        int prevSx = -1, prevSy = -1;
        for (int i = 0; i < plotW; i++)
        {
            int bufIdx = (speedHistoryIdx + offsetStartS + i) % SPEED_HISTORY_SIZE;
            float val = speedHistory[bufIdx];
            uint16_t segCol = getSpeedNeonColor(val);

            int py = oy + oh - 4 - (int)(((val - localMinS) / spanS) * (oh - 8));
            if (py < oy + 2) py = oy + 2;
            if (py > oy + oh - 2) py = oy + oh - 2;
            int px = ox + 2 + i;

            if (i == 0) { prevSx = px; prevSy = py; }
            else
            {
                if (i % 3 != 0) // Nét đứt dạ quang thể hiện kênh 2
                {
                    canvasSprite.drawLine(prevSx, prevSy, px, py, segCol);
                }
                prevSx = px; prevSy = py;
            }
        }

        // Nhãn chú thích 2 kênh ở góc sóng
        myFont.set_font(vietnamtimes12);
        myFont.print(ox + 4, oy + 4, "CH1:V", voltColor, TFT_BLACK);
        myFont.print(ox + 54, oy + 4, "CH2:SPD", spdColor, TFT_BLACK);

        // 9. Footer Telemetry: Hiển thị song song cả 2 thông số
        char dFootBuf[32];
        snprintf(dFootBuf, sizeof(dFootBuf), "%.1fV  |  %d KM/H", batteryVoltage, gpsSpeed);
        myFont.set_font(vietnamtimes12);
        uint16_t dfLen = myFont.getLength(dFootBuf);
        myFont.print(120 - dfLen / 2, 184, dFootBuf, TFT_WHITE, TFT_BLACK);

        char dStatBuf[32];
        snprintf(dStatBuf, sizeof(dStatBuf), "V:%.1f-%.1f  S_MAX:%d", (voltMin > 50.0f) ? batteryVoltage : voltMin, (voltMax < 5.0f) ? batteryVoltage : voltMax, (int)localMaxS);
        myFont.set_font(vietnamtimes12);
        uint16_t dsLen = myFont.getLength(dStatBuf);
        myFont.print(120 - dsLen / 2, 202, dStatBuf, color565(148, 163, 184), TFT_BLACK);
    }
    else
    {
        // ---------------- S4a: CYBER DUAL ARC GAUGES (MÀN HÌNH GỐC S1/S4) ----------------
        // 1. Cung vạch Ắc-quy xe (Trái: 130°..230°, R=110px, Xanh Lá Neon)
        int vSpan = (int)((batteryVoltage / 15.0f) * 100.0f);
        if (vSpan > 100)
            vSpan = 100;
        uint16_t vColor = (batteryVoltage > 12.0f) ? TFT_GREEN : ((batteryVoltage > 11.0f) ? TFT_YELLOW : TFT_RED);

        drawArcSegment(canvasSprite, 120, 120, 110, 130, 230, color565(30, 41, 59));
        drawArcSegment(canvasSprite, 120, 120, 110, 130, 130 + vSpan, vColor);
        drawArcSegment(canvasSprite, 120, 120, 109, 130, 130 + vSpan, vColor);

        char vBuf[12];
        snprintf(vBuf, sizeof(vBuf), "%.1fV", batteryVoltage);
        myFont.set_font(FONT_STATUS_INFO);
        myFont.print(16, 112, vBuf, vColor, TFT_BLACK);

        // 2. Cung vạch Pin Phone (Phải: -50°..50°, R=110px, Cyan)
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        int pSpan = (int)((pBat / 100.0f) * 100.0f);
        if (pSpan > 100)
            pSpan = 100;

        drawArcSegment(canvasSprite, 120, 120, 110, -50, 50, color565(30, 41, 59));
        drawArcSegment(canvasSprite, 120, 120, 110, -50, -50 + pSpan, TFT_CYAN);
        drawArcSegment(canvasSprite, 120, 120, 109, -50, -50 + pSpan, TFT_CYAN);

        char pBuf[12];
        snprintf(pBuf, sizeof(pBuf), "%d%%", pBat);
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t pLen = myFont.getLength(pBuf);
        myFont.print(224 - pLen, 112, pBuf, TFT_CYAN, TFT_BLACK);

        // 3. Giờ số Cyan to ở trung tâm (y = 65)
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(FONT_CLOCK);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 65, timeStr, TFT_CYAN, TFT_BLACK);

        // 4. Thứ & Ngày tháng Tiếng Việt ở trung tâm (y = 118)
        const char *dayOfWeekVi[] = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};
        int dow = rtc.getDayofWeek();
        if (dow < 0 || dow > 6)
            dow = 0;
        String dateStr = String(dayOfWeekVi[dow]) + ", " + rtc.getTime("%d/%m/%Y");
        myFont.set_font(vietnamtimes12);
        uint16_t dateLen = myFont.getLength(dateStr);
        myFont.print(120 - dateLen / 2, 118, dateStr, TFT_WHITE, TFT_BLACK);

        // 5. Thời tiết Vàng rực rỡ + Mặt trời Vector ở trung tâm (y = 145)
        char wxBuf[32];
        if (weatherTemp > -50.0f && weatherTemp < 60.0f)
        {
            snprintf(wxBuf, sizeof(wxBuf), "%.0f°C  Nắng", weatherTemp);
        }
        else
        {
            snprintf(wxBuf, sizeof(wxBuf), "31°C  Nắng");
        }
        myFont.set_font(vietnamtimes12);
        uint16_t wxLen = myFont.getLength(wxBuf);
        int wxX = 120 - (wxLen + 18) / 2;
        canvasSprite.fillCircle(wxX + 6, 151, 5, TFT_YELLOW);
        canvasSprite.drawCircle(wxX + 6, 151, 7, TFT_ORANGE);
        myFont.print(wxX + 18, 145, wxBuf, TFT_YELLOW, TFT_BLACK);
    }

    // Vẽ Overlay Cảnh báo Giao thông (nếu có)
    drawTrafficWarningOverlay();

    canvasSprite.pushSprite(0, 0);
}

// Vẽ một phân đoạn cung tròn có độ dày nhất định ôm sát viền
void drawArcSegment(TFT_eSprite &sprite, int cx, int cy, int r, int startAngle, int endAngle, uint16_t color)
{
    float startRad = startAngle * DEG_TO_RAD;
    float endRad = endAngle * DEG_TO_RAD;
    float step = 1.0 * DEG_TO_RAD; // độ mịn 1 độ mỗi bước

    int prevX = -1, prevY = -1;
    for (float a = startRad; a <= endRad; a += step)
    {
        int x = cx + r * cos(a);
        int y = cy + r * sin(a);
        if (prevX != -1)
        {
            // Vẽ 3 đường thẳng song thiết tạo độ dày cung tròn
            sprite.drawLine(prevX, prevY, x, y, color);
            sprite.drawLine(prevX, prevY - 1, x, y - 1, color);
            sprite.drawLine(prevX - 1, prevY, x - 1, y, color);
        }
        prevX = x;
        prevY = y;
    }
    // Vẽ điểm chốt cuối
    int x = cx + r * cos(endRad);
    int y = cy + r * sin(endRad);
    if (prevX != -1)
    {
        sprite.drawLine(prevX, prevY, x, y, color);
    }
}

// Mở menu chồng chế độ LÊN SPRITE (BIẾN THỂ M3a: MULTI-RING MATRIX HUD)
void drawMenuOverlay()
{
    canvasSprite.fillSprite(TFT_BLACK);

    // Dynamic Color Accent per menu item:
    // 0: HUD (Cyan), 1: MAP (Green), 2: STATUS (Yellow), 3: INFO (Purple), 4: NOTIF (Red), 5: SETTINGS (Orange)
    uint16_t accentColors[] = {TFT_CYAN, TFT_GREEN, TFT_YELLOW, 0xA81F, 0xF810, TFT_ORANGE};
    uint16_t currentAccent = accentColors[menuSelectedIndex % 6];

    // 1. Render 6 Outer Sector Arc Segments at radius R = 110px
    // Angles evenly distributed around 360° (6 sectors of 46° each + 14° gap):
    // 0: HUD (-113°..-67°), 1: MAP (-53°..-7°), 2: STATUS (7°..53°), 3: INFO (67°..113°), 4: NOTIF (127°..173°), 5: SETTINGS (-173°..-127°)
    int startAngles[] = {-113, -53, 7, 67, 127, -173};
    int endAngles[] = {-67, -7, 53, 113, 173, -127};

    for (int i = 0; i < 6; i++)
    {
        bool isSelected = (i == menuSelectedIndex);
        uint16_t arcColor = isSelected ? accentColors[i] : color565(30, 41, 59);

        // Draw Arc Segment
        drawArcSegment(canvasSprite, 120, 120, 110, startAngles[i], endAngles[i], arcColor);
        if (isSelected)
        {
            drawArcSegment(canvasSprite, 120, 120, 109, startAngles[i], endAngles[i], arcColor);
            drawArcSegment(canvasSprite, 120, 120, 111, startAngles[i], endAngles[i], TFT_WHITE);
        }

        // Render Crisp Vector Icon near middle of arc
        float midDeg = (startAngles[i] + endAngles[i]) / 2.0f;
        float midRad = midDeg * 0.0174532925f;
        int ix = 120 + (int)(92.0f * cos(midRad));
        int iy = 120 + (int)(92.0f * sin(midRad));

        uint16_t iconColor = isSelected ? accentColors[i] : color565(100, 116, 139);

        if (i == 0)
        { // HUD
            canvasSprite.drawLine(ix, iy + 6, ix, iy - 6, iconColor);
            canvasSprite.fillTriangle(ix, iy - 7, ix - 4, iy - 1, ix + 4, iy - 1, iconColor);
        }
        else if (i == 1)
        { // MAP
            canvasSprite.drawRect(ix - 5, iy - 5, 3, 10, iconColor);
            canvasSprite.drawRect(ix - 2, iy - 4, 3, 10, iconColor);
            canvasSprite.drawRect(ix + 1, iy - 5, 3, 10, iconColor);
        }
        else if (i == 2)
        { // STATUS
            canvasSprite.drawCircle(ix, iy, 6, iconColor);
            canvasSprite.drawLine(ix, iy, ix, iy - 3, iconColor);
            canvasSprite.drawLine(ix, iy, ix + 3, iy, iconColor);
        }
        else if (i == 3)
        { // INFO
            canvasSprite.fillCircle(ix, iy - 4, 1, iconColor);
            canvasSprite.drawLine(ix, iy - 1, ix, iy + 4, iconColor);
            canvasSprite.drawLine(ix - 2, iy - 1, ix + 2, iy - 1, iconColor);
            canvasSprite.drawLine(ix - 2, iy + 4, ix + 2, iy + 4, iconColor);
        }
        else if (i == 4)
        { // NOTIF
            canvasSprite.drawRect(ix - 5, iy - 4, 10, 8, iconColor);
            canvasSprite.drawLine(ix - 5, iy - 4, ix, iy, iconColor);
            canvasSprite.drawLine(ix + 5, iy - 4, ix, iy, iconColor);
        }
        else if (i == 5)
        { // SETTINGS
            canvasSprite.drawCircle(ix, iy, 5, iconColor);
            canvasSprite.drawLine(ix - 6, iy, ix + 6, iy, iconColor);
            canvasSprite.drawLine(ix, iy - 6, ix, iy + 6, iconColor);
        }
    }

    // 2. Double Center Hub Circle (cx=120, cy=120)
    uint16_t hubBg = color565(15, 23, 42); // Solid dark navy
    canvasSprite.fillCircle(120, 120, 48, hubBg);
    canvasSprite.drawCircle(120, 120, 48, currentAccent);
    canvasSprite.drawCircle(120, 120, 47, currentAccent);
    canvasSprite.drawCircle(120, 120, 41, color565(51, 65, 85));

    // Big Vector Icon at Center Hub
    int cx = 120, cy = 100;
    uint16_t hubIconColor = currentAccent;

    if (menuSelectedIndex == 0)
    { // HUD Arrow
        canvasSprite.drawLine(cx, cy + 10, cx, cy - 10, hubIconColor);
        canvasSprite.fillTriangle(cx, cy - 12, cx - 7, cy - 2, cx + 7, cy - 2, hubIconColor);
    }
    else if (menuSelectedIndex == 1)
    { // MAP
        canvasSprite.drawRect(cx - 9, cy - 9, 6, 18, hubIconColor);
        canvasSprite.drawRect(cx - 3, cy - 7, 6, 18, hubIconColor);
        canvasSprite.drawRect(cx + 3, cy - 9, 6, 18, hubIconColor);
    }
    else if (menuSelectedIndex == 2)
    { // STATUS Clock
        canvasSprite.drawCircle(cx, cy, 10, hubIconColor);
        canvasSprite.drawLine(cx, cy, cx, cy - 6, hubIconColor);
        canvasSprite.drawLine(cx, cy, cx + 5, cy, hubIconColor);
    }
    else if (menuSelectedIndex == 3)
    { // INFO
        canvasSprite.fillCircle(cx, cy - 6, 2, hubIconColor);
        canvasSprite.drawLine(cx, cy - 2, cx, cy + 6, hubIconColor);
        canvasSprite.drawLine(cx - 3, cy - 2, cx + 3, cy - 2, hubIconColor);
        canvasSprite.drawLine(cx - 3, cy + 6, cx + 3, cy + 6, hubIconColor);
    }
    else if (menuSelectedIndex == 4)
    { // NOTIF
        canvasSprite.drawRect(cx - 8, cy - 6, 16, 12, hubIconColor);
        canvasSprite.drawLine(cx - 8, cy - 6, cx, cy, hubIconColor);
        canvasSprite.drawLine(cx + 8, cy - 6, cx, cy, hubIconColor);
    }
    else if (menuSelectedIndex == 5)
    { // SETTINGS Gear
        canvasSprite.drawCircle(cx, cy, 8, hubIconColor);
        canvasSprite.drawCircle(cx, cy, 4, hubIconColor);
        canvasSprite.drawLine(cx - 10, cy, cx + 10, cy, hubIconColor);
        canvasSprite.drawLine(cx, cy - 10, cx, cy + 10, hubIconColor);
    }

    // In tên Tiếng Việt chế độ đang chọn ở tâm vòng tròn (y = 124)
    String selectedName = "";
    switch (menuSelectedIndex)
    {
    case 0:
        selectedName = "DẪN ĐƯỜNG";
        break;
    case 1:
        selectedName = "BẢN ĐỒ";
        break;
    case 2:
        selectedName = "TRẠNG THÁI";
        break;
    case 3:
        selectedName = "THÔNG TIN";
        break;
    case 4:
        selectedName = "THÔNG BÁO";
        break;
    case 5:
        selectedName = "CÀI ĐẶT";
        break;
    default:
        selectedName = "TYMAP";
        break;
    }

    myFont.set_font(FONT_MENU_OPTION);
    uint16_t nameLen = myFont.getLength(selectedName);
    myFont.print(120 - nameLen / 2, 122, selectedName, TFT_WHITE, hubBg);

    canvasSprite.pushSprite(0, 0);
}

// ==========================================
// 6. GIAO DIỆN CÀI ĐẶT HỆ THỐNG (SETTINGS)
// ==========================================
void drawSETTINGS()
{
    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Tiêu đề màn hình Cài Đặt
    String titleStr = "CÀI ĐẶT HỆ THỐNG";
    myFont.set_font(FONT_MENU_TITLE);
    uint16_t titleLen = myFont.getLength(titleStr);
    myFont.print(120 - titleLen / 2, 16, titleStr, TFT_ORANGE, TFT_BLACK);

    // 2. Thẻ 1: MẶT ĐỒNG HỒ (STATUS) (y = 40..80)
    bool isSelected0 = (settingCategoryIndex == 0);
    uint16_t bg0 = isSelected0 ? color565(30, 41, 59) : color565(15, 23, 42);
    uint16_t border0 = isSelected0 ? TFT_YELLOW : color565(51, 65, 85);
    canvasSprite.fillRoundRect(18, 40, 204, 42, 8, bg0);
    canvasSprite.drawRoundRect(18, 40, 204, 42, 8, border0);
    if (isSelected0)
        canvasSprite.drawRoundRect(19, 41, 202, 40, 7, border0);

    myFont.set_font(vietnamtimes12);
    myFont.print(26, 45, "1. MẶT ĐỒNG HỒ", isSelected0 ? TFT_YELLOW : TFT_SILVER, bg0);

    String statusName = "";
    switch (statusStyle)
    {
    case 0:
        statusName = "S4: Cyber Dual";
        break;
    case 1:
        statusName = "S5: Classic Analog";
        break;
    case 2:
        statusName = "S3: Dual Pill";
        break;
    case 3:
        statusName = "M4: Luxury Horizon";
        break;
    case 4:
        statusName = "S7: Sport Dynamic";
        break;
    case 5:
        statusName = "M2: Sport Radar (Scope)";
        break;
    case 6:
        statusName = "M4A: Cyber Superbike 3D";
        break;
    case 7:
        statusName = "M4B: Cyber Speed 3D";
        break;
    case 8:
        statusName = "M4C: Cyber Dual-Trace 3D";
        break;
    default:
        statusName = "M4A: Cyber Superbike 3D";
        break;
    }
    myFont.print(26, 62, statusName, TFT_WHITE, bg0);

    // 3. Thẻ 2: HUD BẢN ĐỒ (MAP_HUD) (y = 88..128)
    bool isSelected1 = (settingCategoryIndex == 1);
    uint16_t bg1 = isSelected1 ? color565(30, 41, 59) : color565(15, 23, 42);
    uint16_t border1 = isSelected1 ? TFT_GREEN : color565(51, 65, 85);
    canvasSprite.fillRoundRect(18, 88, 204, 42, 8, bg1);
    canvasSprite.drawRoundRect(18, 88, 204, 42, 8, border1);
    if (isSelected1)
        canvasSprite.drawRoundRect(19, 89, 202, 40, 7, border1);

    myFont.set_font(vietnamtimes12);
    myFont.print(26, 93, "2. HUD BẢN ĐỒ", isSelected1 ? TFT_GREEN : TFT_SILVER, bg1);

    String mapHudName = "";
    switch (mapHudStyle)
    {
    case 0:
        mapHudName = "MH1: Compact Pill";
        break;
    case 1:
        mapHudName = "MH2: Bottom Bar";
        break;
    case 2:
        mapHudName = "MH3: Minimalist";
        break;
    case 3:
        mapHudName = "MH4: Top Header";
        break;
    case 4:
        mapHudName = "MH5: Pure Map";
        break;
    case 5:
        mapHudName = "MH6: Galaxy Watch";
        break;
    default:
        mapHudName = "MH1: Compact Pill";
        break;
    }
    myFont.print(26, 110, mapHudName, TFT_WHITE, bg1);

    // 4. Thẻ 3: THÔNG BÁO (NOTIF) (y = 136..176)
    bool isSelected2 = (settingCategoryIndex == 2);
    uint16_t bg2 = isSelected2 ? color565(30, 41, 59) : color565(15, 23, 42);
    uint16_t border2 = isSelected2 ? TFT_CYAN : color565(51, 65, 85);
    canvasSprite.fillRoundRect(18, 136, 204, 42, 8, bg2);
    canvasSprite.drawRoundRect(18, 136, 204, 42, 8, border2);
    if (isSelected2)
        canvasSprite.drawRoundRect(19, 137, 202, 40, 7, border2);

    myFont.set_font(vietnamtimes12);
    myFont.print(26, 141, "3. THÔNG BÁO", isSelected2 ? TFT_CYAN : TFT_SILVER, bg2);

    String notifName = "";
    switch (notifStyle)
    {
    case 0:
        notifName = "N1: Floating Card";
        break;
    case 1:
        notifName = "N2: Fullscreen Focus";
        break;
    default:
        notifName = "N2: Fullscreen Focus";
        break;
    }
    myFont.print(26, 158, notifName, TFT_WHITE, bg2);

    // 5. Hướng dẫn thao tác phím ở đáy màn hình
    myFont.set_font(vietnamtimes12);
    String guide1 = "MODE: ĐỔI  ·  ZOOM: CHUYỂN";
    uint16_t g1Len = myFont.getLength(guide1);
    myFont.print(120 - g1Len / 2, 186, guide1, TFT_WHITE, TFT_BLACK);

    String guide2 = "GIỮ MODE: LƯU & THOÁT";
    uint16_t g2Len = myFont.getLength(guide2);
    myFont.print(120 - g2Len / 2, 204, guide2, TFT_DARKGREY, TFT_BLACK);

    // 6. Vẽ Overlay Cảnh báo Giao thông (nếu có)
    drawTrafficWarningOverlay();

    canvasSprite.pushSprite(0, 0);
}

// ==========================================
// 4. GIAO DIỆN THÔNG TIN HỆ THỐNG (INFO)
// ==========================================
void drawINFO()
{
    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Tiêu đề
    String titleStr = "THÔNG TIN HỆ THỐNG";
    myFont.set_font(FONT_MENU_TITLE);
    uint16_t titleLen = myFont.getLength(titleStr);
    myFont.print(120 - titleLen / 2, 20, titleStr, TFT_GREEN, TFT_BLACK);

    myFont.set_font(FONT_HUD_INFO);

    // 2. Trạng thái kết nối BLE
    String connStr = bleConnected ? "BLE: Đã kết nối ✓" : "BLE: Chờ kết nối...";
    uint16_t connLen = myFont.getLength(connStr);
    myFont.print(120 - connLen / 2, 50, connStr, bleConnected ? TFT_GREEN : TFT_YELLOW, TFT_BLACK);

    // 3. Đồng bộ thời gian
    String syncStr = timeSynced ? "Giờ: Đã đồng bộ ✓" : "Giờ: Chưa đồng bộ !";
    uint16_t syncLen = myFont.getLength(syncStr);
    myFont.print(120 - syncLen / 2, 72, syncStr, timeSynced ? TFT_CYAN : TFT_ORANGE, TFT_BLACK);

    // 4. Pin điện thoại
    if (phoneBatteryLevel >= 0)
    {
        drawPhoneBatteryIcon(canvasSprite, 42, 92, phoneBatteryLevel, phoneBatteryCharging);
        char phoneBuf[24];
        snprintf(phoneBuf, sizeof(phoneBuf), "Phone: %d%%%s",
                 phoneBatteryLevel, phoneBatteryCharging ? " ⚡" : "");
        uint16_t pLen = myFont.getLength(phoneBuf);
        myFont.print(120 - pLen / 2 + 12, 92, phoneBuf,
                     phoneBatteryLevel < 20 ? TFT_RED : TFT_WHITE, TFT_BLACK);
    }

    // 5. Điện áp ắc quy xe đạp
    char batBuf[32];
    snprintf(batBuf, sizeof(batBuf), "Batt: %.1fV", batteryVoltage);
    uint16_t batLen = myFont.getLength(batBuf);
    myFont.print(120 - batLen / 2, 115, batBuf, TFT_CYAN, TFT_BLACK);

    // 6. Thời tiết
    if (weatherIcon.length() > 0)
    {
        drawWeatherIcon(canvasSprite, weatherIcon, 50, 140);
        char wxBuf[24];
        snprintf(wxBuf, sizeof(wxBuf), "%.0f°C  %s", weatherTemp, weatherIcon.c_str());
        myFont.print(70, 133, wxBuf, TFT_YELLOW, TFT_BLACK);
    }

    // 7. Cache icon
    char cacheBuf[32];
    snprintf(cacheBuf, sizeof(cacheBuf), "Icon Cache: %d/50", cacheSize);
    uint16_t cacheLen = myFont.getLength(cacheBuf);
    myFont.print(120 - cacheLen / 2, 160, cacheBuf, TFT_WHITE, TFT_BLACK);

    // 8. Phiên bản
    String verStr = "TYMAP v" + String(FW_VERSION_STR) + " | GC9A01";
    uint16_t verLen = myFont.getLength(verStr);
    myFont.print(120 - verLen / 2, 180, verStr, TFT_DARKGREY, TFT_BLACK);

    // Vẽ Overlay Cảnh báo Giao thông (nếu có)
    drawTrafficWarningOverlay();

    canvasSprite.pushSprite(0, 0);
}

// ==========================================
// 5. MÀN HÌNH KHỞI ĐỘNG (LOGO & LOADING BAR)
// ==========================================
void drawLogoWithLoadingBar(unsigned long currentTime, unsigned long startTime, unsigned long introDuration)
{
    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Vẽ logo RGB565 150x150 — push trực tiếp, không cần PSRAM
    canvasSprite.setSwapBytes(true);
    canvasSprite.pushImage(45, 15, 150, 150,
                           (uint16_t *)epd_bitmap_150x150);
    canvasSprite.setSwapBytes(false);

    // 2. Tính tiến trình thanh loading
    float progress = (float)(currentTime - startTime) / introDuration;
    if (progress > 1.0f)
        progress = 1.0f;
    int barWidth = progress * 160;

    // 3. Vẽ chữ "loading..."
    myFont.set_font(vietnamtimes6x2);
    String loadStr = "loading...";
    uint16_t loadLen = myFont.getLength(loadStr);
    myFont.print(120 - loadLen / 2, 175, loadStr, TFT_WHITE, TFT_BLACK);

    // 4. Vẽ thanh loading
    canvasSprite.fillRect(40, 195, barWidth, 4, TFT_GREEN);

    canvasSprite.pushSprite(0, 0);
}

// Hàm bổ trợ vẽ text tự động xuống dòng ôm theo khung tròn
void printWrappedText(int startY, const String &text, uint16_t color, uint16_t bgColor, int maxLineWidth)
{
    int startIdx = 0;
    int y = startY;
    myFont.set_font(FONT_NOTIF_BODY);

    while (startIdx < text.length() && y < 200)
    {
        int testLen = 1;
        while (startIdx + testLen <= text.length())
        {
            String testStr = text.substring(startIdx, startIdx + testLen);
            if (myFont.getLength(testStr) > maxLineWidth)
            {
                testLen--;
                break;
            }
            testLen++;
        }

        if (testLen <= 0)
            testLen = 1;

        int actualLen = testLen;
        if (startIdx + testLen < text.length())
        {
            int spaceIdx = text.substring(startIdx, startIdx + testLen).lastIndexOf(' ');
            if (spaceIdx > 0)
            {
                actualLen = spaceIdx;
            }
        }

        String line = text.substring(startIdx, startIdx + actualLen);
        line.trim();

        uint16_t lineLen = myFont.getLength(line);
        myFont.print(120 - lineLen / 2, y, line, color, bgColor);

        startIdx += actualLen;
        while (startIdx < text.length() && text[startIdx] == ' ')
        {
            startIdx++;
        }

        y += 20;
    }
}

uint16_t getAppAccentColor(const String &appName)
{
    String lower = appName;
    lower.toLowerCase();
    if (lower.indexOf("zalo") >= 0)
        return 0x1C9F; // Bright Zalo Blue
    if (lower.indexOf("messenger") >= 0 || lower.indexOf("facebook") >= 0)
        return 0xD81F; // Magenta/Purple
    if (lower.indexOf("sms") >= 0 || lower.indexOf("tin nhắn") >= 0 || lower.indexOf("message") >= 0)
        return 0x07E0; // Emerald Green
    if (lower.indexOf("phone") >= 0 || lower.indexOf("call") >= 0 || lower.indexOf("cuộc gọi") >= 0)
        return 0xF800; // Red
    if (lower.indexOf("maps") >= 0 || lower.indexOf("bản đồ") >= 0)
        return 0x07FF; // Sky Blue
    return 0x07FF;     // Default Cyan
}

// ==========================================
// 6. GIAO DIỆN HIỂN THỊ THÔNG BÁO (N1: FLOATING CARD / N2: FULLSCREEN FOCUS)
// ==========================================
void drawNOTIF()
{
    canvasSprite.fillSprite(TFT_BLACK);

    if (notifCount == 0)
    {
        canvasSprite.fillRoundRect(20, 50, 200, 140, 16, color565(15, 23, 42));
        canvasSprite.drawRoundRect(20, 50, 200, 140, 16, TFT_CYAN);

        int cx = 120, cy = 105;
        canvasSprite.drawRect(cx - 18, cy - 14, 36, 26, TFT_WHITE);
        canvasSprite.drawLine(cx - 18, cy - 14, cx, cy, TFT_WHITE);
        canvasSprite.drawLine(cx + 18, cy - 14, cx, cy, TFT_WHITE);

        String emptyStr = "Không có thông báo mới";
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t emptyLen = myFont.getLength(emptyStr);
        myFont.print(120 - emptyLen / 2, 145, emptyStr, TFT_WHITE, color565(15, 23, 42));
    }
    else
    {
        String appName = notifList[notifViewIndex].app;
        if (appName.length() == 0)
            appName = "Thông báo";
        uint16_t accent = getAppAccentColor(appName);
        String senderTitle = notifList[notifViewIndex].title;
        if (senderTitle.length() == 0)
            senderTitle = appName;

        // ---------------- MẪU N2-ALPHA: BIG HEADER APP PILL (THUẦN NOTIF DUY NHẤT) ----------------
        // 1. Khung Pill Tên App ở đỉnh (cx=120, y=18, w=170, h=36)
        uint16_t cardBg = color565(15, 23, 42);
        canvasSprite.fillRoundRect(35, 18, 170, 36, 18, cardBg);
        canvasSprite.drawRoundRect(35, 18, 170, 36, 18, accent);

        // Icon Badge Tròn nhỏ bên trong Pill Header
        canvasSprite.fillCircle(53, 36, 11, accent);
        myFont.set_font(vietnamtimes12);
        String initial = appName.substring(0, 1);
        initial.toUpperCase();
        myFont.print(48, 30, initial, TFT_WHITE, accent);

        // Tên Ứng Dụng inside Header Pill
        myFont.set_font(vietnamtimes12);
        myFont.print(72, 30, appName, accent, cardBg);

        // 2. Tên Người Gửi Chữ Đậm ở trung tâm (y = 68)
        myFont.set_font(FONT_NOTIF_TITLE);
        uint16_t tLen = myFont.getLength(senderTitle);
        myFont.print(120 - tLen / 2, 68, senderTitle, TFT_WHITE, TFT_BLACK);

        // 3. Vạch phân cách Accent Horizontal Line (y = 92)
        canvasSprite.drawFastHLine(35, 92, 170, accent);

        // 4. Nội dung tin nhắn Tiếng Việt tự động xuống dòng (y = 104)
        printWrappedText(104, notifList[notifViewIndex].msg, color565(226, 232, 240), TFT_BLACK, 175);

        // 5. Thanh đếm trang & phím bấm ở đáy (y = 186)
        char pageBuf[32];
        snprintf(pageBuf, sizeof(pageBuf), "[%d/%d] MODE: Xem tiếp", notifViewIndex + 1, notifCount);
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t pLen = myFont.getLength(pageBuf);
        myFont.print(120 - pLen / 2, 186, pageBuf, color565(148, 163, 184), TFT_BLACK);
    }

    canvasSprite.pushSprite(0, 0);
}

// ==========================================
// CUSTOM THEME LAYOUT CONFIG PARSER (THEME STUDIO BUILDER)
// ==========================================
bool hasCustomLayoutConfig = false;

void parseAndApplyLayoutJson(const String &jsonStr)
{
    if (jsonStr.length() == 0)
        return;

    if (jsonStr.indexOf("s4") != -1)
        statusStyle = 0;
    else if (jsonStr.indexOf("s5") != -1)
        statusStyle = 1;
    else if (jsonStr.indexOf("s3") != -1)
        statusStyle = 2;
    else if (jsonStr.indexOf("s6") != -1)
        statusStyle = 3;
    else if (jsonStr.indexOf("s7") != -1)
        statusStyle = 4;
    else if (jsonStr.indexOf("n1") != -1)
        notifStyle = 0;
    else if (jsonStr.indexOf("n2") != -1)
        notifStyle = 1;
    else if (jsonStr.indexOf("mh1") != -1)
        mapHudStyle = 0;
    else if (jsonStr.indexOf("mh2") != -1)
        mapHudStyle = 1;
    else if (jsonStr.indexOf("mh3") != -1)
        mapHudStyle = 2;
    else if (jsonStr.indexOf("mh4") != -1)
        mapHudStyle = 3;
    else if (jsonStr.indexOf("mh5") != -1)
        mapHudStyle = 4;
    else if (jsonStr.indexOf("mh6") != -1 || jsonStr.indexOf("galaxy") != -1)
        mapHudStyle = 5;

    hasCustomLayoutConfig = true;
    Serial.printf("GUI: Applied custom layout JSON config via BLE (statusStyle=%d, notifStyle=%d, mapHudStyle=%d)\n", (int)statusStyle, (int)notifStyle, (int)mapHudStyle);
}

// ==========================================
// 8. GIAO DIỆN NẠP FIRMWARE BLE OTA (ESP32 GC9A01)
// ==========================================
void drawOtaProgressScreen()
{
    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Vòng tròn trang trí viền ngoài (Tech Cyan)
    canvasSprite.drawCircle(120, 120, 118, color565(15, 30, 45));
    canvasSprite.drawCircle(120, 120, 116, color565(30, 58, 85));

    int pct = 0;
    if (otaExpectedSize > 0)
    {
        pct = (int)(((uint64_t)otaWritten * 100) / otaExpectedSize);
        if (pct > 100)
            pct = 100;
    }

    // 2. Vòng tròn tiến trình dạng Arc
    int arcAngle = (pct * 360) / 100;
    if (arcAngle > 0)
    {
        drawArcSegment(canvasSprite, 120, 120, 112, -90, -90 + arcAngle, color565(0, 229, 255));
    }

    // 3. Tiêu đề phía trên (Y = 32)
    myFont.set_font(FONT_NOTIF_TITLE);
    const char *titleText = "FIRMWARE OTA";
    uint16_t tLen = myFont.getLength(titleText);
    myFont.print(120 - tLen / 2, 32, titleText, color565(0, 229, 255), TFT_BLACK);

    // 4. Số phần trăm khổng lồ ở trung tâm (Y = 75)
    char pctBuf[16];
    snprintf(pctBuf, sizeof(pctBuf), "%d%%", pct);
    myFont.set_font(FONT_CLOCK);
    uint16_t pctLen = myFont.getLength(pctBuf);
    myFont.print(120 - pctLen / 2, 75, pctBuf, (pct >= 100) ? color565(34, 197, 94) : TFT_WHITE, TFT_BLACK);

    // 5. Thanh ProgressBar dạng Pill nằm ngang (Y = 138)
    int barW = 140;
    int barH = 8;
    int barX = 120 - barW / 2;
    int barY = 138;
    canvasSprite.fillRoundRect(barX, barY, barW, barH, 4, color565(30, 41, 59));
    if (pct > 0)
    {
        int fillW = (barW * pct) / 100;
        if (fillW < 6)
            fillW = 6;
        canvasSprite.fillRoundRect(barX, barY, fillW, barH, 4, (pct >= 100) ? color565(34, 197, 94) : color565(0, 229, 255));
    }
    canvasSprite.drawRoundRect(barX, barY, barW, barH, 4, color565(51, 65, 85));

    // 6. Thông số KB chi tiết & Trạng thái (Y = 158)
    char bytesBuf[32];
    snprintf(bytesBuf, sizeof(bytesBuf), "%d / %d KB", (int)(otaWritten / 1024), (int)(otaExpectedSize / 1024));
    myFont.set_font(FONT_STATUS_INFO);
    uint16_t bLen = myFont.getLength(bytesBuf);
    myFont.print(120 - bLen / 2, 158, bytesBuf, color565(148, 163, 184), TFT_BLACK);

    // 7. Nhắc nhở ở đáy (Y = 188)
    const char *bottomMsg = (pct >= 100) ? "HOAN TAT! REBOOT..." : "DANG NAP QUA BLE...";
    uint16_t msgColor = (pct >= 100) ? color565(34, 197, 94) : color565(251, 146, 60);
    uint16_t mLen = myFont.getLength(bottomMsg);
    myFont.print(120 - mLen / 2, 188, bottomMsg, msgColor, TFT_BLACK);

    canvasSprite.pushSprite(0, 0);
}
