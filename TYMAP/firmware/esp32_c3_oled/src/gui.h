#ifndef GUI_H
#define GUI_H

#include <Arduino.h>
#include <U8g2lib.h>
#include <ESP32Time.h>
#include <FontMaker.h>

// ==================== CẤU HÌNH FONT CHỮ HỆ THỐNG ====================
#define FONT_CLOCK f_to_vai               // Font đồng hồ lớn (STATUS)
#define FONT_HUD_DIST h_to2               // Font khoảng cách rẽ (HUD)
#define FONT_HUD_STREET vietnamtimes14x4b // Font tên đường / chỉ dẫn (HUD)
#define FONT_HUD_INFO vietnamtimes12      // Font tốc độ & ETA (HUD)
#define FONT_STATUS_INFO h_to1            // Font ngày tháng, thời tiết, điện áp (STATUS)
#define FONT_NOTIF_TITLE h_to2            // Font tiêu đề thông báo
#define FONT_NOTIF_BODY vietnamtimes12    // Font nội dung thông báo
#define FONT_MENU_TITLE vietnamtimes12x2b // Font tiêu đề menu
#define FONT_MENU_OPTION vietnamtimes12   // Font các mục chọn trong menu
// ====================================================================

// Khai báo chế độ hiển thị hệ thống
enum Mode { HUD_MODE, MAP_MODE, STATUS_MODE, INFO_MODE, NOTIF_MODE, SETTINGS_MODE };

// Cấu trúc lưu trữ thông báo
struct NotificationItem {
    String app;
    String title;
    String msg;
    unsigned long time;
};

// Khai báo biến extern dùng chung
extern Mode currentMode;
extern Mode selectedMode;
extern Mode previousModeBeforeNotif;
extern U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2;
extern MakeFont myFont;
extern volatile bool bleConnected;

// Trạng thái dữ liệu Dẫn đường HUD
extern String nextStreet;
extern String distToNext;
extern String totalDist;
extern String eta;
extern String ete;
extern int navDirIdx;
extern int gpsSpeed;
extern uint8_t staticIconIndex;
extern bool hasCustomIcon;
extern uint8_t customIconBitmap[288];

// Trạng thái Thời gian, Thời tiết & Thiết bị
extern ESP32Time rtc;
extern float weatherTemp;
extern String weatherIcon;
extern float batteryVoltage;

// Pin điện thoại & Đồng bộ thời gian
extern int phoneBatteryLevel;     // -1 = chưa biết, 0-100 = mức pin
extern bool phoneBatteryCharging; // đang sạc hay không
extern bool timeSynced;           // đã đồng bộ thời gian chưa

// Dữ liệu Notifications
extern NotificationItem notifList[3];
extern int notifCount;
extern int notifViewIndex;
extern bool isNotifPopupTransient;
extern unsigned long notifPopupStartTime;

// Trạng thái Cảnh báo giao thông (Speed Limit & Camera Phạt nguội)
extern bool isTrafficWarningActive;
extern uint8_t trafficWarningType;  // 1: Quá tốc độ, 2: Camera phạt nguội, 3: Cả hai
extern uint8_t trafficWarningValue; // Giá trị tốc độ giới hạn
extern unsigned long trafficWarningStartTime;

// Trạng thái Menu & Kiểu dáng HUD
extern bool isMenuOpen;
extern unsigned long menuStartTime;
extern int menuSelectedIndex;
extern int brightness;
extern uint8_t hudStyle; // 0=H1 Classic Boxed, 1=H2 Split Dash, 2=H3 Big Arrow, 3=H4 Racing Bar

// Trạng thái Cập nhật Firmware BLE OTA
extern bool isOtaMode;
extern uint32_t otaExpectedSize;
extern uint32_t otaWritten;

// Nhận ảnh OLED 128x64 1bpp (1024 bytes)
extern uint8_t oledBuffer[1024];
extern bool hasActiveOledImage;

// Các nguyên mẫu hàm vẽ GUI OLED
void drawCustomIcon(const uint8_t *bitmap, int xOffset, int yOffset, int scale = 1);
void drawHUD();
void drawSTATUS();
void drawMAP();
void drawINFO();
void drawNOTIF();
void drawSETTINGS();
void drawTrafficWarningOverlay();
void drawOtaProgressScreen();
void drawLogoSplash(unsigned long currentTime, unsigned long startTime, unsigned long introDuration);

#endif // GUI_H
