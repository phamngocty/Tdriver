# Design Spec: MAP HUD Mode & Intelligent Display Architecture

**Date:** 2026-08-09  
**Status:** Approved by User  
**Target Platform:** ESP32-S3 Firmware (`firmware/esp32_s3_gc9a01`) & Android Companion App (`TYMAP/app`)  

---

## 0. Project Philosophy & Core Priority Constraint

- **Zero-Cost Constraint:** The system is built for zero operational cost. Google Maps navigation via Notification Listener (`GMapsNotificationListener.kt`) provides superior, free real-time Vietnamese traffic & turn guidance over paid/expensive third-party APIs.
- **Primary Data Source:** All turn-by-turn maneuvers, next street names (`nextStreet`), distance to next turn (`distToNext`), and turn icons (`customIconBitmap` / `navDirIdx`) are parsed directly from Google Maps notifications.
- **Google Maps Popup Origin:** The turn popup at <= 100m was designed specifically to bridge Google Maps turn alerts with offline/captured OSM map visuals at zero cost.

---

## 1. Goal Description

Implement a new hybrid display mode **`MAP_HUD_MODE`** on the 240x240 circular GC9A01 LCD screen powered by ESP32-S3. The top ~70% of the screen displays real-time map rendering (JPEG stream / tile render), while the bottom ~30% renders a sleek, floating dark rounded HUD card featuring turn maneuver icons, auto-scrolling street names, and turn distance provided by Google Maps. Include a conflict-free state machine for automatic mode adaptation and physical hardware button controls.

---

## 2. Technical Architecture & UI Components

### 2.1 UI Component: Floating Card HUD (`MAP_HUD_MODE`)
- **Screen Resolution:** 240x240 pixels circular LCD (GC9A01).
- **Map View Area (Top 70%):** Rectangular/circular clip from y = 0 to y = 175. Shows blue polyline route & vehicle location marker.
- **Floating HUD Card (Bottom 30%):**
  - Card Coordinates: x = 16, y = 145, width = 208, height = 80, border radius = 20px.
  - Background: Translucent dark slate `rgba(15, 23, 42, 0.95)` with 0.6 opacity Cyan border (`#00E5FF`).
  - Left Icon Circle: 44x44px circular Cyan badge with white turn direction arrow (renders 1bpp bitmap `customIconBitmap` or vector `navDirIdx`).
  - Right Info Column:
    - **Street Name (`nextStreet`):** Parsed from Google Maps. Rendered using `FONT_HUD_STREET` (`vietnamtimes14x4b`). Includes auto-scrolling marquee (`clipMinX`/`clipMaxX`) if length exceeds visible width (140px).
    - **Distance to Turn (`distToNext`):** Parsed from Google Maps. Rendered using `FONT_HUD_DIST` (`h_to2`) in bright neon green (`#00FF7F`).

### 2.2 Mode State Machine & Enum Extensions
Extend `Mode` enum in `gui.h`:
```cpp
enum Mode { HUD_MODE, MAP_MODE, MAP_HUD_MODE, STATUS_MODE, INFO_MODE, NOTIF_MODE };
```
- Mapped mode cycle for `MODE_BTN`: `MAP_HUD` $\rightarrow$ `HUD_DASHBOARD` $\rightarrow$ `STATUS` $\rightarrow$ `MAP_HUD`.
- Hardware Toggle: Long pressing `ZOOM_BTN` (1.5s) toggles `showMapHudCard` boolean to switch between `MAP_HUD` (Map + Floating Card) and `MAP_PURE` (100% full screen map).

### 2.3 Conflict Resolution: Turn Popup in HUD Mode
- In `HUD_MODE`, when distance to next turn parsed from Google Maps reaches threshold (<= 100m), Android triggers a turn JPEG image (`CHA_MAP_IMAGE`).
- ESP32 sets `isPopupActive = true` for `popupDuration` (5s).
- During `isPopupActive = true`, ESP32 renders **Pure 100% Map Popup** without drawing the bottom card, preserving max visibility for the 5-second turn popup before automatically reverting to `HUD_MODE`.

---

## 3. Files to Modify / Create

### Firmware (`TYMAP/firmware/esp32_s3_gc9a01/`):
1. `src/gui.h`: Add `MAP_HUD_MODE` enum, declare `drawMapHudOverlay()` and `extern bool showMapHudCard`.
2. `src/gui.cpp`: Implement `drawMapHudOverlay()` rendering floating card, auto-scrolling text, and turn icon.
3. `src/main.cpp`: Update BLE image rendering logic, mode loop switching, and long-press event handlers for `ZOOM_BTN`.

### Android Companion App (`TYMAP/app/`):
1. `service/NavigationService.kt`: Ensure BLE updates push Google Maps turn data and JPEG frames in sync.
2. `service/GMapsNotificationListener.kt`: Ensure high priority extraction of turn notifications.
3. `ui/SettingsFragment.kt`: Add settings toggle for default MAP HUD view & Auto-Adaptive mode.

---

## 4. Verification Plan

### Automated Build Verification:
- Build ESP32 firmware using PlatformIO CLI: `pio run -e esp32-s3-devkitc-1`.
- Build Android project using Gradle wrapper: `./gradlew assembleDebug`.

### Manual Hardware & UI Verification:
- Test mode switching via physical GPIO0 button.
- Test Floating Card toggle via long-press GPIO1 button.
- Verify smooth street name scrolling on long Google Maps street names.
- Verify 5s turn popup behavior when in HUD mode.
