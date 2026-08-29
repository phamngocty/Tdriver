#include "gui.h"
#include "logo.h"

// Biến trạng thái GUI
bool isMenuOpen = false;
unsigned long menuStartTime = 0;
int menuSelectedIndex = 0;
int brightness = 80;

// Vẽ icon rẽ 1bpp monochrome 48x48
void drawCustomIcon(const uint8_t *bitmap, int xOffset, int yOffset, int scale)
{
    if (!bitmap) return;
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

uint8_t statusStyle = 0; // 0=S1 Classic Digital, 1=S2 Dual Gauges, 2=S3 Minimalist, 3=S4 Sport Telemetry
uint8_t hudStyle = 0;    // 0=H1 Classic Boxed, 1=H2 Split Dash, 2=H3 Big Arrow, 3=H4 Racing Bar, 4=H5 Banner, 5=H6 Dual Pill

// -------------------------------------------------------------
// 1. MÀN HÌNH STATUS (ĐỒNG HỒ & THÔNG SỐ XE - 4 KIỂU PHONG CÁCH)
// -------------------------------------------------------------
void drawSTATUS()
{
    u8g2.clearBuffer();

    String timeStr = rtc.getTime("%H:%M");
    String dateStr = rtc.getTime("%d/%m");

    if (statusStyle == 0) {
        // ================= S1: CLASSIC DIGITAL DASH =================
        // Header
        if (bleConnected) {
            u8g2.setDrawColor(1);
            u8g2.drawDisc(4, 5, 2);
        } else {
            u8g2.drawCircle(4, 5, 2);
        }

        myFont.set_font(vietnamtimes12);
        myFont.print(12, 0, dateStr.c_str(), 1, 0);

        if (weatherTemp > -50.0f) {
            char wBuf[16];
            snprintf(wBuf, sizeof(wBuf), "%.0f°C", weatherTemp);
            uint16_t wLen = myFont.getLength(wBuf);
            myFont.print(128 - wLen, 0, wBuf, 1, 0);
        }

        u8g2.drawHLine(0, 12, 128);

        // Đồng hồ trung tâm lớn
        myFont.set_font(FONT_CLOCK);
        uint16_t timeLen = myFont.getLength(timeStr.c_str());
        int timeX = (128 - timeLen) / 2;
        if (timeX < 0) timeX = 0;
        myFont.print(timeX, 15, timeStr.c_str(), 1, 0);

        // Footer
        u8g2.drawHLine(0, 50, 128);
        myFont.set_font(vietnamtimes12);

        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "XE:%.1fV", batteryVoltage);
        myFont.print(0, 52, vBuf, 1, 0);

        char pBuf[16];
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        snprintf(pBuf, sizeof(pBuf), "ĐT:%d%%%s", pBat, phoneBatteryCharging ? "+" : "");
        uint16_t pLen = myFont.getLength(pBuf);
        myFont.print(128 - pLen, 52, pBuf, 1, 0);

    } else if (statusStyle == 1) {
        // ================= S2: DUAL GAUGES (GIỜ & TỐC ĐỘ XE) =================
        // Cột trái: Đồng hồ số
        myFont.set_font(FONT_CLOCK);
        uint16_t tLen = myFont.getLength(timeStr.c_str());
        myFont.print((64 - tLen) / 2, 4, timeStr.c_str(), 1, 0);

        myFont.set_font(vietnamtimes12);
        char dBuf[16];
        snprintf(dBuf, sizeof(dBuf), "%s • %.0f°C", dateStr.c_str(), weatherTemp > -50.0f ? weatherTemp : 28.0f);
        uint16_t dLen = myFont.getLength(dBuf);
        myFont.print((64 - dLen) / 2, 44, dBuf, 1, 0);

        // Đường phân cách giữa
        u8g2.drawVLine(64, 0, 64);

        // Cột phải: Tốc độ xe & Vạch tốc độ
        char sBuf[16];
        snprintf(sBuf, sizeof(sBuf), "%d", gpsSpeed);
        myFont.set_font(FONT_CLOCK);
        uint16_t sLen = myFont.getLength(sBuf);
        myFont.print(64 + (64 - sLen) / 2, 4, sBuf, 1, 0);

        myFont.set_font(vietnamtimes12x2b);
        myFont.print(82, 38, "KM/H", 1, 0);

        u8g2.drawFrame(68, 52, 56, 6);
        int barW = (gpsSpeed * 56) / 120;
        if (barW > 56) barW = 56;
        if (barW > 0) u8g2.drawBox(68, 52, barW, 6);

    } else if (statusStyle == 2) {
        // ================= S3: ELEGANT MINIMALIST =================
        myFont.set_font(FONT_CLOCK);
        uint16_t timeLen = myFont.getLength(timeStr.c_str());
        myFont.print((128 - timeLen) / 2, 2, timeStr.c_str(), 1, 0);

        myFont.set_font(vietnamtimes12);
        char dBuf[32];
        snprintf(dBuf, sizeof(dBuf), "%s | %.0f°C", rtc.getTime("%A, %d/%m").c_str(), weatherTemp > -50.0f ? weatherTemp : 28.0f);
        uint16_t dLen = myFont.getLength(dBuf);
        myFont.print((128 - dLen) / 2, 36, dBuf, 1, 0);

        u8g2.drawHLine(10, 50, 108);
        char vBuf[32];
        int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
        snprintf(vBuf, sizeof(vBuf), "XE: %.1fV  |  ĐT: %d%%", batteryVoltage, pBat);
        uint16_t vLen = myFont.getLength(vBuf);
        myFont.print((128 - vLen) / 2, 52, vBuf, 1, 0);

    } else {
        // ================= S4: SPORT ACTIVITY TELEMETRY =================
        myFont.set_font(vietnamtimes12);
        char topBuf[32];
        snprintf(topBuf, sizeof(topBuf), "%s   %.0f°C", timeStr.c_str(), weatherTemp > -50.0f ? weatherTemp : 28.0f);
        myFont.print(4, 0, topBuf, 1, 0);

        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "%.1fV", batteryVoltage);
        uint16_t vLen = myFont.getLength(vBuf);
        myFont.print(124 - vLen, 0, vBuf, 1, 0);

        u8g2.drawHLine(0, 12, 128);

        char sBuf[16];
        snprintf(sBuf, sizeof(sBuf), "%d", gpsSpeed);
        myFont.set_font(FONT_CLOCK);
        myFont.print(36, 15, sBuf, 1, 0);

        myFont.set_font(vietnamtimes12x2b);
        myFont.print(84, 28, "KM/H", 1, 0);

        u8g2.drawFrame(0, 54, 128, 8);
        int barW = (gpsSpeed * 128) / 120;
        if (barW > 128) barW = 128;
        if (barW > 0) u8g2.drawBox(0, 54, barW, 8);
    }

    // Vẽ Overlay Cảnh báo giao thông (nếu có)
    drawTrafficWarningOverlay();

    u8g2.sendBuffer();
}

