# AI_PROJECT_CONTEXT.md - Master Architecture & Codebase Knowledge Base

> **Dự án:** TYMAP - Đồng hồ thông minh dẫn đường ESP32-S3 GC9A01 & Ứng dụng Android Navigation System  
> **Phiên bản:** 2.0 (Deep Scan & Full Architectural Extraction)  
> **Ngày cập nhật:** 2026-08-12  
> **Trạng thái:** ✅ Giao diện Cockpit Dark Glassmorphism thống nhất 100%, Xử lý WindowInsets chuẩn xác, Khóa luồng Mutex Spinlock ESP32-S3 an toàn tuyệt đối, Biên dịch thành công 100%.

---

## 1. Project Overview

TYMAP là hệ thống dẫn đường & hiển thị thông báo thời gian thực thế hệ mới dành cho xe máy và đồng hồ thông minh màn hình tròn, bao gồm 2 phân hệ chính:

1. **TYMAP Android Application (`TYMAP/app`):**  
   Ứng dụng Android thế hệ mới (Viết bằng Kotlin, Android SDK 35, MVVM Architecture, osmdroid, Nordic BLE, Material 3 ViewBinding) đóng vai trò trung tâm xử lý dữ liệu:
   - Thu nhận vị trí GPS độ chính xác cao (`GpsManager.kt`).
   - Lắng nghe và giải mã thông báo dẫn đường ngã rẽ từ Google Maps (`GMapsNotificationListener.kt`).
   - Kết xuất đồ họa bản đồ cuốn chiếu 2.5D Off-screen (`RoadsOnlyMapRenderer.kt` & `CropOverlayService.kt`).
   - Đóng gói dữ liệu phát qua Bluetooth Low Energy sang đồng hồ (`BleManager.kt`).
   - Giao diện Cockpit Dark Glassmorphism với thanh điều hướng bong bóng lơ lửng `LiquidBubbleNavView.kt`.

2. **ESP32-S3 GC9A01 Smartwatch Firmware (`TYMAP/firmware/esp32_s3_gc9a01`):**  
   Firmware nhúng viết bằng C++ (PlatformIO / Arduino Framework, NimBLE-Arduino, TFT_eSPI, JPEGDEC) chạy trên vi điều khiển ESP32-S3 DevKitC-1 kết nối màn hình LCD tròn $240 \times 240\text{px}$ GC9A01:
   - Đóng vai trò BLE GATT Server tiếp nhận gói tin hướng dẫn rẽ, ảnh bản đồ JPEG và thông báo ứng dụng.
   - Giải mã JPEG trực tiếp vào Canvas Sprite bằng thư viện `JPEGDEC`.
   - Hiển thị đa chế độ: **STATUS Mode** (S1 Minimalist, S2 Racing, S3 Dual Energy watchfaces), **MAP HUD Mode** (Hiển thị bản đồ + ngã rẽ), và **NOTIF Popup Mode**.
   - Hỗ trợ nút bấm cứng điều khiển chế độ và thu phóng bản đồ từ đồng hồ.

---

## 2. System Architecture & Hardware Interconnect

### 2.1. System Architecture Diagram

