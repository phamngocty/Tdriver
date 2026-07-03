#include "gui.h"
#include "logo.h"

// Vẽ icon 1bpp monochrome custom (phóng to scale=2) LÊN SPRITE
void drawCustomIcon(TFT_eSprite &sprite, const uint8_t *bitmap, int xOffset, int yOffset, int scale)
{
    for (int y = 0; y < 48; y++)
    {
        for (int x = 0; x < 48; x++)
        {
            int byteIdx = (y * 48 + x) / 8;
            int bitPos = 7 - (x % 8);
            bool isPixel = (bitmap[byteIdx] & (1 << bitPos)) != 0;
            uint16_t color = isPixel ? TFT_WHITE : TFT_BLACK;
            sprite.fillRect(xOffset + x * scale, yOffset + y * scale, scale, scale, color);
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
    if (hasCustomIcon) {
        drawCustomIcon(canvasSprite, customIconBitmap, 72, 36, 2);
    } else {
        int ax = 120, ay = 84; // tâm
        switch (navDirIdx) {
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
            case 7: case 8: // uturn
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
        if (scrollMs > 1000) scrollX = (scrollMs - 1000) / 30;

        myFont.print(120 - visibleWidth / 2 - scrollX, 135, nextStreet, TFT_WHITE, TFT_BLACK);

        clipMinX = 0; clipMaxX = 240;
        clipMinY = 0; clipMaxY = 240;
    }
    else
    {
        myFont.print(120 - streetLen / 2, 135, nextStreet, TFT_WHITE, TFT_BLACK);
    }

    // 4. Vẽ khoảng cách rẽ ở dưới
    myFont.set_font(FONT_HUD_DIST);
    uint16_t distLen = myFont.getLength(distToNext);
    myFont.print(120 - distLen / 2, 170, distToNext, TFT_WHITE, TFT_BLACK);

    // 5. Vẽ nút bấm tròn chứa mũi tên đi thẳng chỉ hướng di chuyển ở dưới cùng
    canvasSprite.fillCircle(120, 215, 15, TFT_BLACK);
    canvasSprite.drawCircle(120, 215, 15, TFT_WHITE);

    // Vẽ mũi tên chỉ thẳng hướng lên nhỏ bên trong nút bấm
    int acx = 120, acy = 215;
    canvasSprite.drawLine(acx, acy + 6, acx, acy - 6, TFT_WHITE);
    canvasSprite.drawLine(acx, acy - 6, acx - 4, acy - 2, TFT_WHITE);
    canvasSprite.drawLine(acx, acy - 6, acx + 4, acy - 2, TFT_WHITE);

    canvasSprite.pushSprite(0, 0);
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

    canvasSprite.pushSprite(0, 0);
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

    // Biểu tượng sạc: "⚡" = tia sét nhỏ ở giữa
    if (charging)
    {
        sprite.fillTriangle(x + 12, y + 1, x + 8, y + 6, x + 11, y + 6, TFT_WHITE);
        sprite.fillTriangle(x + 8, y + 6, x + 12, y + 10, x + 12, y + 5, TFT_WHITE);
    }
}

// ==========================================
// 2. GIAO DIỆN TRẠNG THÁI (STATUS) - GALAXY WATCH STYLE
// ==========================================
void drawSTATUS()
{
    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Vẽ đồng hồ giờ (Giờ:Phút:Giây) với FONT_CLOCK to, màu trắng nổi bật
    String timeStr = rtc.getTime("%H:%M:%S");
    myFont.set_font(FONT_CLOCK);
    uint16_t timeLen = myFont.getLength(timeStr);
    myFont.print(120 - timeLen / 2, 25, timeStr, TFT_WHITE, TFT_BLACK);

    // Hiển thị dấu "!" màu cam nếu chưa đồng bộ thời gian từ điện thoại
    if (!timeSynced)
    {
        myFont.set_font(FONT_HUD_INFO);
        String excl = "!";
        myFont.print(120 + timeLen / 2 + 2, 25, excl, TFT_ORANGE, TFT_BLACK);
    }

    // 2. Vẽ ngày tháng bằng font vietnamtimes12 tinh tế
    String dateStr = rtc.getTime("%A, %d/%m/%Y");
    myFont.set_font(vietnamtimes12);
    uint16_t dateLen = myFont.getLength(dateStr);
    myFont.print(120 - dateLen / 2, 75, dateStr, TFT_WHITE, TFT_BLACK);

    // 3. Vẽ thời tiết (Sử dụng Icon và chữ kết hợp)
    if (weatherIcon.length() > 0)
    {
        drawWeatherIcon(canvasSprite, weatherIcon, 50, 110);
        char wxBuf[32];
        snprintf(wxBuf, sizeof(wxBuf), "%.0f°C  %s", weatherTemp, weatherIcon.c_str());
        myFont.set_font(vietnamtimes12);
        myFont.print(70, 103, wxBuf, TFT_YELLOW, TFT_BLACK);
    }
    else
    {
        char tempBuf[48];
        snprintf(tempBuf, sizeof(tempBuf), "Thời tiết: Chưa cập nhật");
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t tempLen = myFont.getLength(tempBuf);
        myFont.print(120 - tempLen / 2, 110, tempBuf, TFT_YELLOW, TFT_BLACK);
    }

    // 4. Vẽ pin ắc quy xe đạp (Chữ màu xanh lá cây mát mắt ở dòng tiếp theo)
    char batBuf[32];
    snprintf(batBuf, sizeof(batBuf), "Ắc quy: %.1fV", batteryVoltage);
    myFont.set_font(FONT_STATUS_INFO);
    uint16_t batLen = myFont.getLength(batBuf);
    myFont.print(120 - batLen / 2, 140, batBuf, TFT_GREEN, TFT_BLACK);

    // 5. Vẽ pin điện thoại (Sử dụng Icon và chữ tương tự drawINFO)
    if (phoneBatteryLevel >= 0)
    {
        drawPhoneBatteryIcon(canvasSprite, 42, 170, phoneBatteryLevel, phoneBatteryCharging);
        char phoneBuf[24];
        snprintf(phoneBuf, sizeof(phoneBuf), "Phone: %d%%%s",
                 phoneBatteryLevel, phoneBatteryCharging ? " ⚡" : "");
        myFont.set_font(vietnamtimes12);
        uint16_t pLen = myFont.getLength(phoneBuf);
        myFont.print(120 - pLen / 2 + 12, 170, phoneBuf,
                     phoneBatteryLevel < 20 ? TFT_RED : TFT_WHITE, TFT_BLACK);
    }

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

// Mở menu chồng chế độ LÊN SPRITE (Dạng Cung Tròn 5 hướng)
void drawMenuOverlay()
{
    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Vẽ viền tròn ngoài cùng màn hình
    canvasSprite.drawCircle(120, 120, 115, TFT_WHITE);

    // 2. Vẽ 5 đường thẳng phân chia góc (18, 90, 162, 234, 306 độ)
    float angles[] = {18, 90, 162, 234, 306};
    for (int i = 0; i < 5; i++)
    {
        float rad = angles[i] * DEG_TO_RAD;
        int xStart = 120 + 42 * cos(rad);
        int yStart = 120 + 42 * sin(rad);
        int xEnd = 120 + 115 * cos(rad);
        int yEnd = 120 + 115 * sin(rad);
        canvasSprite.drawLine(xStart, yStart, xEnd, yEnd, TFT_WHITE);
    }

    // 3. Vẽ dải cung tròn xanh lá (Green Arc Segment) bao viền ngoài cung được chọn
    if (menuSelectedIndex == 0)
    { // HUD (North): 234° đến 306°
        drawArcSegment(canvasSprite, 120, 120, 114, 234, 306, TFT_GREEN);
    }
    else if (menuSelectedIndex == 1)
    { // BẢN ĐỒ (East-North-East): 306° đến 378°
        drawArcSegment(canvasSprite, 120, 120, 114, 306, 378, TFT_GREEN);
    }
    else if (menuSelectedIndex == 2)
    { // STATUS (South-East): 18° đến 90°
        drawArcSegment(canvasSprite, 120, 120, 114, 18, 90, TFT_GREEN);
    }
    else if (menuSelectedIndex == 3)
    { // INFO (South-West): 90° đến 162°
        drawArcSegment(canvasSprite, 120, 120, 114, 90, 162, TFT_GREEN);
    }
    else if (menuSelectedIndex == 4)
    { // NOTIF (West-North-West): 162° đến 234°
        drawArcSegment(canvasSprite, 120, 120, 114, 162, 234, TFT_GREEN);
    }

    // 4. Vẽ các Icon tại các tâm cung
    int xs[] = {120, 191, 164, 76, 49};
    int ys[] = {45, 97, 181, 181, 97};

    for (int i = 0; i < 5; i++)
    {
        bool isSelected = (i == menuSelectedIndex);
        int cx = xs[i];
        int cy = ys[i];

        uint16_t iconColor = isSelected ? TFT_GREEN : TFT_WHITE;

        if (i == 0)
        { // HUD: Mũi tên dẫn đường hướng lên
            canvasSprite.drawLine(cx, cy + 10, cx, cy - 10, iconColor);
            canvasSprite.drawLine(cx, cy - 10, cx - 6, cy - 4, iconColor);
            canvasSprite.drawLine(cx, cy - 10, cx + 6, cy - 4, iconColor);
        }
        else if (i == 1)
        { // MAP: Bản đồ gấp khúc
            canvasSprite.drawRect(cx - 9, cy - 9, 6, 18, iconColor);
            canvasSprite.drawRect(cx - 3, cy - 7, 6, 18, iconColor);
            canvasSprite.drawRect(cx + 3, cy - 9, 6, 18, iconColor);
        }
        else if (i == 2)
        { // STATUS: Đồng hồ
            canvasSprite.drawCircle(cx, cy, 10, iconColor);
            canvasSprite.drawLine(cx, cy, cx, cy - 6, iconColor);
            canvasSprite.drawLine(cx, cy, cx + 5, cy, iconColor);
        }
        else if (i == 3)
        { // INFO: Chữ i
            canvasSprite.drawCircle(cx, cy - 6, 2, iconColor);
            canvasSprite.drawLine(cx, cy - 2, cx, cy + 6, iconColor);
            canvasSprite.drawLine(cx - 3, cy - 2, cx + 3, cy - 2, iconColor);
            canvasSprite.drawLine(cx - 3, cy + 6, cx + 3, cy + 6, iconColor);
        }
        else if (i == 4)
        { // NOTIF: Envelope
            canvasSprite.drawRect(cx - 8, cy - 6, 16, 12, iconColor);
            canvasSprite.drawLine(cx - 8, cy - 6, cx, cy, iconColor);
            canvasSprite.drawLine(cx + 8, cy - 6, cx, cy, iconColor);
        }
    }

    // 5. Vẽ vòng tròn trung tâm
    canvasSprite.fillCircle(120, 120, 42, TFT_BLACK);
    canvasSprite.drawCircle(120, 120, 42, TFT_WHITE);

    // 6. In tên Tiếng Việt chế độ đang chọn ở tâm vòng tròn
    String selectedName = "";
    if (menuSelectedIndex == 0)
        selectedName = "DẪN ĐƯỜNG";
    else if (menuSelectedIndex == 1)
        selectedName = "BẢN ĐỒ";
    else if (menuSelectedIndex == 2)
        selectedName = "STATUS";
    else if (menuSelectedIndex == 3)
        selectedName = "INFO";
    else if (menuSelectedIndex == 4)
        selectedName = "THÔNG BÁO";

    myFont.set_font(FONT_MENU_OPTION);
    uint16_t nameLen = myFont.getLength(selectedName);
    myFont.print(120 - nameLen / 2, 114, selectedName, TFT_GREEN, TFT_BLACK);

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

// ==========================================
// 6. GIAO DIỆN HIỂN THỊ THÔNG BÁO (NOTIFICATION SCREEN)
// ==========================================
void drawNOTIF()
{
    canvasSprite.fillSprite(TFT_BLACK);

    // 1. Tiêu đề góc trên
    String titleStr = "THÔNG BÁO";
    myFont.set_font(FONT_MENU_TITLE);
    uint16_t titleLen = myFont.getLength(titleStr);
    myFont.print(120 - titleLen / 2, 25, titleStr, TFT_RED, TFT_BLACK);

    if (notifCount == 0)
    {
        // Vẽ icon envelope trống
        int cx = 120, cy = 110;
        canvasSprite.drawRect(cx - 20, cy - 15, 40, 30, TFT_DARKGREY);
        canvasSprite.drawLine(cx - 20, cy - 15, cx, cy, TFT_DARKGREY);
        canvasSprite.drawLine(cx + 20, cy - 15, cx, cy, TFT_DARKGREY);

        String emptyStr = "Không có thông báo";
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t emptyLen = myFont.getLength(emptyStr);
        myFont.print(120 - emptyLen / 2, 155, emptyStr, TFT_WHITE, TFT_BLACK);
    }
    else
    {
        // 2. Chỉ số trang: e.g. [1/3]
        String indexStr = "[" + String(notifViewIndex + 1) + "/" + String(notifCount) + "]";
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t idxLen = myFont.getLength(indexStr);
        myFont.print(120 - idxLen / 2, 50, indexStr, TFT_SKYBLUE, TFT_BLACK);

        // 3. Tên ứng dụng + Tiêu đề
        String appTitle = notifList[notifViewIndex].app + ": " + notifList[notifViewIndex].title;
        myFont.set_font(FONT_NOTIF_TITLE);
        uint16_t appTitleLen = myFont.getLength(appTitle);
        if (appTitleLen > 200)
        {
            appTitle = appTitle.substring(0, 24) + "...";
            appTitleLen = myFont.getLength(appTitle);
        }
        myFont.print(120 - appTitleLen / 2, 75, appTitle, TFT_YELLOW, TFT_BLACK);

        // 4. Nội dung thông báo tự động xuống dòng
        printWrappedText(105, notifList[notifViewIndex].msg, TFT_WHITE, TFT_BLACK, 190);

        // 5. Hướng dẫn nút bấm
        String footerStr = isNotifPopupTransient ? "Bấm nút: thoát" : "Bấm nút: Thông báo tiếp";
        myFont.set_font(FONT_HUD_INFO);
        uint16_t footerLen = myFont.getLength(footerStr);
        myFont.print(120 - footerLen / 2, 205, footerStr, TFT_DARKGREY, TFT_BLACK);
    }

    canvasSprite.pushSprite(0, 0);
}
