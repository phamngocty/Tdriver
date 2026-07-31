# Design Specification: Modern Smartwatch Notification UI

**Date:** 2026-07-31  
**Project:** TYMAP (ESP32-S3 GC9A01 Firmware + Android App Companion)  
**Status:** Approved by User  

---

## 1. Overview
Redesign the Notification interface across both the **ESP32-S3 smartwatch firmware (`NOTIF_MODE`)** and the **Android Companion App (`NotificationsFragment`)** to deliver an aesthetic, readable, and functional smartwatch notification experience comparable to modern smartwatches (Apple Watch / Galaxy Watch).

---

## 2. Component Specifications

### 2.1 ESP32-S3 Firmware: `NOTIF_MODE` (`gui.cpp`, `gui.h`, `main.cpp`)

#### A. Brand Accent Color Detection
Implement helper function `getAppAccentColor(const String& appName)`:
- `Zalo` → Bright Zalo Blue (`0x1C9F`)
- `Messenger` / `Facebook` → Magenta/Purple (`0xD81F`)
- `SMS` / `Tin nhắn` → Emerald Green (`0x07E0`)
- `Phone` / `Call` / `Cuộc gọi` → Coral Red (`0xF800`)
- `Google Maps` / `Dẫn đường` → Sky Blue (`0x07FF`)
- Default / Unknown → Bright Cyan (`0x07FF`)

#### B. Top Pill Badge (`App Badge`)
- Position: Center `cx = 120`, `y = 16`, Height = `22px`, Radius = `11px`.
- Fill rounded rectangle with `accentColor`.
- Draw App name text in contrasting color inside the badge.

#### C. Card Container & Layout (`y = 44..190`)
- Outer Card: `fillRoundRect(15, 44, 210, 145, 16, 0x18E3)` (Glassmorphic dark grey background).
- Border: `drawRoundRect(15, 44, 210, 145, 16, accentColor)` thin outline.
- Title: Bold font (`FONT_NOTIF_TITLE` / `FONT_MENU_TITLE`), printed in White (`TFT_WHITE`) or Gold (`TFT_YELLOW`) at `y = 52`. Truncate cleanly if too long.
- Message Body: Printed using `printWrappedText()` at `y = 76`, wrapping nicely inside the 210px container bounds.

#### D. Page Dot Indicator (`y = 196`)
- Replace text `[1/3]` with a row of circular dots centered horizontally.
- Inactive dots: Radius `3px`, Color `0x39E7` (Muted Grey).
- Active dot (`notifViewIndex`): Radius `5px`, Color `accentColor` or `TFT_WHITE` with accent highlight.

#### E. Footer Guidance Line (`y = 216`)
- Subdued text prompt at bottom:
  - Transient popup mode: "Bấm nút: Đóng [X]"
  - Persistent menu mode: "Bấm nút: Tiếp (1/3)"

#### F. Empty Notification State (`notifCount == 0`)
- Display empty envelope/bell icon at center (`cx=120, cy=110`).
- Text "Không có thông báo mới" in muted silver (`TFT_SILVER`) at `y = 155`.

---

### 2.2 Android Companion App: `NotificationsFragment` & Layouts

#### A. Notification Access Permission Banner
- Check if Notification Listener Service is authorized.
- If disabled: Display prominent amber/red alert card with button `CẤP QUYỀN NGAY`.
- If enabled: Display subtle green active indicator chip.

#### B. Quick Test / Mock Notification Card
- Material 3 Card container allowing manual simulation of notifications.
- Selector chip/spinner for App type (Zalo, Messenger, SMS, Call, Custom).
- TextInputEditText fields for Title and Message.
- Action button `GỬI SANG ĐỒNG HỒ` pushing JSON payload over BLE (`CHA_NOTIFICATION_UUID`).

#### C. Allowed Apps List (`rvNotificationApps`)
- Redesigned list items (`item_notification_app.xml`):
  - 40dp App Icon.
  - App Name (Bold) + Package Name (Subdued text).
  - Material 3 Switch to enable/disable notification forwarding for that app.
- Action button `+ THÊM ỨNG DỤNG` opening installed application picker dialog.

---

## 3. Verification Plan

### 3.1 Firmware Build Verification
- Compile ESP32-S3 firmware using PlatformIO (`pio run -e esp32_s3_gc9a01`).
- Verify binary builds without compilation or linking errors.

### 3.2 Android App Build Verification
- Compile Android application using Gradle (`./gradlew assembleDebug` or `gradlew.bat assembleDebug`).
- Verify no build failures or missing resource references.

---

## 4. User Review
This spec file is created at `docs/superpowers/specs/2026-07-31-smartwatch-notification-ui-design.md`.