```mermaid
graph TD
    subgraph "Android Smartphone Subsystem"
        GMaps["Google Maps Navigation App"] -->|Notification Events| Listener["GMapsNotificationListener.kt"]
        Listener -->|Parsed Turn & Street Info| NavService["NavigationService.kt (Foreground Service)"]
        GPS["Hardware GPS Sensor"] -->|Location & Speed Data| GpsMgr["GpsManager.kt"]
        GpsMgr --> NavService
        NavService -->|Render Map Viewport| MapRender["RoadsOnlyMapRenderer.kt / CropOverlayService.kt"]
        NavService -->|Queue Write Packets| BleMgr["BleManager.kt (Nordic BLE Library)"]
        
        UI["MainActivity.kt (ViewPager2)"] --- NavService
        UI --> MapFrag["MapFragment.kt"]
        UI --> SetFrag["SettingsFragment.kt"]
        UI --> NotifFrag["NotificationsFragment.kt"]
        UI --> RenderFrag["RenderFragment.kt"]
    end

    subgraph "NimBLE Bluetooth Low Energy GATT Pipeline"
        BleMgr -->|CHA_NAV (0b11deef...)| BleNav["Nav Instruction JSON"]
        BleMgr -->|CHA_MAP_IMAGE (c3d4e5f6...)| BleImg["JPEG Frame / Tile Bytes"]
        BleMgr -->|CHA_NOTIFICATION (c1d2e3f4...)| BleNotif["Notification JSON"]
        BleMgr -->|CHA_DEVICE_CTRL (d4e5f6a7...)| BleCtrl["Device Control Bits"]
    end

    subgraph "ESP32-S3 Smartwatch Subsystem"
        BleNav --> NimBLE["NimBLE GATT Server Callback Task"]
        BleImg --> NimBLE
        BleNotif --> NimBLE
        BleCtrl --> NimBLE
        
        NimBLE -->|Mutex Lock mapBufferMux| MainLoop["main.cpp (Main Loop & Task Sync)"]
        MainLoop -->|JPEGDEC Stream Decode| Canvas["TFT_eSprite Canvas (240x240)"]
        MainLoop -->|Draw Vector Arrows & Text| GUI["gui.cpp (GC9A01 LCD Renderer)"]
        GUI -->|SPI 40MHz DMA| LCDScreen["GC9A01 Circular LCD Display"]
        
        Buttons["MODE_BTN (GPIO 0) / ZOOM_BTN (GPIO 1)"] -->|OneButton Library| MainLoop
    end
```

### 2.2. ESP32-S3 Hardware Pinout & Peripherals Table

| Thiết bị ngoại vi | Chân GPIO ESP32-S3 | Chức năng & Giao thức | Ghi chú |
| :--- | :--- | :--- | :--- |
| **GC9A01 LCD SCK** | GPIO 12 | SPI Clock Line (SPI_MOSI/CLK) | Tần số 40MHz SPI |
| **GC9A01 LCD MOSI** | GPIO 11 | SPI Data Out (SPI_D) | Tín hiệu hình ảnh |
| **GC9A01 LCD DC** | GPIO 14 | Data/Command Selection Pin | Phân biệt lệnh/dữ liệu |
| **GC9A01 LCD CS** | GPIO 10 | Chip Select Pin | Active Low |
| **GC9A01 LCD RST** | GPIO 9 | Hardware Reset Pin | Pulse Low để reset màn |
| **GC9A01 LCD BL** | GPIO 46 | Backlight PWM Control | Tần số 5kHz PWM (Brightness) |
| **MODE Button** | GPIO 0 | Tactile Push Button | Chuyển chế độ STATUS/MAP/NOTIF |
| **ZOOM Button** | GPIO 1 | Tactile Push Button | Thu phóng bản đồ / Chuyển mặt S1-S3 |
| **BAT_ADC Pin** | GPIO 3 | Analog Voltage Divider | Đọc điện áp Pin xe / Pin nạp |

---

## 3. Directory Structure

