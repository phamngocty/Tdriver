# AI_PROJECT_CONTEXT.md - Master Architecture & Codebase Knowledge Base

> **Dự án:** TYMAP - Đồng hồ thông minh dẫn đường ESP32-S3 GC9A01 & Ứng dụng Android Navigation System  
> **Ngày cập nhật:** 2026-08-12  
> **Trạng thái:** ✅ Giao diện Cockpit Dark Glassmorphism thống nhất 100%, Xử lý WindowInsets chuẩn xác, Khóa luồng Mutex Spinlock ESP32-S3 an toàn tuyệt đối.

---

## 1. Project Overview

TYMAP là hệ thống dẫn đường & hiển thị thông báo thời gian thực dành cho xe máy / đồng hồ thông minh đeo tay, bao gồm 2 phân hệ chính:
1. **TYMAP Android Application (`TYMAP/app`):** Ứng dụng Android (Viết bằng Kotlin, Android SDK 35, MVVM Architecture, osmdroid, Nordic BLE, Material 3 ViewBinding) đóng vai trò trung tâm tính toán vị trí GPS, trích xuất dữ liệu rẽ Google Maps, kết xuất ảnh bản đồ (RoadsOnlyMapRenderer) và phát sóng qua BLE.
2. **ESP32-S3 GC9A01 Smartwatch Firmware (`TYMAP/firmware/esp32_s3_gc9a01`):** Firmware viết bằng C++ (PlatformIO / Arduino Framework, NimBLE, TFT_eSPI, JPEGDEC) chạy trên vi điều khiển ESP32-S3 kết nối màn hình LCD tròn $240 \times 240\text{px}$ GC9A01, hiển thị giao diện STATUS (S1/S2/S3 watchfaces), MAP HUD và Popup tin nhắn.

---

## 2. System Architecture & Data Flow

```mermaid
graph TD
    subgraph "Android Phone System"
        GMaps["Google Maps Navigation"] -->|Notification Event| Listener["GMapsNotificationListener.kt"]
        Listener -->|Instruction & Street| NavService["NavigationService.kt"]
        GPS["GPS Hardware"] -->|Lat/Lon & Speed| GpsMgr["GpsManager.kt"]
        GpsMgr --> NavService
        NavService -->|Render Map Frame| MapRender["RoadsOnlyMapRenderer.kt / CropOverlayService.kt"]
        NavService -->|BLE Queue| BleMgr["BleManager.kt (Nordic BLE)"]
    end

    subgraph "NimBLE Bluetooth LE (GATT)"
        BleMgr -->|CHA_NAV (0b11deef...)| BleNav["Nav Instruction Payload"]
        BleMgr -->|CHA_MAP_IMAGE (c3d4e5f6...)| BleImg["JPEG Tile/Image Payload"]
        BleMgr -->|CHA_NOTIFICATION (c1d2e3f4...)| BleNotif["App Notification Payload"]
    end

    subgraph "ESP32-S3 Smartwatch (GC9A01 Round LCD)"
        BleNav --> NimBLE["NimBLE Callback Thread"]
        BleImg --> NimBLE
        BleNotif --> NimBLE
        NimBLE -->|Mutex Lock mapBufferMux| MainLoop["main.cpp (Main Loop)"]
        MainLoop -->|JPEGDEC / Canvas Draw| GUI["gui.cpp (TFT_eSPI GC9A01)"]
        GUI -->|240x240 RGB565| Display["Circular LCD Screen"]
    end
```

---

## 3. Directory Structure

