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

// -------------------------------------------------------------
// 1. MÀN HÌNH STATUS (ĐỒNG HỒ & THÔNG SỐ XE)
// -------------------------------------------------------------
void drawSTATUS()
{
    u8g2.clearBuffer();

    // 1. Header trên cùng (y: 0 - 10)
    // Icon BLE / Trạng thái
    if (bleConnected) {
        u8g2.setDrawColor(1);
        u8g2.drawDisc(4, 5, 2);
    } else {
        u8g2.drawCircle(4, 5, 2);
    }

    // Ngày tháng tiếng Việt
    String dateStr = rtc.getTime("%d/%m");
    myFont.set_font(vietnamtimes12);
    myFont.print(12, 0, dateStr.c_str(), 1, 0);

    // Thời tiết
    if (weatherTemp > -50.0f) {
        char wBuf[16];
        snprintf(wBuf, sizeof(wBuf), "%.0f°C", weatherTemp);
        uint16_t wLen = myFont.getLength(wBuf);
        myFont.print(128 - wLen, 0, wBuf, 1, 0);
    }

    // Đường kẻ phân cách header
    u8g2.drawHLine(0, 12, 128);

    // 2. Đồng hồ trung tâm lớn
    String timeStr = rtc.getTime("%H:%M");
    myFont.set_font(FONT_CLOCK);
    uint16_t timeLen = myFont.getLength(timeStr.c_str());
    int timeX = (128 - timeLen) / 2;
    if (timeX < 0) timeX = 0;
    myFont.print(timeX, 15, timeStr.c_str(), 1, 0);

    // 3. Footer dưới cùng (y: 50 - 64)
    u8g2.drawHLine(0, 50, 128);
    myFont.set_font(vietnamtimes12);

    // Điện áp xe (XE: 12.4V)
    char vBuf[16];
    snprintf(vBuf, sizeof(vBuf), "XE:%.1fV", batteryVoltage);
    myFont.print(0, 52, vBuf, 1, 0);

    // Pin điện thoại (ĐT: 85%)
    char pBuf[16];
    int pBat = (phoneBatteryLevel >= 0) ? phoneBatteryLevel : 100;
    snprintf(pBuf, sizeof(pBuf), "ĐT:%d%%%s", pBat, phoneBatteryCharging ? "+" : "");
    uint16_t pLen = myFont.getLength(pBuf);
    myFont.print(128 - pLen, 52, pBuf, 1, 0);

    // Vẽ Overlay Cảnh báo giao thông (nếu có)
    drawTrafficWarningOverlay();

    u8g2.sendBuffer();
}

// -------------------------------------------------------------
// 2. MÀN HÌNH HUD (DẪN ĐƯỜNG TURN-BY-TURN - BỐ CỤC CHUẨN)
// -------------------------------------------------------------
void drawHUD()
{
    u8g2.clearBuffer();

    // 1. Phía trên bên trái: Icon rẽ (40x40 / 48x48)
    if (hasCustomIcon) {
        drawCustomIcon(customIconBitmap, 0, 0, 1);
    } else {
        // Vẽ icon rẽ vector rõ nét
        if (navDirIdx == 1 || navDirIdx == 4 || navDirIdx == 6) {
            // Rẽ trái (Mũi tên bẻ góc 90 độ sang trái)
            u8g2.drawBox(24, 10, 4, 28);
            u8g2.drawBox(8, 10, 18, 4);
            u8g2.drawTriangle(2, 12, 12, 4, 12, 20);
        } else if (navDirIdx == 2 || navDirIdx == 5 || navDirIdx == 7) {
            // Rẽ phải (Mũi tên bẻ góc 90 độ sang phải)
            u8g2.drawBox(10, 10, 4, 28);
            u8g2.drawBox(10, 10, 18, 4);
            u8g2.drawTriangle(34, 12, 24, 4, 24, 20);
        } else if (navDirIdx == 8 || navDirIdx == 9) {
            // Quay đầu (U-Turn)
            u8g2.drawBox(10, 16, 4, 22);
            u8g2.drawBox(24, 16, 4, 22);
            u8g2.drawBox(10, 10, 18, 4);
            u8g2.drawTriangle(12, 40, 4, 30, 20, 30);
        } else {
            // Đi thẳng
            u8g2.drawBox(16, 12, 4, 26);
            u8g2.drawTriangle(18, 2, 8, 16, 28, 16);
        }
    }

    // 2. Phía trên bên phải: Khung chữ nhật hiển thị tên đường (x=48, y=0, w=80, h=44)
    u8g2.drawFrame(48, 0, 80, 44);

    // Tên đường kế tiếp (Font tiếng Việt FontMaker, tự chia 2-3 dòng nếu dài)
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

    // 3. Hàng dưới cùng: Khoảng cách rẽ (Trái) & Tốc độ GPS (Phải)
    // Khoảng cách rẽ (ví dụ: "200M", "1.5KM")
    myFont.set_font(FONT_HUD_DIST);
    String dStr = distToNext.length() > 0 ? distToNext : "0M";
    dStr.toUpperCase();
    myFont.print(0, 48, dStr.c_str(), 1, 0);

    // Tốc độ xe (ví dụ: "70 KM/H")
    char spdBuf[20];
    snprintf(spdBuf, sizeof(spdBuf), "%d KM/H", gpsSpeed);
    uint16_t spdLen = myFont.getLength(spdBuf);
    int spdX = 128 - spdLen;
    if (spdX < 60) spdX = 60;
    myFont.print(spdX, 48, spdBuf, 1, 0);

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