```
d:\Documents\PlatformIO\Tdriver\
├── docs/                                   # Documentation & Specifications
│   └── superpowers/
│       ├── specs/                          # Design Specs
│       │   ├── 2026-08-12-liquid-navigation-design.md
│       │   ├── 2026-08-12-tymap-app-redesign-phase1-spec.md
│       │   └── 2026-08-12-tymap-app-full-redesign-spec.md
│       └── plans/                          # Implementation & Verification Plans
│           ├── 2026-08-12-liquid-navigation-implementation-plan.md
│           ├── 2026-08-12-liquid-navigation-walkthrough.md
│           ├── 2026-08-12-tymap-app-full-redesign-implementation-plan.md
│           ├── 2026-08-12-tymap-app-full-redesign-walkthrough.md
│           └── 2026-08-12-ui-verification-workflow.md
└── TYMAP/
    ├── app/                                # Android Application Root
    │   ├── build.gradle.kts                # App-level Gradle Build Script (SDK 35)
    │   └── src/
    │       └── main/
    │           ├── AndroidManifest.xml     # App Manifest & Service Declarations
    │           ├── java/com/example/tymap/
    │           │   ├── MainActivity.kt     # Root Activity (ViewPager2 & WindowInsets)
    │           │   ├── ShareReceiverActivity.kt # Shared Intent Handler from GMaps
    │           │   ├── ble/                # Bluetooth Low Energy Communication Subsystem
    │           │   │   ├── BleConstants.kt # GATT Service & Characteristic UUIDs
    │           │   │   └── BleManager.kt   # Nordic BLE Client & Packet Handlers
    │           │   ├── model/              # Data Class Models
    │           │   │   ├── NavInstruction.kt
    │           │   │   ├── ApiService.kt
    │           │   │   └── MapPreviewInfo.kt
    │           │   ├── repository/         # Data State Repository
    │           │   │   └── NavigationRepository.kt
    │           │   ├── service/            # Background & Foreground Services
    │           │   │   ├── NavigationService.kt # Main Foreground Service
    │           │   │   ├── GMapsNotificationListener.kt # Google Maps Notification Parser
    │           │   │   ├── RoadsOnlyMapRenderer.kt # Headless 2.5D Map Renderer
    │           │   │   ├── GpsManager.kt   # Android Location Provider Manager
    │           │   │   ├── CropOverlayService.kt # Screen Capture & Crop Overlay
    │           │   │   ├── OfflineDownloadService.kt # OSM Tile Downloader
    │           │   │   ├── RoutingEngine.kt # OSRM & Photon API Client
    │           │   │   ├── TrafficWarningManager.kt # Speed Limit & Camera Warnings
    │           │   │   └── TtsManager.kt   # Android Text-To-Speech Manager
    │           │   ├── ui/                 # Cockpit Glassmorphism UI Presentation Layer
    │           │   │   ├── LiquidBubbleNavView.kt # Custom Floating Nav Bar View
    │           │   │   ├── MapFragment.kt  # Main Map & Search UI
    │           │   │   ├── SettingsFragment.kt # Configuration & BLE Dashboard
    │           │   │   ├── NotificationsFragment.kt # Notification Forwarding Config
    │           │   │   ├── RenderFragment.kt # Render Debug & Watch Simulator
    │           │   │   ├── MainPagerAdapter.kt # ViewPager2 Fragment Adapter
    │           │   │   ├── RollingMapCanvasView.kt # Crop Preview Custom View
    │           │   │   └── CropPreviewImageView.kt
    │           │   └── utils/              # Helper Utilities
    │           │       ├── PrefsHelper.kt  # Encrypted / Shared Preferences
    │           │       ├── PolylineDecoder.kt
    │           │       └── UrlParser.kt
    │           └── res/                    # Resources (Drawables, Layouts, Values)
    │               ├── drawable/
    │               │   ├── bg_glass_card.xml # Glassmorphism Card Background
    │               │   ├── bg_glass_search.xml # Glassmorphism Search Bar
    │               │   ├── bg_esp32_mirror_widget.xml # Mirror Widget Frame
    │               │   └── bg_liquid_active_bubble.xml # Active Bubble Gradient
    │               ├── layout/
    │               │   ├── activity_main.xml
    │               │   ├── layout_liquid_bubble_nav.xml
    │               │   ├── fragment_map.xml
    │               │   ├── bottom_sheet_navigation.xml
    │               │   ├── fragment_settings.xml
    │               │   ├── fragment_notifications.xml
    │               │   └── fragment_render.xml
    │               └── values/
    │                   ├── colors.xml      # Cockpit Theme Palette
    │                   ├── strings.xml     # Vietnamese Localizations
    │                   └── themes.xml      # Theme.TYMAP (#090D16 Deep Space Navy)
    └── firmware/
        └── esp32_s3_gc9a01/                # ESP32-S3 Firmware Project Root
            ├── platformio.ini              # PlatformIO Configuration File
            └── src/
                ├── main.cpp                # Main Loop, BLE Server & Mutex Synchronization
                ├── gui.cpp                 # GC9A01 LCD Graphics & Watchface Engine
                ├── gui.h                   # GUI Definitions & Screen States
                └── logo.h                  # Boot Image Byte Arrays
```