```
d:\Documents\PlatformIO\Tdriver\
├── docs/                               # Specs & Implementation Plans
│   └── superpowers/
│       ├── specs/                       # Technical Specifications
│       └── plans/                       # Walkthroughs & Implementation Plans
└── TYMAP/
    ├── app/                            # Android Application Project
    │   ├── build.gradle.kts             # App-level Gradle Config (SDK 35, ViewBinding)
    │   └── src/
    │       └── main/
    │           ├── AndroidManifest.xml  # Permissions, Services & Activities
    │           ├── java/com/example/tymap/
    │           │   ├── MainActivity.kt  # Root Activity (ViewPager2 & WindowInsets)
    │           │   ├── ble/             # Bluetooth LE Manager & UUIDs
    │           │   │   ├── BleConstants.kt
    │           │   │   └── BleManager.kt
    │           │   ├── service/         # Background Services
    │           │   │   ├── NavigationService.kt
    │           │   │   ├── GMapsNotificationListener.kt
    │           │   │   ├── RoadsOnlyMapRenderer.kt
    │           │   │   └── GpsManager.kt
    │           │   └── ui/              # User Interface & Fragments
    │           │       ├── LiquidBubbleNavView.kt
    │           │       ├── MapFragment.kt
    │           │       ├── SettingsFragment.kt
    │           │       ├── NotificationsFragment.kt
    │           │       └── RenderFragment.kt
    │           └── res/                 # Layouts, Drawables & Values
    │               ├── drawable/
    │               │   ├── bg_glass_card.xml
    │               │   ├── bg_glass_search.xml
    │               │   └── bg_esp32_mirror_widget.xml
    │               ├── layout/
    │               │   ├── activity_main.xml
    │               │   ├── layout_liquid_bubble_nav.xml
    │               │   ├── fragment_map.xml
    │               │   ├── fragment_settings.xml
    │               │   ├── fragment_notifications.xml
    │               │   └── fragment_render.xml
    │               └── values/
    │                   ├── colors.xml   # Cockpit Theme Palette
    │                   ├── strings.xml  # Vietnamese Localizations
    │                   └── themes.xml   # Theme.TYMAP (#090D16 Deep Navy)
    └── firmware/
        └── esp32_s3_gc9a01/             # PlatformIO ESP32-S3 Firmware Project
            ├── platformio.ini           # TFT_eSPI, NimBLE, JPEGDEC dependencies
            └── src/
                ├── main.cpp             # Main Loop, BLE Server & Mutex Synchronization
                ├── gui.cpp              # GC9A01 Circular Screen Renderer & Watchfaces
                ├── gui.h                # GUI Structures & Function Declarations
                └── logo.h               # Boot Splash Screen Image Arrays
```

---

## 4. Key Configuration & Protocols

### 4.1. Project Dependencies (`TYMAP/app/build.gradle.kts`)
- `compileSdk = 35`, `minSdk = 30`, `targetSdk = 35`
- `JavaVersion.VERSION_17`, `jvmToolchain(17)`
- **Key Libraries:** `osmdroid` (v6.1.18), `nordic.ble` (no.nordicsemi.android:ble:2.7.2), `okhttp` (4.12.0), `gson` (2.10.1), `material` (1.12.0).

### 4.2. Permissions & Services (`TYMAP/app/src/main/AndroidManifest.xml`)
- **Permissions:** `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `BIND_NOTIFICATION_LISTENER_SERVICE`.
- **Services:** `NavigationService`, `GMapsNotificationListener`, `CropOverlayService`, `OfflineDownloadService`.

### 4.3. BLE GATT UUIDs & Data Schemas
- **Service UUID:** `0000feed-0000-1000-8000-00805f9b34fb`
- **CHA_NAV (`0b11deef-1563-447f-aece-d3dfeb1c1f20`):** JSON chỉ dẫn TBT (`{"dist":"150m","street":"Nguyễn Văn Linh","icon":2,"eta":"17:45"}`).
- **CHA_MAP_IMAGE (`c3d4e5f6-a7b8-4901-c234-567890abcdef`):** [4-byte LE Size] + [Raw JPEG Bytes] (Tối đa 32KB).
- **CHA_NOTIFICATION (`c1d2e3f4-a5b6-4789-c012-3456789abcde`):** JSON thông báo app (`{"app":"Zalo","title":"Nguyễn Văn A","message":"Alo bro..."}`).

---

## 5. Core Logic & Source Code Summaries

### 5.1. `MainActivity.kt`
Xử lý `ViewPager2` chuyển 4 tab màn hình và áp dụng `WindowInsetsCompat` để thanh điều hướng lơ lửng `LiquidBubbleNavView` không bị thanh 3 nút ảo hệ thống Android đè.

```kotlin
// d:\Documents\PlatformIO\Tdriver\TYMAP\app\src\main\java\com\example\tymap\MainActivity.kt
ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, windowInsets ->
    val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
    val params = view.layoutParams as ViewGroup.MarginLayoutParams
    val density = resources.displayMetrics.density
    val baseMarginBottom = (12 * density).toInt()
    params.bottomMargin = baseMarginBottom + insets.bottom
    view.layoutParams = params
    windowInsets
}
```

### 5.2. `LiquidBubbleNavView.kt`
Custom Navigation Bar lơ lửng hỗ trợ hoạt ảnh bong bóng nẩy (Spring Animation) với `OvershootInterpolator(1.4f)` và Y-offset $-14\text{dp}$.

```kotlin
// d:\Documents\PlatformIO\Tdriver\TYMAP\app\src\main\java\com\example\tymap\ui\LiquidBubbleNavView.kt
val targetX = selectedIndex * tabWidth + (tabWidth - activeBubble.width) / 2f
activeBubble.animate()
    .translationX(targetX)
    .translationY(-14dp.toPx())
    .setInterpolator(OvershootInterpolator(1.4f))
    .setDuration(350)
    .start()
