#ifndef GUI_H
#define GUI_H

#include <Arduino.h>
#include <ESP32Time.h>
#include <FontMaker.h>
#include <TFT_eSPI.h>

// ==================== CẤU HÌNH FONT CHỮ HỆ THỐNG ====================
// Giải thích quy chuẩn đặt tên Font:
// - [tên_font][cỡ_chữ]x[khoảng_cách_chữ_pixel][b (nếu in đậm)]
// Ví dụ:
//   - vietnamtimes8x2: chữ cỡ 8, khoảng cách các chữ 2px
//   - time12x2b: chữ số cỡ 12, khoảng cách các chữ 2px, in đậm (b)
//
// Các Font khả dụng từ thư viện FontMaker (được tự động extern từ
// MyFontMaker.h):
// - time4x8, time10x4, time12x2b
// - vietnamtimes6x2, vietnamtimes7x2r, vietnamtimes8x2, vietnamtimes10x2,
// vietnamtimes12, vietnamtimes12x2b

#define FONT_CLOCK f_to_vai               // Font đồng hồ lớn (STATUS)
#define FONT_HUD_DIST h_to2               // Font khoảng cách rẽ (HUD)
#define FONT_HUD_STREET vietnamtimes14x4b // Font tên đường / chỉ dẫn (HUD)
#define FONT_HUD_INFO vietnamtimes12      // Font tốc độ & ETA (HUD)
#define FONT_STATUS_INFO h_to1 // Font ngày tháng, thời tiết, điện áp (STATUS)
#define FONT_NOTIF_TITLE h_to2 // Font tiêu đề thông báo (STATUS)
#define FONT_NOTIF_BODY vietnamtimes12    // Font nội dung thông báo (STATUS)
#define FONT_MENU_TITLE vietnamtimes12x2b // Font tiêu đề menu CHỌN CHẾ ĐỘ
#define FONT_MENU_OPTION vietnamtimes12   // Font các mục chọn trong menu
// ====================================================================

// Khai báo chế độ hiển thị hệ thống
enum Mode { HUD_MODE, MAP_MODE, STATUS_MODE, INFO_MODE, NOTIF_MODE };

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
extern TFT_eSPI tft;
extern TFT_eSprite canvasSprite;
extern MakeFont myFont;
extern int16_t clipMinX;
extern int16_t clipMaxX;
extern int16_t clipMinY;
extern int16_t clipMaxY;
extern volatile bool bleConnected;

// Trạng thái dữ liệu Dẫn đường HUD
extern String nextStreet;
extern String distToNext;
extern String totalDist;
extern String eta;
extern String ete;
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

// Trạng thái Menu chọn chế độ
extern int menuSelectedIndex;
extern int cacheSize;

// Các nguyên mẫu hàm vẽ GUI
void drawCustomIcon(TFT_eSprite &sprite, const uint8_t *bitmap, int xOffset,
                    int yOffset, int scale = 2);
void drawHUD();
void drawSTATUS();
void drawMenuOverlay();
void drawINFO();
void drawNOTIF();
void drawMapOverlay();
void drawLogoWithLoadingBar(unsigned long currentTime, unsigned long startTime,
                            unsigned long introDuration);

#endif // GUI_H