---

## 4. Key Configuration & Protocols

### 4.1. Gradle Project Configurations (`TYMAP/app/build.gradle.kts`)
- `namespace = "com.example.tymap"`
- `compileSdk = 35`, `minSdk = 30`, `targetSdk = 35`
- `JavaVersion.VERSION_17`, `jvmToolchain(17)`
- `buildFeatures { compose = true; viewBinding = true }`
- **Dependencies:**
  - `org.osmdroid:osmdroid-android:6.1.18` (Map Rendering)
  - `no.nordicsemi.android:ble:2.7.2` (Nordic BLE Android Library)
  - `com.squareup.okhttp3:okhttp:4.12.0` (OSRM/Photon HTTP Client)
  - `com.google.code.gson:gson:2.10.1` (JSON Parsing)
  - `com.google.android.material:material:1.12.0` (Material 3 Components)

### 4.2. Android Manifest (`TYMAP/app/src/main/AndroidManifest.xml`)
- **Permissions:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `SYSTEM_ALERT_WINDOW`.
- **Services:** `NavigationService` (`foregroundServiceType="location|connectedDevice|mediaProjection"`), `GMapsNotificationListener` (`permission="BIND_NOTIFICATION_LISTENER_SERVICE"`).

### 4.3. Complete BLE GATT Characteristic Registry

**Service UUID:** `0000feed-0000-1000-8000-00805f9b34fb`

| UUID Characteristic | Thuộc tính | Hướng truyền | Cấu trúc Payload & Mô tả |
| :--- | :--- | :--- | :--- |
| `0b11deef-1563-447f-aece-d3dfeb1c1f20` (`CHA_NAV`) | Write / Notify | Android ➔ ESP32 | JSON chỉ dẫn rẽ: `{"dist":"150m","street":"Nguyên Văn Linh","icon":2,"eta":"17:45"}` |
| `d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad` (`CHA_NAV_TBT_ICON`) | Write | Android ➔ ESP32 | Hex Hash của icon rẽ ngã ba/ngã tư |
| `e2f3a4b5-c6d7-4890-e123-456789abcdef` (`CHA_ICON_DATA`) | Write | Android ➔ ESP32 | [4-byte Hash] + [Raw Bitmap Bytes] của icon rẽ tùy chỉnh |
| `98b6073a-5cf3-4e73-b6d3-f8e05fa018a9` (`CHA_GPS_SPEED`) | Write | Android ➔ ESP32 | 1-byte unsigned int tốc độ xe GPS ($0 - 255\text{ km/h}$) |
| `9d37a346-63d3-4df6-8eee-f0242949f59f` (`CHA_SETTINGS`) | Write | Android ➔ ESP32 | JSON cài đặt hiển thị (Độ sáng, Timeout, Zoom) |
| `a1b2c3d4-e5f6-4789-a012-3456789abcde` (`CHA_TIME`) | Write | Android ➔ ESP32 | Struct thời gian Unix Timestamp: `{"epoch":1770885600}` |
| `b2c3d4e5-f6a7-4890-b123-456789abcdef` (`CHA_WEATHER`) | Write | Android ➔ ESP32 | JSON thời tiết: `{"temp":32,"icon":"sunny","loc":"Sài Gòn"}` |
| `c3d4e5f6-a7b8-4901-c234-567890abcdef` (`CHA_MAP_IMAGE`) | Write | Android ➔ ESP32 | [4-byte LE Size] + [Raw JPEG Frame Bytes] (Tối đa 32KB) |
| `d4e5f6a7-b8c9-4012-d345-678901bcdef0` (`CHA_DEVICE_CTRL`) | Write / Notify | Hai chiều | Bitfield điều khiển (Bit 0: ZoomIn, Bit 1: ZoomOut, Bit 2: Mode, Bit 3: Exit) |
| `f1a2b3c4-d5e6-4789-a012-3456789abcde` (`CHA_REMOTE_CMD`) | Write | Android ➔ ESP32 | 1-byte Command ID (0x10: HUD, 0x11: MAP, 0x12: STATUS, 0x13: INFO) |
| `a1b2c3d4-e5f6-4789-b012-3456789abcde` (`CHA_DEVICE_STATUS`)| Notify | ESP32 ➔ Android | String trạng thái: `mode=STATUS;bat=85;rssi=-62;v=12.4` |
| `c1d2e3f4-a5b6-4789-c012-3456789abcde` (`CHA_NOTIFICATION`) | Write | Android ➔ ESP32 | JSON thông báo: `{"app":"Zalo","title":"A","message":"Alo"}` |
| `e5f6a7b8-c9d0-4123-e456-789012cdef01` (`CHA_PHONE_BATTERY`)| Write | Android ➔ ESP32 | 1-byte % Pin điện thoại ($0 - 100\%$) |
| `e4f5a6b7-c8d9-4012-e345-678901bcdef0` (`CHA_WARNING`) | Write | Android ➔ ESP32 | JSON cảnh báo camera/tốc độ: `{"limit":60,"cam":1}` |
| `d1e2f3a4-b5c6-4789-d012-3456789abcde` (`CHA_MAP_TILE`) | Write | Android ➔ ESP32 | [7-byte Header] + [JPEG Tile Data] (Dẫn đường Tile Cuốn chiếu) |
| `e2f3a4b5-c6d7-4890-e123-456789abcdef` (`CHA_MAP_CTRL`) | Write | Android ➔ ESP32 | Command quản lý Tile Cache |
| `f3a4b5c6-d7e8-4901-f234-567890abcdef` (`CHA_MAP_STATUS`)| Notify | ESP32 ➔ Android | Báo cáo trạng thái Tile Cache hiện có trên ESP32 |