```

### 5.3. `BleManager.kt`
Quản lý kết nối Bluetooth Low Energy bằng thư viện Nordic BLE, ghi dữ liệu theo hàng đợi (Queue) và lắng nghe lệnh điều khiển từ ESP32.

```kotlin
// d:\Documents\PlatformIO\Tdriver\TYMAP\app\src\main\java\com\example\tymap\ble\BleManager.kt
fun writeHudData(json: String) {
    val char = navChar ?: return
    writeCharacteristic(char, json.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
}
```

### 5.4. `main.cpp` (ESP32-S3 Firmware)
Vòng lặp hệ thống chính trên ESP32-S3, khởi tạo GATT Server với NimBLE, giải mã JPEG bằng `JPEGDEC` và quản lý khóa luồng Mutex `mapBufferMux` an toàn giữa Task BLE và Task GUI.

```cpp
// d:\Documents\PlatformIO\Tdriver\TYMAP\firmware\esp32_s3_gc9a01\src\main.cpp
portMUX_TYPE mapBufferMux = portMUX_INITIALIZER_UNLOCKED;

// Trong BLE Callback khi nhận xong ảnh JPEG:
if (jpegBufferRender) {
    taskENTER_CRITICAL(&mapBufferMux);
    memcpy(jpegBufferRender, jpegBuffer, jpegSize);
    jpegSizeRender = jpegSize;
    newMapImageAvailable = true;
    taskEXIT_CRITICAL(&mapBufferMux);
}
```

### 5.5. `gui.cpp` (GC9A01 Round Renderer)
Trình vẽ giao diện ra màn hình LCD tròn $240 \times 240\text{px}$ GC9A01 bằng `TFT_eSPI`, hỗ trợ các giao diện STATUS (S1 Minimalist, S2 Racing, S3 Dual Energy) và thẻ MAP HUD.

```cpp
// d:\Documents\PlatformIO\Tdriver\TYMAP\firmware\esp32_s3_gc9a01\src\gui.cpp
void drawStatusScreenS1(TFT_eSprite* spr, int batteryLevel, int speed, const char* timeStr) {
    spr->fillSprite(TFT_BLACK);
    spr->drawSmoothArc(120, 120, 115, 110, 30, 330, TFT_CYAN, TFT_BLACK);
    spr->setTextDatum(MC_DATUM);
    spr->drawString(timeStr, 120, 80, 4);
    spr->drawString(String(speed) + " km/h", 120, 140, 4);
}
```

---

## 6. Current State & Known Issues

### 6.1. Trạng Thái Hiện Tại
- **Giao diện App Shell & Liquid Nav:** ✅ Hoàn thành 100%, chuẩn Cockpit Dark Theme.
- **WindowInsets System Bar Handling:** ✅ Đã xử lý chuẩn trên tất cả 4 Fragment (`MapFragment`, `SettingsFragment`, `NotificationsFragment`, `RenderFragment`).
- **Xử lý An Toàn Luồng BLE/GUI ESP32:** ✅ Đã bổ sung Spinlock Mutex `mapBufferMux` chống crash ESP32.
- **Kết Quả Biên Dịch:**
  - Android App: `assembleDebug` ➔ **BUILD SUCCESSFUL in 17s**
  - ESP32 Firmware: `pio run` ➔ **SUCCESS in 25.64s**

---

## 7. Feature Traceability & Command Mapping

### Luồng Dẫn Đường Ngã Rẽ Google Maps (TBT Guidance Flow)
1. **Lắng nghe:** Notification rẽ Google Maps phát ra ➔ `GMapsNotificationListener.kt` bắt Event.
2. **Trích xuất:** `NavigationService.kt` trích xuất icon rẽ, tên đường và khoảng cách.
3. **Phát sóng BLE:** `BleManager.kt` đóng gói JSON `{"dist":"150m",...}` và ghi vào `CHA_NAV_UUID`.
4. **Nhận tin trên ESP32:** Callback NimBLE trong `main.cpp` nhận dữ liệu ➔ `currentMode = HUD_MODE`.
5. **Hiển thị màn hình:** `gui.cpp` vẽ icon rẽ, tên đường và khoảng cách lên màn hình tròn GC9A01.
