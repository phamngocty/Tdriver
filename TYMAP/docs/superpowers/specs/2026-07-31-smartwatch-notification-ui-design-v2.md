# Design Specification v2: Dynamic Ring Arc Smartwatch Notification & All-App Sync Manager

**Date:** 2026-07-31  
**Project:** TYMAP (ESP32-S3 GC9A01 Firmware + Android App Companion)  
**Status:** Approved by User (Selected Model A: Dynamic Ring Arc)  

---

## 1. Overview
Redesign the **ESP32-S3 GC9A01 smartwatch notification screen (`NOTIF_MODE`)** using **Model A: Dynamic Ring Arc** featuring top curved app header arc, right circular page arc progress, and smart centered payload text.

Redesign the **Android Companion App (`NotificationsFragment`)** to display **all installed applications** directly in the main notification tab with an instant search filter box and direct toggle switches, eliminating the need for add-app popup dialogs.

---

## 2. Component Specifications

### 2.1 ESP32-S3 Firmware: `NOTIF_MODE` (Model A: Dynamic Ring Arc)

#### A. Brand Accent Colors (`getAppAccentColor`)
- `Zalo` → Bright Zalo Blue (`0x1C9F`)
- `Messenger` / `Facebook` → Purple/Magenta (`0xD81F`)
- `SMS` / `Message` → Emerald Green (`0x07E0`)
- `Phone` / `Call` → Coral Red (`0xF800`)
- `Google Maps` → Sky Blue (`0x07FF`)
- Default → Bright Cyan (`0x07FF`)

#### B. Top Header Arc (`y = 10..34`)
- Top curved pill/arc badge filled with `accentColor`.
- Centered App Name in contrasting bold text.

#### C. Right-Edge Circular Arc Page Gauge
- Draw a curved ring arc gauge along the right circular border `(cx=120, cy=120, radius=115)`.
- Background arc: Muted grey arc from `-35°` to `+35°`.
- Active arc indicator: Accent-colored arc segment representing current page ratio `(notifViewIndex + 1) / notifCount`.

#### D. Center Notification Message Payload (`y = 42..188`)
- Title: Bold White at `y = 48`.
- Accent separator line at `y = 68`.
- Body text: Wrapped using `printWrappedText()` at `y = 74` with width constraint `185px`.

#### E. Bottom Action Line (`y = 212`)
- Guidance text centered at `y = 212` ("Bấm nút: Đóng" / "Bấm nút: Tiếp theo").

---

### 2.2 Android Companion App: `NotificationsFragment` & Layouts

#### A. Direct All-App Listing (`rvNotificationApps`)
- Retrieve all installed launchable applications via `packageManager.getInstalledApplications()`.
- Sort alphabetically by app name.
- Render each app directly in the main RecyclerView list with icon, title, package name, and a **Switch**.
- Toggling the switch instantly updates `enabled_notifications` in `PrefsHelper`.

#### B. Live Search Filter Box (`etSearchApp` / `svApps`)
- Add a search input field directly above the app list in `fragment_notifications.xml`.
- Filter app list live as user types without reloading.

#### C. Permission Status & Quick Test Cards
- Retain Permission Status Banner Card (Green = Authorized, Red = Grant Permission action).
- Retain Quick Test Simulator Chips (Zalo, Messenger, SMS, Call) to test BLE notifications.

---

## 3. Verification Plan

### Automated Build Verification
- Firmware: `& "$env:USERPROFILE\.platformio\penv\Scripts\pio.exe" run -e esp32-s3-devkitc-1`
- Android App: `.\gradlew.bat assembleDebug`