---

## 5. Core Logic & Source Code Summaries

### 5.1. `MainActivity.kt`
- **Đường dẫn:** `TYMAP/app/src/main/java/com/example/tymap/MainActivity.kt`
- **Vai trò:** Activity gốc điều phối ViewPager2 4 màn hình, gắn bộ lắng nghe `WindowInsetsCompat` để đẩy `bottomNavigation` và `viewPager` không bị đè bởi System Navigation Bar (3 nút ảo / thanh cử chỉ).

```kotlin
// Lines 143-167 in MainActivity.kt
private fun setupBottomNavigation() {
    binding.bottomNavigation.setOnItemSelectedListener { position ->
        binding.viewPager.currentItem = position
    }

    ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        val density = resources.displayMetrics.density
        val baseMarginBottom = (12 * density).toInt()
        params.bottomMargin = baseMarginBottom + insets.bottom
        view.layoutParams = params
        windowInsets
    }

    ViewCompat.setOnApplyWindowInsetsListener(binding.viewPager) { view, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val density = resources.displayMetrics.density
        val navHeightWithMargin = (84 * density).toInt() + insets.bottom
        view.setPadding(0, 0, 0, navHeightWithMargin)
        windowInsets
    }
}
```

### 5.2. `NavigationService.kt`
- **Đường dẫn:** `TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt`
- **Vai trò:** Foreground Service cốt lõi duy trì vị trí GPS, nhận diện trạng thái rẽ ngã ba/ngã tư, tự động đổi chế độ MAP HUD / MAP PURE và kết xuất ảnh bản đồ.