void drawVectorTurnIcon(int dirIdx, int xOffset, int yOffset)
{
    if (dirIdx == 1 || dirIdx == 4 || dirIdx == 6) {
        // Rẽ trái (Mũi tên bẻ góc 90 độ sang trái)
        u8g2.drawBox(xOffset + 24, yOffset + 10, 4, 28);
        u8g2.drawBox(xOffset + 8, yOffset + 10, 18, 4);
        u8g2.drawTriangle(xOffset + 2, yOffset + 12, xOffset + 12, yOffset + 4, xOffset + 12, yOffset + 20);
    } else if (dirIdx == 2 || dirIdx == 5 || dirIdx == 7) {
        // Rẽ phải (Mũi tên bẻ góc 90 độ sang phải)
        u8g2.drawBox(xOffset + 10, yOffset + 10, 4, 28);
        u8g2.drawBox(xOffset + 10, yOffset + 10, 18, 4);
        u8g2.drawTriangle(xOffset + 34, yOffset + 12, xOffset + 24, yOffset + 4, xOffset + 24, yOffset + 20);
    } else if (dirIdx == 8 || dirIdx == 9) {
        // Quay đầu (U-Turn)
        u8g2.drawBox(xOffset + 10, yOffset + 16, 4, 22);
        u8g2.drawBox(xOffset + 24, yOffset + 16, 4, 22);
        u8g2.drawBox(xOffset + 10, yOffset + 10, 18, 4);
        u8g2.drawTriangle(xOffset + 12, yOffset + 40, xOffset + 4, yOffset + 30, xOffset + 20, yOffset + 30);
    } else {
        // Đi thẳng
        u8g2.drawBox(xOffset + 16, yOffset + 12, 4, 26);
        u8g2.drawTriangle(xOffset + 18, yOffset + 2, xOffset + 8, yOffset + 16, xOffset + 28, yOffset + 16);
    }
}

