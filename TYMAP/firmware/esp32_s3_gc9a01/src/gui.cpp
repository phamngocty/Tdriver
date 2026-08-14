#include "gui.h"
#include "logo.h"

bool showMapHudCard = true;
uint8_t statusStyle = 0;          // 0=S4 Cyber Dual Gauges (Mặc định), 1=S5 Classic Analog, 2=S3 Dual Pill
uint8_t notifStyle = 1;           // 0=N1 Floating Card 3D, 1=N2 Fullscreen Focus (THUẦN NOTIF - Mặc định)
uint8_t settingCategoryIndex = 0; // 0=Mặt đồng hồ (statusStyle), 1=HUD Bản đồ (mapHudStyle), 2=Thông báo (notifStyle)

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
    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Vẽ chỉ dẫn (dir) và ETA/Giờ (màu trắng) ở đỉnh màn hình
    myFont.set_font(FONT_HUD_INFO);
    String part1 = (ete.length() > 0) ? ete : ((totalDist.length() > 0) ? totalDist : "20 min");
    String part2 = "  ·  " + ((eta.length() > 0) ? eta : rtc.getTime("%H:%M"));
    uint16_t len1 = myFont.getLength(part1);
    uint16_t len2 = myFont.getLength(part2);
    uint16_t totalLen = len1 + len2;
    int startX = 120 - totalLen / 2;

    myFont.print(startX, 22, part1, TFT_SKYBLUE, TFT_BLACK);
    myFont.print(startX + len1, 22, part2, TFT_WHITE, TFT_BLACK);

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
    uint16_t streetLen = myFont.getLength(nextStreet);
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

        myFont.print(120 - visibleWidth / 2 - scrollX, 135, nextStreet, TFT_WHITE, TFT_BLACK);

        clipMinX = 0;
        clipMaxX = 240;
        clipMinY = 0;
        clipMaxY = 240;
    }
    else
    {
        myFont.print(120 - streetLen / 2, 135, nextStreet, TFT_WHITE, TFT_BLACK);
    }

    // 4. Vẽ khoảng cách rẽ ở dưới
    myFont.set_font(FONT_HUD_DIST);
    uint16_t distLen = myFont.getLength(distToNext);
    myFont.print(120 - distLen / 2, 170, distToNext, TFT_WHITE, TFT_BLACK);

    // 5. Ô hiển thị Tốc độ GPS (km/h) ở đáy
    char speedBuf[16];
    snprintf(speedBuf, sizeof(speedBuf), "%d km/h", gpsSpeed);
    myFont.set_font(FONT_HUD_INFO);
    uint16_t spdLen = myFont.getLength(speedBuf);
    uint16_t badgeW = spdLen + 16;
    if (badgeW < 64)
        badgeW = 64;
    canvasSprite.fillRoundRect(120 - badgeW / 2, 205, badgeW, 22, 10, color565(30, 41, 59));
    canvasSprite.drawRoundRect(120 - badgeW / 2, 205, badgeW, 22, 10, TFT_GREEN);
    myFont.print(120 - spdLen / 2, 209, speedBuf, TFT_GREEN, color565(30, 41, 59));

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
    else if (mapHudStyle == 1)
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
        uint16_t streetLen = myFont.getLength(nextStreet);
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

            myFont.print(50 - scrollX, 207, nextStreet, TFT_WHITE, cardBgColor);

            clipMinX = 0;
            clipMaxX = 240;
            clipMinY = 0;
            clipMaxY = 240;
        }
        else
        {
            myFont.print(50, 207, nextStreet, TFT_WHITE, cardBgColor);
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
        uint16_t dLen = myFont.getLength(distToNext);
        myFont.print(115 - dLen / 2, 33, distToNext, accent, cardBgColor);

        // 3. Thanh Pill Tên đường tối giản ở Đáy (x=30, y=200, w=180, h=28, r=14)
        canvasSprite.fillRoundRect(30, 200, 180, 28, 14, cardBgColor);
        canvasSprite.drawRoundRect(30, 200, 180, 28, 14, color565(51, 65, 85));

        myFont.set_font(vietnamtimes12);
        uint16_t streetLen = myFont.getLength(nextStreet);
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

            myFont.print(40 - scrollX, 206, nextStreet, TFT_WHITE, cardBgColor);

            clipMinX = 0;
            clipMaxX = 240;
            clipMinY = 0;
            clipMaxY = 240;
        }
        else
        {
            myFont.print(120 - streetLen / 2, 206, nextStreet, TFT_WHITE, cardBgColor);
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
    else
    {
        // ================= MẪU MH1: COMPACT FLOATING PILL (HIỂN THỊ 85% BẢN ĐỒ) =================
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
        myFont.print(70, 186, distToNext, TFT_GREEN, cardBgColor);

        // Tên đường chỉ dẫn Chữ Trắng Tự Cuộn Marquee ở Dòng Dưới (y = 203)
        myFont.set_font(vietnamtimes12);
        uint16_t streetLen = myFont.getLength(nextStreet);
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

            myFont.print(70 - scrollX, 203, nextStreet, TFT_WHITE, cardBgColor);

            clipMinX = 0;
            clipMaxX = 240;
            clipMinY = 0;
            clipMaxY = 240;
        }
        else
        {
            myFont.print(70, 203, nextStreet, TFT_WHITE, cardBgColor);
        }
    }
}