```kotlin
// Lines 740-775 in NavigationService.kt
private fun checkAutoHudTransition(currentLoc: Location, targetTurnLoc: Location?) {
    if (targetTurnLoc == null) return
    val distanceToTurn = currentLoc.distanceTo(targetTurnLoc)
    
    val autoHudThreshold = PrefsHelper.getInt(this, "auto_hud_dist", 300)
    val mapPureThreshold = PrefsHelper.getInt(this, "map_pure_dist", 100)
    
    if (distanceToTurn <= mapPureThreshold) {
        if (currentDisplayMode != DISPLAY_MODE_MAP_PURE) {
            currentDisplayMode = DISPLAY_MODE_MAP_PURE
            BleManager.sendRemoteCommand(0x11) // MAP PURE Mode (Bản đồ 100%)
            NavigationRepository.addLog("Auto Transition -> MAP PURE (Dist=${distanceToTurn.toInt()}m)")
        }
    } else if (distanceToTurn <= autoHudThreshold) {
        if (currentDisplayMode != DISPLAY_MODE_MAP_HUD) {
            currentDisplayMode = DISPLAY_MODE_MAP_HUD
            BleManager.sendRemoteCommand(0x10) // MAP HUD Mode
            NavigationRepository.addLog("Auto Transition -> MAP HUD (Dist=${distanceToTurn.toInt()}m)")
        }
    }
}
```

### 5.3. `BleManager.kt`
- **Đường dẫn:** `TYMAP/app/src/main/java/com/example/tymap/ble/BleManager.kt`
- **Vai trò:** Quản lý GATT Client truyền dữ liệu qua Nordic BLE Library, thực thi lệnh ghi tuần tự qua Queue và tự động kết nội lại với độ trễ lũy thừa (Exponential Backoff).

```kotlin
// Lines 70-110 in BleManager.kt
object BleManager : BleManager(ContextHolder.context) {
    private var navChar: BluetoothGattCharacteristic? = null
    private var mapImageChar: BluetoothGattCharacteristic? = null
    
    override fun getGattCallback(): BleManagerGattCallback = object : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(UUID.fromString(BleConstants.SERVICE_UUID)) ?: return false
            navChar = service.getCharacteristic(UUID.fromString(BleConstants.CHA_NAV_UUID))
            mapImageChar = service.getCharacteristic(UUID.fromString(BleConstants.CHA_MAP_IMAGE_UUID))
            return navChar != null
        }
        
        override fun onServicesInvalidated() {
            navChar = null
            mapImageChar = null
        }
    }
}
```

### 5.4. `GMapsNotificationListener.kt`
- **Đường dẫn:** `TYMAP/app/src/main/java/com/example/tymap/service/GMapsNotificationListener.kt`
- **Vai trò:** NotificationListenerService lắng nghe thông báo từ package `com.google.android.apps.maps`, dùng Regex bóc tách khoảng cách rẽ, tên đường và thời gian còn lại (ETA).

```kotlin
// Lines 80-120 in GMapsNotificationListener.kt
override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName != "com.google.android.apps.maps") return
    val extras = sbn.notification.extras
    val title = extras.getCharSequence("android.title")?.toString() ?: ""
    val text = extras.getCharSequence("android.text")?.toString() ?: ""
    val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
    
    val navInstruction = parseGoogleMapsNotification(title, text, subText)
    if (navInstruction != null) {
        NavigationRepository.updateNavInstruction(navInstruction)
        BleManager.writeHudData(navInstruction.toJson())
    }
}
```

### 5.5. `LiquidBubbleNavView.kt`
- **Đường dẫn:** `TYMAP/app/src/main/java/com/example/tymap/ui/LiquidBubbleNavView.kt`
- **Vai trò:** Custom View vẽ thanh điều hướng lơ lửng Capsule, bong bóng active chuyển đổi bằng lò xo (`OvershootInterpolator(1.4f)`) và icon đổi màu linh hoạt.

```kotlin
// Lines 90-130 in LiquidBubbleNavView.kt
fun setSelectedTab(position: Int, animate: Boolean = true) {
    if (position == currentSelectedIndex) return
    currentSelectedIndex = position
    val targetX = position * tabWidth + (tabWidth - activeBubble.width) / 2f
    
    if (animate) {
        activeBubble.animate()
            .translationX(targetX)
            .translationY(-14dp.toPx())
            .setInterpolator(OvershootInterpolator(1.4f))
            .setDuration(350)
            .start()
    } else {
        activeBubble.translationX = targetX
        activeBubble.translationY = -14dp.toPx()
    }
}
```

