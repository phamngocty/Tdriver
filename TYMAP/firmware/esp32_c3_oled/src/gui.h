#ifndef GUI_H
#define GUI_H

#include <Arduino.h>
#include <U8g2lib.h>
#include <ESP32Time.h>
#include <FontMaker.h>

// ==================== CẤU HÌNH FONT CHỮ HỆ THỐNG ====================
// Font tiếng Việt (FontMaker) - CHỈ DÙNG CHO TÊN ĐƯỜNG VÀ THÔNG BÁO
#define FONT_VIETNAMESE_TITLE vietnamtimes12x2b // Tiêu đề thông báo / Tên ứng dụng
#define FONT_VIETNAMESE_BODY  vietnamtimes12    // Nội dung thông báo & Tên đường

// Font U8g2 chuẩn cho các thành phần số & giao diện (Không bị tràn màn hình)
#define FONT_U8G2_BIG_CLOCK   u8g2_font_logisoso28_tn // Số đồng hồ lớn
#define FONT_U8G2_MID_CLOCK   u8g2_font_logisoso22_tn // Số đồng hồ / tốc độ vừa
#define FONT_U8G2_DIST        u8g2_font_helvB14_tf    // Khoảng cách rẽ (150M, 1.2KM)
#define FONT_U8G2_LABEL_BOLD  u8g2_font_7x14B_tf      // Nhãn nổi bật (KM/H, MAX, TITLE)
#define FONT_U8G2_SMALL       u8g2_font_6x10_tf       // Thông tin nhỏ (Ngày, Pin, V, ETA)
#define FONT_U8G2_TINY        u8g2_font_helvB08_tf    // Thông tin phụ siêu nhỏ
// ====================================================================

// Khai báo chế độ hiển thị hệ thống (Đồng bộ thứ tự với ESP32-S3)
enum Mode { HUD_MODE, MAP_MODE, MAP_HUD_MODE, STATUS_MODE, INFO_MODE, NOTIF_MODE, SETTINGS_MODE };

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
extern bool isNavigating;
extern U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2;
extern MakeFont myFont;
extern volatile bool bleConnected;

// Tọa độ Clipping cửa sổ hiển thị
extern int16_t clipMinX;
extern int16_t clipMaxX;
extern int16_t clipMinY;
extern int16_t clipMaxY;

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
extern uint8_t hudStyle;    // 0=H1 Classic Boxed, 1=H2 Split Dash, 2=H3 Big Arrow, 3=H4 Racing Bar, 4=H5 Banner, 5=H6 Dual Pill
extern uint8_t statusStyle; // 0=S1 Classic Digital, 1=S2 Dual Gauges, 2=S3 Minimalist, 3=S4 Sport Telemetry
extern uint8_t notifStyle;  // 0=N1 Rounded Card, 1=N2 Split App Focus, 2=N3 Top Banner
extern uint8_t mapStyle;    // 0=M1 Fullscreen Map + Mini HUD, 1=Pure Map 100%, 2=M2 Split Map + Turn HUD

// Trạng thái Cập nhật Firmware BLE OTA
extern bool isOtaMode;
extern uint32_t otaExpectedSize;
extern uint32_t otaWritten;

// Nhận ảnh OLED 128x64 1bpp (1024 bytes)
extern uint8_t oledBuffer[1024];
extern bool hasActiveOledImage;

// Bộ đệm sóng Oscilloscope thời gian thực
extern float voltHistory[80];
extern uint8_t voltHistoryIdx;
extern float voltMin;
extern float voltMax;
extern uint16_t autoSampleIntervalMs;
void pushVoltSample(float v);

// Các nguyên mẫu hàm vẽ GUI OLED
void drawCustomIcon(const uint8_t *bitmap, int xOffset, int yOffset, int scale = 1);
void drawWrappedTextMyFont(int startX, int startY, int maxW, int lineHeight, int maxLines, const String &text);
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