// ==========================================
// 1B. CÁC THÀNH PHẦN ĐÈ LÊN BẢN ĐỒ (MAP OVERLAY) - PHONG CÁCH GALAXY WATCH 7
// ==========================================
void drawMapOverlay()
{
    // 1. Vẽ dải nền tối mờ ở trên đỉnh màn hình để chữ dễ đọc
    canvasSprite.fillRoundRect(60, 10, 120, 24, 6, TFT_BLACK);
    canvasSprite.drawRoundRect(60, 10, 120, 24, 6, TFT_WHITE);

    // Vẽ ETE và ETA/Giờ ở đỉnh
    myFont.set_font(FONT_HUD_INFO);
    String part1 = (ete.length() > 0) ? ete : ((totalDist.length() > 0) ? totalDist : "20 min");
    String part2 = "  ·  " + ((eta.length() > 0) ? eta : rtc.getTime("%H:%M"));
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

    // Vẽ Overlay Cảnh báo Giao thông (nếu có)
    drawTrafficWarningOverlay();

    canvasSprite.pushSprite(0, 0);
}

// ==========================================
// CẢNH BÁO GIAO THÔNG (SPEED LIMIT & CAMERA PHẠT NGUỘI) - POPUP OVERLAY 3 GIÂY
// ==========================================
void drawTrafficWarningOverlay()
{
    // Nếu không có cảnh báo nào đang hoạt động thì không vẽ Popup
    if (!isTrafficWarningActive)
        return;

    int cx = 120; // Tâm màn hình GC9A01 (240x240)
    int cy = 120;
    int r = 48; // Bán kính hình tròn biển báo giao thông

    // 1. Vẽ vòng tròn ngoài màu đỏ nổi bật (Viền dày 4px chuẩn biển báo giao thông)
    canvasSprite.fillCircle(cx, cy, r + 4, TFT_RED);

    // 2. Vẽ vòng tròn viền trong màu trắng
    canvasSprite.fillCircle(cx, cy, r + 1, TFT_WHITE);

    // 3. Vẽ nền trong hình tròn màu trắng
    canvasSprite.fillCircle(cx, cy, r - 4, TFT_WHITE);

    if (trafficWarningType == 0x02) // Biển giới hạn tốc độ (Speed Limit)
    {
        // 4. In số tốc độ giới hạn (ví dụ: 50, 60) màu đen in đậm chính giữa
        myFont.set_font(FONT_CLOCK);
        String valStr = String(trafficWarningValue);
        uint16_t txtLen = myFont.getLength(valStr);
        myFont.print(cx - txtLen / 2, cy - 16, valStr, TFT_BLACK, TFT_WHITE);

        // 5. In nhãn "km/h" màu xám đen phía dưới
        myFont.set_font(FONT_MENU_OPTION);
        String unitStr = "km/h";
        uint16_t uLen = myFont.getLength(unitStr);
        myFont.print(cx - uLen / 2, cy + 20, unitStr, TFT_DARKGREY, TFT_WHITE);
    }
    else if (trafficWarningType == 0x01) // Camera phạt nguội (Speed Camera)
    {
        // 4. In tiêu đề "CAM" màu đỏ nổi bật
        myFont.set_font(FONT_NOTIF_TITLE);
        String camTitle = "CAM";
        uint16_t cLen = myFont.getLength(camTitle);
        myFont.print(cx - cLen / 2, cy - 22, camTitle, TFT_RED, TFT_WHITE);

        // 5. In nhãn "PHẠT NGUỘI" màu đen phía dưới
        myFont.set_font(FONT_HUD_STREET);
        String alertText = "PHẠT NGUỘI";
        uint16_t aLen = myFont.getLength(alertText);
        myFont.print(cx - aLen / 2, cy + 6, alertText, TFT_BLACK, TFT_WHITE);
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
        // ---------------- S6: CLASSIC ANALOG/DIGITAL ----------------
        // 1. Date at top (y=30)
        const char *dayOfWeekVi[] = {"CN", "T2", "T3", "T4", "T5", "T6", "T7"};
        int dow = rtc.getDayofWeek();
        if (dow < 0 || dow > 6)
            dow = 0;
        String dateStr = String(dayOfWeekVi[dow]) + ", " + rtc.getTime("%d/%m");
        myFont.set_font(vietnamtimes12);
        uint16_t dateLen = myFont.getLength(dateStr);
        myFont.print(120 - dateLen / 2, 30, dateStr, TFT_CYAN, TFT_BLACK);

        // 2. Time at center-top (y=70)
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(FONT_CLOCK);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 70, timeStr, TFT_WHITE, TFT_BLACK);

        // 3. Dual Battery Bars (y=140)
        // Bike Battery
        canvasSprite.drawRect(50, 140, 50, 12, color565(71, 85, 105));
        canvasSprite.fillRect(52, 142, (int)((batteryVoltage / 15.0f) * 46), 8, TFT_GREEN);
        myFont.set_font(vietnamtimes12);
        myFont.print(50, 155, "XE", TFT_GREEN, TFT_BLACK);

        // Phone Battery
        canvasSprite.drawRect(140, 140, 50, 12, color565(71, 85, 105));
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        canvasSprite.fillRect(142, 142, (int)((pBat / 100.0f) * 46), 8, TFT_CYAN);
        myFont.print(140, 155, "ĐT", TFT_CYAN, TFT_BLACK);

        // 4. Brand at bottom (y=200)
        myFont.set_font(vietnamtimes12);
        myFont.print(95, 200, "TYMAP", color565(71, 85, 105), TFT_BLACK);
    }
    else if (statusStyle == 4)
    {
        // ---------------- S7: SPORT ACTIVITY ----------------
        // 1. Time at top (y=25) in Cyan
        String timeStr = rtc.getTime("%H:%M");
        myFont.set_font(vietnamtimes12);
        uint16_t timeLen = myFont.getLength(timeStr);
        myFont.print(120 - timeLen / 2, 25, timeStr, TFT_CYAN, TFT_BLACK);

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
    else
    {
        // ---------------- S4a: CYBER DUAL ARC GAUGES (MATCH EXACT WEB SKETCH) ----------------
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
        statusName = "S6: Classic Digital";
        break;
    case 4:
        statusName = "S7: Sport Dynamic";
        break;
    default:
        statusName = "S4: Cyber Dual";
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
    snprintf(cacheBuf, sizeof(cacheBuf), "Icon Cache: %d/50", staticIconIndex);
    uint16_t cacheLen = myFont.getLength(cacheBuf);
    myFont.print(120 - cacheLen / 2, 160, cacheBuf, TFT_WHITE, TFT_BLACK);

    // 8. Phiên bản
    String verStr = "TYMAP v1.0.0 | GC9A01";
    uint16_t verLen = myFont.getLength(verStr);
    myFont.print(120 - verLen / 2, 180, verStr, TFT_DARKGREY, TFT_BLACK);

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

    hasCustomLayoutConfig = true;
    Serial.printf("GUI: Applied custom layout JSON config via BLE (statusStyle=%d, notifStyle=%d, mapHudStyle=%d)\n", (int)statusStyle, (int)notifStyle, (int)mapHudStyle);
}