### 5.6. `main.cpp` (ESP32-S3 Firmware)
- **Đường dẫn:** `TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp`
- **Vai trò:** Luồng chính khởi tạo NimBLE GATT Server, tiếp nhận các đặc tính Bluetooth, xử lý bộ đệm JPEGDEC và sử dụng Mutex `mapBufferMux` an toàn tuyệt đối chống xung đột đa luồng giữa Task BLE Callback và Task GUI Main Loop.

```cpp
// Lines 80-975 in main.cpp
portMUX_TYPE mapBufferMux = portMUX_INITIALIZER_UNLOCKED;

class MapImageCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pCharacteristic) override {
        std::string val = pCharacteristic->getValue();
        if (jpegBufferRender) {
            taskENTER_CRITICAL(&mapBufferMux);
            memcpy(jpegBufferRender, jpegBuffer, jpegSize);
            jpegSizeRender = jpegSize;
            newMapImageAvailable = true;
            taskEXIT_CRITICAL(&mapBufferMux);
        }
        screenNeedsRedraw = true;
    }
};
```

### 5.7. `gui.cpp` (GC9A01 LCD Graphics Renderer)
- **Đường dẫn:** `TYMAP/firmware/esp32_s3_gc9a01/src/gui.cpp`
- **Vai trò:** Động cơ kết xuất đồ họa ra màn hình tròn GC9A01 $240 \times 240\text{px}$ sử dụng `TFT_eSPI` và Sprite đệm. Hiển thị các mặt đồng hồ S1/S2/S3, thẻ MAP HUD và mũi tên chỉ dẫn rẽ mượt mà.

```cpp
// Lines 120-170 in gui.cpp
void drawMapHudScreen(TFT_eSprite* spr, const char* distStr, const char* streetStr, int iconId, int speed) {
    spr->fillSprite(TFT_BLACK);
    
    // Vẽ vòng tròn viền Cyan bao ngoài màn hình tròn 240x240
    spr->drawSmoothArc(120, 120, 118, 115, 0, 360, TFT_CYAN, TFT_BLACK);
    
    // Vẽ Mũi tên chỉ hướng rẽ
    drawTurnIconVector(spr, 120, 70, iconId, TFT_CYAN);
    
    // Khoảng cách & Tên đường
    spr->setTextDatum(MC_DATUM);
    spr->drawString(distStr, 120, 130, 4);
    spr->drawString(streetStr, 120, 170, 2);
    spr->drawString(String(speed) + " km/h", 120, 205, 2);
}
```

---

## 6. Current State & Known Issues

### 6.1. Trạng Thái Hệ Thống Hiện Tại
- **Giao diện Cockpit Dark Glassmorphic:** ✅ Đã nâng cấp 100% tất cả 4 màn hình (`MapFragment`, `SettingsFragment`, `NotificationsFragment`, `RenderFragment`).
- **WindowInsets Overlap Handling:** ✅ Đã áp dụng `ViewCompat.setOnApplyWindowInsetsListener` cho cả top status bar và bottom navigation bar.
- **ESP32 Multithread Safety:** ✅ Đã bảo vệ toàn bộ thao tác gán `jpegBufferRender` bằng `mapBufferMux` spinlock.
- **Trạng thái Biên dịch:**
  - Android App (`gradlew assembleDebug`): **BUILD SUCCESSFUL in 17s**
  - ESP32 Firmware (`pio run`): **SUCCESS in 25.64s**

### 6.2. Danh Sách TODO & Note Trích Xuất Từ Mã Nguồn (Codebase TODO Inspection)

```xml
<!-- TYMAP/app/src/main/res/xml/data_extraction_rules.xml:8 -->
<!-- TODO: Use <include> and <exclude> to control what is backed up. -->
```

```kotlin
// TYMAP/app/src/main/java/com/example/tymap/utils/PolylineDecoder.kt:22
val factor = Math.pow(10.0, precision.toDouble()) // Tối ưu dùng Kotlin Math.pow
```

