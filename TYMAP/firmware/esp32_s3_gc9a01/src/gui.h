#ifndef GUI_H
#define GUI_H

#include <Arduino.h>
#include <ESP32Time.h>
#include <FontMaker.h>
#include <TFT_eSPI.h>

// ==================== CẤU HÌNH FONT CHỮ HỆ THỐNG ====================
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

#define color565(r, g, b) canvasSprite.color565(r, g, b)

// Khai báo chế độ hiển thị hệ thống
enum Mode { HUD_MODE, MAP_MODE, MAP_HUD_MODE, STATUS_MODE, INFO_MODE, NOTIF_MODE, SETTINGS_MODE };

extern bool showMapHudCard;
extern uint8_t statusStyle; // 0=S4 Cyber Dual Gauges, 1=S5 Classic Analog, 2=S3 Dual Energy Pill
extern uint8_t notifStyle;  // 0=N1 Floating Card, 1=N2 Fullscreen Focus
extern bool isMenuOpen;
extern int menuSelectedIndex;
extern uint8_t settingCategoryIndex;
extern unsigned long menuStartTime;
extern int cacheSize;

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

// Style Selections
extern uint8_t statusStyle; // 0 = S4 Cyber Dual Gauges, 1 = S5 Classic Analog Watch, 2 = S3 Dual Energy Pill
extern uint8_t notifStyle;  // 0 = N1 Floating Card 3D, 1 = N2 Fullscreen Focus Card (Mẫu N2-Alpha)
extern uint8_t mapHudStyle; // 0 = MH1 Compact Floating Pill (85%), 1 = MH3 Minimalist Badge (92%)

// Dữ liệu Notifications
extern NotificationItem notifList[3];
extern int notifCount;
extern int notifViewIndex;
extern bool isNotifPopupTransient;
extern unsigned long notifPopupStartTime;

// Trạng thái Cảnh báo giao thông (Speed Limit & Camera Phạt nguội)
extern bool isTrafficWarningActive;
extern uint8_t trafficWarningType;
extern uint8_t trafficWarningValue;
extern unsigned long trafficWarningStartTime;

// Custom Theme Layout Config from Theme Studio Builder
extern bool hasCustomLayoutConfig;
void parseAndApplyLayoutJson(const String& jsonStr);

// Các nguyên mẫu hàm vẽ GUI
void drawCustomIcon(TFT_eSprite &sprite, const uint8_t *bitmap, int xOffset,
                    int yOffset, int scale = 2, uint16_t fgColor = TFT_WHITE);
void drawCustomIconResized(TFT_eSprite &sprite, const uint8_t *bitmap, int xOffset,
                           int yOffset, int targetW, int targetH, uint16_t fgColor = TFT_WHITE);
void drawArcSegment(TFT_eSprite &sprite, int cx, int cy, int r, int startAngle, int endAngle, uint16_t color);
void drawHUD();
void drawMapHudOverlay();
void drawSTATUS();
void drawMenuOverlay();
void drawINFO();
void drawNOTIF();
void drawSETTINGS();
void drawMapOverlay();
void drawTrafficWarningOverlay();
void drawLogoWithLoadingBar(unsigned long currentTime, unsigned long startTime,
                            unsigned long introDuration);

uint16_t getAppAccentColor(const String& appName);

#endif // GUI_H