// -------------------------------------------------------------
// 2. MÀN HÌNH HUD (DẪN ĐƯỜNG TURN-BY-TURN - 4 PHONG CÁCH TÙY BIẾN)
// -------------------------------------------------------------
void drawHUD()
{
    u8g2.clearBuffer();

    if (hudStyle == 0) {
        // ---------------- H1: CLASSIC BOXED (Phác thảo gốc) ----------------
        // 1. Phía trên bên trái: Icon rẽ
        if (hasCustomIcon) {
            drawCustomIcon(customIconBitmap, 0, 0, 1);
        } else {
            drawVectorTurnIcon(navDirIdx, 0, 0);
        }

        // 2. Khung chữ nhật hiển thị tên đường (x=48, y=0, w=80, h=44)
        u8g2.drawFrame(48, 0, 80, 44);
        myFont.set_font(vietnamtimes12);
        String street = nextStreet.length() > 0 ? nextStreet : "Đang dẫn đường...";
        if (street.length() <= 12) {
            myFont.print(52, 14, street.c_str(), 1, 0);
        } else if (street.length() <= 24) {
            String l1 = street.substring(0, 12);
            String l2 = street.substring(12);
            myFont.print(52, 6, l1.c_str(), 1, 0);
            myFont.print(52, 22, l2.c_str(), 1, 0);
        } else {
            String l1 = street.substring(0, 12);
            String l2 = street.substring(12, 22) + "..";
            myFont.print(52, 6, l1.c_str(), 1, 0);
            myFont.print(52, 22, l2.c_str(), 1, 0);
        }

        // 3. Khoảng cách & Tốc độ
        myFont.set_font(FONT_HUD_DIST);
        String dStr = distToNext.length() > 0 ? distToNext : "0M";
        dStr.toUpperCase();
        myFont.print(0, 48, dStr.c_str(), 1, 0);

        char spdBuf[20];
        snprintf(spdBuf, sizeof(spdBuf), "%d KM/H", gpsSpeed);
        uint16_t spdLen = myFont.getLength(spdBuf);
        int spdX = 128 - spdLen;
        if (spdX < 60) spdX = 60;
        myFont.print(spdX, 48, spdBuf, 1, 0);

    } else if (hudStyle == 1) {
        // ---------------- H2: SPLIT DASHBOARD (Chia đôi đối xứng) ----------------
        // Cột trái (Tốc độ lớn + KM/H + mini speed gauge)
        char sBuf[16];
        snprintf(sBuf, sizeof(sBuf), "%d", gpsSpeed);
        myFont.set_font(FONT_CLOCK);
        uint16_t sLen = myFont.getLength(sBuf);
        myFont.print((56 - sLen) / 2, 6, sBuf, 1, 0);

        myFont.set_font(vietnamtimes12x2b);
        myFont.print(12, 38, "KM/H", 1, 0);

        // Vạch tốc độ
        u8g2.drawFrame(4, 52, 48, 6);
        int barW = (gpsSpeed * 48) / 120;
        if (barW > 48) barW = 48;
        if (barW > 0) u8g2.drawBox(4, 52, barW, 6);

        // Đường phân cách dọc ở giữa
        u8g2.drawVLine(56, 0, 64);

        // Cột phải: Icon rẽ + khoảng cách + tên đường
        if (hasCustomIcon) {
            drawCustomIcon(customIconBitmap, 60, 2, 1);
        } else {
            drawVectorTurnIcon(navDirIdx, 60, 0);
        }

        myFont.set_font(FONT_HUD_DIST);
        String dStr = distToNext.length() > 0 ? distToNext : "0M";
        dStr.toUpperCase();
        myFont.print(94, 6, dStr.c_str(), 1, 0);

        myFont.set_font(vietnamtimes12);
        String street = nextStreet.length() > 0 ? nextStreet : "Đang dẫn đường...";
        if (street.length() > 14) street = street.substring(0, 12) + "..";
        myFont.print(60, 44, street.c_str(), 1, 0);

    } else if (hudStyle == 2) {
        // ---------------- H3: BIG ARROW FOCUS (Mũi tên lớn + Progress + ETA) ----------------
        // Cột trái: Mũi tên rẽ
        if (hasCustomIcon) {
            drawCustomIcon(customIconBitmap, 2, 2, 1);
        } else {
            drawVectorTurnIcon(navDirIdx, 6, 2);
        }

        myFont.set_font(FONT_HUD_DIST);
        String dStr = distToNext.length() > 0 ? distToNext : "0M";
        dStr.toUpperCase();
        uint16_t dLen = myFont.getLength(dStr.c_str());
        myFont.print((48 - dLen) / 2, 48, dStr.c_str(), 1, 0);

        // Cột phải: Tên đường + Progress bar + Tốc độ + ETA
        myFont.set_font(vietnamtimes12x2b);
        String street = nextStreet.length() > 0 ? nextStreet : "Đang dẫn đường...";
        if (street.length() > 13) street = street.substring(0, 11) + "..";
        myFont.print(50, 2, street.c_str(), 1, 0);

        u8g2.drawFrame(50, 20, 76, 6);
        u8g2.drawBox(50, 20, 50, 6);

        char spdBuf[16];
        snprintf(spdBuf, sizeof(spdBuf), "%d km/h", gpsSpeed);
        myFont.set_font(FONT_HUD_DIST);
        myFont.print(50, 36, spdBuf, 1, 0);

        if (eta.length() > 0) {
            char etaBuf[16];
            snprintf(etaBuf, sizeof(etaBuf), "Đến %s", eta.c_str());
            myFont.set_font(vietnamtimes12);
            uint16_t eLen = myFont.getLength(etaBuf);
            myFont.print(128 - eLen, 50, etaBuf, 1, 0);
        }

    } else {
        // ---------------- H4: RACING TELEMETRY (Thanh tốc độ đua) ----------------
        // Header: Thanh tốc độ RPM
        int barW = (gpsSpeed * 80) / 120;
        if (barW > 80) barW = 80;
        u8g2.drawFrame(0, 0, 80, 10);
        if (barW > 0) u8g2.drawBox(0, 0, barW, 10);

        char spdBuf[16];
        snprintf(spdBuf, sizeof(spdBuf), "%d KM/H", gpsSpeed);
        myFont.set_font(vietnamtimes12x2b);
        uint16_t sLen = myFont.getLength(spdBuf);
        myFont.print(128 - sLen, 0, spdBuf, 1, 0);

        // Thân giữa: Icon rẽ + khoảng cách + tên đường
        if (hasCustomIcon) {
            drawCustomIcon(customIconBitmap, 2, 14, 1);
        } else {
            drawVectorTurnIcon(navDirIdx, 2, 12);
        }

        myFont.set_font(FONT_HUD_DIST);
        String dStr = distToNext.length() > 0 ? distToNext : "0M";
        dStr.toUpperCase();
        myFont.print(46, 16, dStr.c_str(), 1, 0);

        myFont.set_font(vietnamtimes12);
        String street = nextStreet.length() > 0 ? nextStreet : "Đang dẫn đường...";
        if (street.length() > 15) street = street.substring(0, 13) + "..";
        myFont.print(46, 34, street.c_str(), 1, 0);

        // Footer: Điện áp xe & ETA
        u8g2.drawHLine(0, 48, 128);
        char vBuf[16];
        snprintf(vBuf, sizeof(vBuf), "XE:%.1fV", batteryVoltage);
        myFont.print(0, 51, vBuf, 1, 0);

        if (eta.length() > 0) {
            char etaBuf[16];
            snprintf(etaBuf, sizeof(etaBuf), "Đến %s", eta.c_str());
            uint16_t eLen = myFont.getLength(etaBuf);
            myFont.print(128 - eLen, 51, etaBuf, 1, 0);
        }
    }

    // Vẽ Overlay Cảnh báo giao thông (nếu có)
    drawTrafficWarningOverlay();

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 3. MÀN HÌNH MAP (BẢN ĐỒ 1BPP STREAMING TỪ APP)
// -------------------------------------------------------------
void drawMAP()
{
    u8g2.clearBuffer();

    if (hasActiveOledImage) {
        // oledBuffer chứa 1024 bytes (128x64 1bpp)
        u8g2.drawBitmap(0, 0, 16, 64, oledBuffer);
    } else {
        myFont.set_font(vietnamtimes12);
        myFont.print(15, 25, "Đang chờ bản đồ...", 1, 0);
        u8g2.drawFrame(0, 0, 128, 64);
    }

    // Overlay mini tốc độ góc trái dưới
    char spdBuf[16];
    snprintf(spdBuf, sizeof(spdBuf), "%d km/h", gpsSpeed);
    u8g2.setDrawColor(0);
    u8g2.drawBox(0, 50, 48, 14);
    u8g2.setDrawColor(1);
    u8g2.drawFrame(0, 50, 48, 14);
    myFont.set_font(vietnamtimes12);
    myFont.print(2, 51, spdBuf, 1, 0);

    // Vẽ Overlay Cảnh báo giao thông (nếu có)
    drawTrafficWarningOverlay();

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 4. MÀN HÌNH NOTIFICATION (THÔNG BÁO CUỘC GỌI / ZALO / SMS)
// -------------------------------------------------------------
void drawNOTIF()
{
    u8g2.clearBuffer();

    if (notifCount > 0) {
        NotificationItem &item = notifList[notifViewIndex % notifCount];

        // Khung viền thông báo
        u8g2.drawRFrame(0, 0, 128, 64, 3);
        u8g2.drawHLine(0, 14, 128);

        // Header: Tên Ứng Dụng
        myFont.set_font(vietnamtimes12x2b);
        String appName = item.app.length() > 0 ? item.app : "THÔNG BÁO";
        appName.toUpperCase();
        myFont.print(4, 1, appName.c_str(), 1, 0);

        // Người gửi / Tiêu đề
        myFont.set_font(vietnamtimes12x2b);
        String title = item.title;
        if (title.length() > 20) title = title.substring(0, 18) + "..";
        myFont.print(4, 17, title.c_str(), 1, 0);

        // Nội dung tin nhắn
        myFont.set_font(FONT_NOTIF_BODY);
        String msg = item.msg;
        if (msg.length() > 40) msg = msg.substring(0, 38) + "..";

        // Chia 2 dòng nếu dài
        if (msg.length() > 20) {
            String line1 = msg.substring(0, 20);
            String line2 = msg.substring(20);
            myFont.print(4, 33, line1.c_str(), 1, 0);
            myFont.print(4, 48, line2.c_str(), 1, 0);
        } else {
            myFont.print(4, 33, msg.c_str(), 1, 0);
        }
    } else {
        myFont.set_font(vietnamtimes12);
        myFont.print(20, 25, "Không có thông báo", 1, 0);
    }

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 5. MÀN HÌNH THÔNG TIN HỆ THỐNG (INFO)
// -------------------------------------------------------------
void drawINFO()
{
    u8g2.clearBuffer();

    u8g2.drawFrame(0, 0, 128, 64);
    myFont.set_font(vietnamtimes12x2b);
    myFont.print(16, 2, "TYMAP v1.0.9 | SH1106", 1, 0);
    u8g2.drawHLine(0, 15, 128);

    myFont.set_font(vietnamtimes12);

    // RAM khả dụng
    char ramBuf[32];
    snprintf(ramBuf, sizeof(ramBuf), "Free Heap: %d KB", ESP.getFreeHeap() / 1024);
    myFont.print(4, 18, ramBuf, 1, 0);

    // Điện áp ADC
    char batBuf[32];
    snprintf(batBuf, sizeof(batBuf), "Pin Xe: %.1fV | ĐT: %d%%", batteryVoltage, phoneBatteryLevel >= 0 ? phoneBatteryLevel : 100);
    myFont.print(4, 32, batBuf, 1, 0);

    // Uptime
    char upBuf[32];
    unsigned long sec = millis() / 1000;
    snprintf(upBuf, sizeof(upBuf), "Uptime: %02lu:%02lu:%02lu", sec / 3600, (sec % 3600) / 60, sec % 60);
    myFont.print(4, 46, upBuf, 1, 0);

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 6. CẢNH BÁO GIAO THÔNG OVERLAY (TỐC ĐỘ / CAMERA)
// -------------------------------------------------------------
void drawTrafficWarningOverlay()
{
    if (!isTrafficWarningActive) return;

    if (millis() - trafficWarningStartTime > 6000) {
        isTrafficWarningActive = false;
        return;
    }

    // Nhấp nháy mỗi 400ms
    if ((millis() / 400) % 2 == 0) return;

    // Hộp cảnh báo góc trên phải
    u8g2.setDrawColor(0);
    u8g2.drawBox(78, 0, 50, 24);
    u8g2.setDrawColor(1);
    u8g2.drawFrame(78, 0, 50, 24);

    myFont.set_font(vietnamtimes12);
    if (trafficWarningType == 2) {
        myFont.print(82, 4, "CAMERA", 1, 0);
    } else {
        char spdWarn[16];
        snprintf(spdWarn, sizeof(spdWarn), "MAX %d", trafficWarningValue > 0 ? trafficWarningValue : 60);
        myFont.print(82, 4, spdWarn, 1, 0);
    }
}

// -------------------------------------------------------------
// 7. TIẾN TRÌNH CẬP NHẬT BLE OTA
// -------------------------------------------------------------
void drawOtaProgressScreen()
{
    u8g2.clearBuffer();

    u8g2.drawFrame(0, 0, 128, 64);
    myFont.set_font(vietnamtimes12x2b);
    myFont.print(14, 4, "NÂNG CẤP FIRMWARE", 1, 0);
    u8g2.drawHLine(0, 18, 128);

    int pct = 0;
    if (otaExpectedSize > 0) {
        pct = (int)((otaWritten * 100) / otaExpectedSize);
    }

    // Thanh Progress Bar
    u8g2.drawFrame(14, 26, 100, 12);
    u8g2.drawBox(16, 28, (pct * 96) / 100, 8);

    // Phần trăm
    myFont.set_font(vietnamtimes12);
    char pBuf[32];
    snprintf(pBuf, sizeof(pBuf), "%d%% (%d/%d KB)", pct, otaWritten / 1024, otaExpectedSize / 1024);
    uint16_t pLen = myFont.getLength(pBuf);
    myFont.print((128 - pLen) / 2, 44, pBuf, 1, 0);

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 8. LOGO KHỞI ĐỘNG
// -------------------------------------------------------------
void drawLogoSplash(unsigned long currentTime, unsigned long startTime, unsigned long introDuration)
{
    u8g2.clearBuffer();

    // Vẽ Logo 64x64 căn giữa màn hình (x = 32, y = 0)
    u8g2.drawXBMP(32, 0, 64, 64, logo_pnt_64x64);

    u8g2.sendBuffer();
}