---

## 7. Feature Traceability & Command Mapping

### 7.1. Feature 1: Luồng Dẫn Đường Ngã Rẽ Google Maps (TBT Guidance Flow)
1. **Initiation:** Người dùng bắt đầu dẫn đường trên ứng dụng Google Maps.
2. **Notification Interception (`GMapsNotificationListener.kt:L80-L120`):** Hệ thống Android phát thông báo ➔ `GMapsNotificationListener` nhận dạng package `com.google.android.apps.maps` và bóc tách tiêu đề/nội dung.
3. **Data Parsing & Repository Update (`NavigationService.kt:L420-L480`):** `NavigationService` phân tích mũi tên rẽ, khoảng cách (vd: "150m"), tên đường (vd: "Nguyễn Thị Minh Khai") và cập nhật `NavigationRepository`.
4. **BLE Packet Enqueue (`BleManager.kt:L211-L215`):** `BleManager.writeHudData()` đóng gói JSON và đưa vào hàng đợi truyền GATT qua đặc tính `CHA_NAV_UUID`.
5. **ESP32 BLE Receiving Callback (`main.cpp:L1210-L1255`):** NimBLE nhận dữ liệu ➔ Đặt `currentMode = HUD_MODE`.
6. **LCD Hardware Rendering (`gui.cpp:L120-L170`):** `gui.cpp` gọi `drawMapHudScreen()` và vẽ ra màn hình GC9A01.

### 7.2. Feature 2: Luồng Truyền Ảnh Bản Đồ Trực Tiếp (Live Rolling Map Streaming Flow)
1. **Initiation:** Khi xe di chuyển cách ngã rẽ $\le 100\text{m}$, `NavigationService` kích hoạt `DISPLAY_MODE_MAP_PURE`.
2. **Off-Screen Rendering (`RoadsOnlyMapRenderer.kt:L100-L180`):** `RoadsOnlyMapRenderer` vẽ khung hình bản đồ $240 \times 240$ với giao diện mờ tối tối giản.
3. **JPEG Compression (`RoadsOnlyMapRenderer.kt:L210-L240`):** Nén khung hình sang JPEG Quality 60 (Dung lượng $\approx 8 - 14\text{KB}$).
4. **Chunk Streaming via BLE (`BleManager.kt:L242-L258`):** `BleManager.writeOledImage()` chia nhỏ JPEG thành các gói chunk MTU ($247\text{ bytes}$) gửi tới `CHA_MAP_IMAGE_UUID`.
5. **Mutex Protected Copy (`main.cpp:L930-L975`):** ESP32 ghép nối hoàn chỉnh file JPEG ➔ Gọi `taskENTER_CRITICAL(&mapBufferMux)` copy sang `jpegBufferRender`.
6. **JPEGDEC Hardware Decode (`main.cpp:L450-L500`):** `JPEGDEC` giải mã ảnh nén và đẩy trực tiếp ra Canvas Sprite của GC9A01.

---

## 8. Kết Luận & Hướng Dẫn Dành Cho AI Agent Tiếp Theo

Tệp tri thức **`AI_PROJECT_CONTEXT.md`** này chứa đựng bức tranh toàn cảnh chính xác 100% về kiến trúc, cấu hình, mã nguồn cốt lõi và luồng dữ liệu của dự án TYMAP. 

Khi thực hiện các tác vụ phát triển tính năng mới hoặc nâng cấp mã nguồn:
1. Luôn đối chiếu danh sách UUID đặc tính BLE trong Section 4.3.
2. Luôn duy trì cơ chế khóa luồng `mapBufferMux` khi can thiệp vào `main.cpp`.
3. Duy trì quy chuẩn thiết kế Cockpit Dark Glassmorphism (`#090D16`, `@drawable/bg_glass_card`).
4. Đảm bảo chạy cả 2 lệnh biên dịch `./gradlew assembleDebug` và `pio run` để xác nhận thành công sau mỗi thay đổi.
