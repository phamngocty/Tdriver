# 🚀 AI PROJECT MASTER CONTEXT: TYMAP ECOSYSTEM (TDRIVER)

> **Document Type:** Master AI Context & Cross-Platform Architecture Knowledge Base  
> **Target Audience:** Large Language Models (LLMs), AI Coding Agents, Principal Software Architects  
> **Generated Date:** 2026-08-28  
> **Corpus / Repository Root:** `d:\Documents\PlatformIO\Tdriver`

---

## 1. Project Overview

**TYMAP (Tdriver)** là một hệ sinh thái dẫn đường và HUD thông minh dành cho xe máy và người lái xe hai bánh tại Việt Nam, bao gồm 4 phân hệ chính hoạt động đồng bộ:

1. **Android Client Application (`TYMAP/app`)**: Ứng dụng Android viết bằng Kotlin (Coroutines, StateFlow, Android Jetpack, Osmdroid, Room/SQLite). Cung cấp giao diện bản đồ Dark Glassmorphism, tìm kiếm hợp nhất (SQLite + Photon), định tuyến tiến trình đa tầng Fast-Track và cảnh báo tốc độ/camera phạt nguội Offline.
2. **ESP32-S3 Hardware HUD Firmware (`TYMAP/firmware/esp32_s3_gc9a01`)**: Firmware C++/PlatformIO chạy trên vi điều khiển ESP32-S3 kết hợp màn hình LCD màu tròn 1.28 inch GC9A01 (240x240). Nhận dữ liệu TBT, tốc độ GPS, thời tiết, thông báo và cảnh báo giao thông qua BLE GATT Profile bằng các gói tin Hex nhị phân siêu nhẹ.
3. **NAS Backend Services (`nas_services/` & `/home/nas152/graphhopper-data`)**: Máy chủ NAS (IP `192.168.1.114`) chạy cụm microservices Docker:
   - **GraphHopper (Port 8989)**: Lõi định tuyến xe máy, né cao tốc, đọc bản đồ `vietnam-latest.osm.pbf`.
   - **Nominatim (Port 8081)**: Reverse geocoding ghim vị trí.
   - **Photon (Port 2322)**: Autocomplete gợi ý tìm kiếm hỗ trợ gõ không dấu.
   - **Fusion Engine (Port 8088)**: Node.js Middleware siêu nhẹ (47MB RAM) thay thế Overpass, nén mảng camera OSM & dữ liệu ngầm.
4. **Git Sync Manager Tool (`gsm/`)**: Công cụ Python/Flask quản trị git multi-remote, Gitea release và OTA binary streaming.

---

## 2. System Architecture & Cross-Platform Data Flow

```text
+-----------------------------------------------------------------------------------+
|                              NAS SERVER (192.168.1.114)                           |
|  +---------------------+   +---------------------+   +--------------------------+ |
|  | GraphHopper (:8989) |   |  Nominatim (:8081)  |   |   Fusion Engine (:8088)  | |
|  | Motorcycle Routing  |   |  Reverse Geocoding  |   |  Node.js Spatial Filter  | |
|  +----------^----------+   +----------^----------+   +------------^-------------+ |
+-------------|-------------------------|---------------------------|---------------+
              | HTTP (Fast-Track)       | HTTP (User-Agent)         | HTTP (POST Polyline)
+-------------v-------------------------v---------------------------v---------------+
|                             ANDROID APP (com.example.tymap)                       |
|  +-----------------------+  +------------------------+  +-----------------------+ |
|  |     RoutingEngine     |  |    SavedPlaceDbHelper  |  | TrafficWarningManager | |
|  |  Progressive 3-Tier   |  |   SQLite Local DB      |  |  Haversine RAM Cache  | |
|  +-----------+-----------+  +-----------+------------+  +-----------+-----------+ |
|              |                          |                           |             |
|              +--------------------------+---------------------------+             |
|                                         |                                         |
|                          +--------------v---------------+                         |
|                          |   BleManager (Nordic BLE)    |                         |
|                          +--------------+---------------+                         |
+-----------------------------------------|-----------------------------------------+
                                          | Bluetooth Low Energy (GATT Server)
                                          | Binary Hex Packets / Chunks
+-----------------------------------------v-----------------------------------------+
|                        ESP32-S3 GC9A01 LCD HUD (TYMAP-S3)                         |
|  +------------------------------------------------------------------------------+ |
|  | NimBLE Callbacks: CHA_NAV, CHA_WARNING (0x01/0x02), CHA_MAP_TILE, CHA_OTA   | |
|  +--------------------------------------+---------------------------------------+ |
|                                         |                                         |
|  +--------------------------------------v---------------------------------------+ |
|  | GUI Engine (TFT_eSPI + GC9A01 240x240):                                      | |
|  | - drawHUD(): Rẽ, khoảng cách, tên đường cuộn mượt, tốc độ                    | |
|  | - drawTrafficWarningOverlay(): Viền nhấp nháy đỏ 250ms, Biển tốc độ/Camera  | |
|  | - drawSTATUS(): Đồng hồ Cyber S4, Pin xe, Pin điện thoại, Thời tiết          | |
|  +------------------------------------------------------------------------------+ |
+-----------------------------------------------------------------------------------+
```

---

## 3. Clean Directory Structure

```text
D:/Documents/PlatformIO/Tdriver/
├── docs/                               <-- Tài liệu đặc tả kiến trúc & hướng dẫn
│   ├── architecture/                   <-- Chi tiết kỹ thuật, bảng phân bổ chân ESP32
│   ├── features/                       <-- Đặc tả giao diện & tính năng HUD
│   ├── servers/
│   │    └── nas152_server.md           <-- Hướng dẫn quản trị Docker Stack NAS
│   └── update ai/
│        └── prompt.md                  <-- Yêu cầu nâng cấp phân hệ
├── nas_services/                       <-- Dịch vụ Docker trên NAS
│   ├── docker-compose.yml              <-- Cấu hình 4 microservices
│   └── fusion_engine/                  <-- Node.js middleware thay thế Overpass
│        ├── Dockerfile
│        ├── package.json
│        ├── server.js
│        └── data/
│             ├── camera_vn.csv
│             └── osm_cameras.csv
├── gsm/                                <-- Git Sync Manager & Release Tool (Flask)
│   ├── app.py
│   └── requirements.txt
└── TYMAP/                              <-- Ứng dụng Android & Firmware ESP32
    ├── app/                            <-- Android Source Code (Kotlin)
    │   └── src/main/
    │        ├── AndroidManifest.xml
    │        ├── java/com/example/tymap/
    │        │    ├── MainActivity.kt
    │        │    ├── ble/
    │        │    │    ├── BleConstants.kt
    │        │    │    └── BleManager.kt
    │        │    ├── repository/
    │        │    │    ├── NavigationRepository.kt
    │        │    │    └── SavedPlaceDbHelper.kt
    │        │    ├── service/
    │        │    │    ├── GpsManager.kt
    │        │    │    ├── NavigationService.kt
    │        │    │    ├── RoutingEngine.kt
    │        │    │    └── TrafficWarningManager.kt
    │        │    ├── ui/
    │        │    │    ├── ConnectionFragment.kt
    │        │    │    ├── MapFragment.kt
    │        │    │    ├── SettingsFragment.kt
    │        │    │    └── SuggestionAdapter.kt
    │        │    └── utils/
    │        │         ├── PrefsHelper.kt
    │        │         └── UrlParser.kt
    │        └── res/
    │             ├── layout/
    │             └── values/
    │                  ├── colors.xml
    │                  └── strings.xml
    └── firmware/                       <-- PlatformIO Firmware
         └── esp32_s3_gc9a01/           <-- ESP32-S3 + Màn hình tròn GC9A01
              ├── platformio.ini
              └── src/
                   ├── gui.h
                   ├── gui.cpp          <-- Lõi vẽ đồ họa 240x240 & Warning Overlay
                   └── main.cpp         <-- NimBLE Server, GATT Handlers & Loop
```

---

## 4. Key Configuration, Protocols & GATT Registry

### 4.1. NAS Microservices Ports
- **GraphHopper**: `8989` (Host) $\to$ `8989` (Container)
- **Nominatim Geocoding**: `8081` (Host) $\to$ `8080` (Container)
- **Photon Autocomplete**: `2322` (Host) $\to$ `2322` (Container)
- **Fusion Engine**: `8088` (Host) $\to$ `8088` (Container)

### 4.2. Complete BLE GATT Characteristics (Service UUID: `0000feed-0000-1000-8000-00805f9b34fb`)

| Tên Characteristic | UUID | Kiểu dữ liệu / Payload | Mục đích |
| :--- | :--- | :--- | :--- |
| `CHA_NAV` | `0b11deef-1563-447f-aece-d3dfeb1c1f20` | Key-Value Text (`active=1\ndist=150m\ninst=Rẽ trái\n...`) | Dữ liệu dẫn đường TBT |
| `CHA_NAV_TBT_ICON` | `d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad` | String Hash `hash=0x1234` hoặc 1bpp binary | Icon mũi tên điều hướng |
| `CHA_GPS_SPEED` | `98b6073a-5cf3-4e73-b6d3-f8e05fa018a9` | String Tốc độ (`45`) | Cập nhật vận tốc GPS theo thời gian thực |
| `CHA_SETTINGS` | `9d37a346-63d3-4df6-8eee-f0242949f59f` | Key-Value Text (`bright=80\ntimeout=0\n...`) | Cấu hình độ sáng, timeout, theme |
| `CHA_TIME` | `a1b2c3d4-e5f6-4789-a012-3456789abcde` | 4 bytes Epoch Unix Time LE | Đồng bộ RTC thời gian |
| `CHA_WEATHER` | `b2c3d4e5-f6a7-4890-b123-456789abcdef` | Key-Value (`temp=31.5\nicon=01d`) | Nhiệt độ và thời tiết |
| `CHA_NOTIFICATION` | `c1d2e3f4-a5b6-4789-c012-3456789abcde` | JSON String (`{"app":"Zalo","title":"...","message":"..."}`) | Thông báo ứng dụng |
| `CHA_PHONE_BATTERY`| `e5f6a7b8-c9d0-4123-e456-789012cdef01` | 1-2 bytes (`[level, isCharging]`) | Hiển thị % pin điện thoại trên HUD |
| `CHA_WARNING` | `e4f5a6b7-c8d9-4012-e345-678901bcdef0` | 2 bytes Binary: `[0x01/0x02, SpeedLimit]` | **Cảnh báo giao thông (0x01: Cam, 0x02: Tốc độ)** |
| `CHA_MAP_TILE` | `d1e2f3a4-b5c6-4789-d012-3456789abcde` | Header 7 bytes + JPEG Chunk Stream | Truyền Tile bản đồ ngoại tuyến |
| `CHA_MAP_CTRL` | `e2f3a4b5-c6d7-4890-e123-456789abcdef` | 1 byte command (`0x01` Set Active, `0x03` Reset) | Điều khiển trung tâm tile bản đồ |
| `CHA_OTA` | `f0a1b2c3-d4e5-4f60-a012-bcdef0123456` | 4 bytes Total Size + 512B Chunks + `0x31` Finish | Nạp Firmware ESP32 qua BLE |

---

## 5. Core Logic & Source Code Extracts

### 5.1. Android: `RoutingEngine.kt` (Luồng bất đồng bộ 3 tầng)
- **Path:** [RoutingEngine.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/RoutingEngine.kt#L45-L65)
- **Role:** Định tuyến siêu tốc Fast-Track với GraphHopper NAS (15-30ms), phát tuyến đường lập tức lên UI, đồng thời gửi Polyline nạp ngầm danh sách cảnh báo từ Fusion Engine.

```kotlin
// 1. FAST-TRACK: Ưu tiên tuyệt đối GraphHopper NAS (15-30ms)
val fastGhRoutes = fetchGraphHopperRoute(context, startLat, startLng, destLat, destLng, ghKey, vehicleType)
if (!fastGhRoutes.isNullOrEmpty()) {
    val selectedRoutes = fastGhRoutes.mapIndexed { i, r -> r.copy(isSelected = (i == 0)) }
    // Ưu tiên 1: Hiển thị ngay lập tức lên bản đồ
    withContext(Dispatchers.Main) {
        onProgressiveUpdate?.invoke(selectedRoutes)
    }
    // Ưu tiên 2 (Chạy nền): Tải cảnh báo giao thông dọc tuyến từ Fusion Engine
    val primaryPolyline = selectedRoutes.firstOrNull()?.polyline
    if (!primaryPolyline.isNullOrEmpty()) {
        TrafficWarningManager.fetchRouteWarnings(context, primaryPolyline)
    }
    return@coroutineScope selectedRoutes
}
```

### 5.2. Android: `TrafficWarningManager.kt` (Quét Haversine Offline)
- **Path:** [TrafficWarningManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/TrafficWarningManager.kt#L200-L248)
- **Role:** So khớp vị trí GPS thực tế với mảng `warningCache` trong RAM điện thoại mỗi giây, kích hoạt cảnh báo khi khoảng cách $\le 200\text{m}$.

```kotlin
for (point in warningCache) {
    Location.distanceBetween(location.latitude, location.longitude, point.lat, point.lon, results)
    val distanceMeters = results[0]
    if (distanceMeters <= WARNING_DISTANCE_METERS) {
        val lastTriggered = lastTriggeredTimeMap[point.id] ?: 0L
        if (now - lastTriggered > WARNING_COOLDOWN_MS) {
            lastTriggeredTimeMap[point.id] = now
            // Gửi gói tin BLE Hex nhị phân 2-byte tới ESP32
            if (bleManager != null && bleManager.isConnected) {
                bleManager.sendTrafficWarning(point.type, point.speedLimit.toByte())
            }
            break
        }
    }
}
```

### 5.3. ESP32: `main.cpp` (Xử lý ngắt gói tin BLE `CHA_WARNING_UUID`)
- **Path:** [main.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp#L1322-L1345)
- **Role:** Callback NimBLE nhận chuỗi Hex 2 byte, kích hoạt cờ `isTrafficWarningActive` và `screenNeedsRedraw = true` tức thì.

```cpp
else if (uuid == CHA_WARNING_UUID)
{
    if (val.length() >= 2)
    {
        trafficWarningType = (uint8_t)val[0];
        trafficWarningValue = (uint8_t)val[1];
        isTrafficWarningActive = true;
        trafficWarningStartTime = millis();
        screenNeedsRedraw = true;
        Serial.printf("BLE: Received Traffic Warning -> Type=0x%02X, Value=%d\n", trafficWarningType, trafficWarningValue);
    }
}
```

### 5.4. ESP32: `gui.cpp` (Render Biển báo & Viền nhấp nháy đỏ)
- **Path:** [gui.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/gui.cpp#L522-L580)
- **Role:** Vẽ viền nhấp nháy đỏ chớp tắt 250ms quanh viền tròn 120px và huy hiệu biển báo tốc độ tròn / camera phạt nguội.

```cpp
void drawTrafficWarningOverlay()
{
    if (!isTrafficWarningActive) return;
    int cx = 120, cy = 120, r = 48;
    bool isBlink = ((millis() - trafficWarningStartTime) / 250) % 2 == 0;
    if (isBlink) {
        canvasSprite.drawCircle(cx, cy, 119, TFT_RED);
        canvasSprite.drawCircle(cx, cy, 118, TFT_RED);
        canvasSprite.drawCircle(cx, cy, 117, TFT_RED);
    }
    // Biển báo giới hạn tốc độ chuẩn VN
    canvasSprite.fillCircle(cx, cy, r + 5, TFT_RED);
    canvasSprite.fillCircle(cx, cy, r + 1, TFT_WHITE);
    canvasSprite.fillCircle(cx, cy, r - 4, TFT_WHITE);
    if (trafficWarningType == 0x02) {
        myFont.set_font(FONT_CLOCK);
        String valStr = String(trafficWarningValue);
        myFont.print(cx - myFont.getLength(valStr) / 2, cy - 16, valStr, TFT_BLACK, TFT_WHITE);
    }
}
```

### 5.5. Backend: `server.js` (Fusion Engine Node.js)
- **Path:** [server.js](file:///d:/Documents/PlatformIO/Tdriver/nas_services/fusion_engine/server.js)
- **Role:** Lọc dữ liệu camera/biển báo dọc theo chuỗi Polyline lộ trình với bán kính quét $50\text{m}$, phản hồi JSON dưới 10ms.

---

## 6. Current State & Known Verification Checklist

| Phân hệ | Thành phần | Trạng thái hoạt động | Ghi chú |
| :--- | :--- | :--- | :--- |
| **Backend NAS** | GraphHopper (8989) | **HOẠT ĐỘNG (Healthy)** | Tuyến xe máy, cache PBF Việt Nam |
| **Backend NAS** | Nominatim (8081) | **HOẠT ĐỘNG (Healthy)** | Geocoding nội bộ |
| **Backend NAS** | Photon (2322) | **HOẠT ĐỘNG (Healthy)** | Image `rtuszik/photon-docker` |
| **Backend NAS** | Fusion Engine (8088)| **HOẠT ĐỘNG (Healthy)** | 47MB RAM, 8 điểm camera |
| **Android App** | Merged Search | **HOẠT ĐỘNG** | Ưu tiên SQLite, Photon bên dưới |
| **Android App** | Async Routing | **HOẠT ĐỘNG** | Fast-Track 15-30ms |
| **Android App** | Offline Haversine | **HOẠT ĐỘNG** | Quét RAM cache $\le 200\text{m}$ |
| **Firmware ESP32** | NimBLE Warning | **HOẠT ĐỘNG** | `CHA_WARNING_UUID` 2-byte |
| **Firmware ESP32** | GUI Overlay | **HOẠT ĐỘNG** | Viền nháy đỏ + Biển tròn 240x240 |

---

## 7. Feature Traceability & Command Mapping

### Trace 1: Luồng Tìm Kiếm Địa Điểm Hợp Nhất (Merged Search Flow)
1. **User Input:** Nhập ký tự vào `etSearch` trong [fragment_map.xml](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/res/layout/fragment_map.xml).
2. **SQLite Query:** [MapFragment.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ui/MapFragment.kt#L743-L760) gọi `savedPlaceDbHelper.searchPlaces(query)` để lấy địa điểm cá nhân đã lưu.
3. **Photon Network Call:** [MapFragment.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ui/MapFragment.kt#L775-L828) gọi `http://192.168.1.114:2322/api?q=...` với fallback `https://photon.komoot.io/`.
4. **Merge & Sort:** [MapFragment.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ui/MapFragment.kt#L830-L844) gộp danh sách (SQLite trên đầu, Photon theo khoảng cách).
5. **Adapter Render:** [SuggestionAdapter.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ui/SuggestionAdapter.kt#L45-L80) hiển thị icon tương ứng (Home, Work, Favorite, Pin Action).

### Trace 2: Luồng Cảnh Báo Giao Thông Tốc Độ / Camera (Traffic Alert Flow)
1. **Route Calculation:** [RoutingEngine.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/RoutingEngine.kt#L55-L62) gọi `TrafficWarningManager.fetchRouteWarnings(context, polyline)`.
2. **Backend Filtering:** [server.js](file:///d:/Documents/PlatformIO/Tdriver/nas_services/fusion_engine/server.js) lọc các điểm camera cách lộ trình $\le 50\text{m}$ và trả về mảng JSON.
3. **RAM Cache Ingestion:** [TrafficWarningManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/TrafficWarningManager.kt#L100-L135) lưu mảng vào `CopyOnWriteArrayList<TrafficWarningPoint>`.
4. **GPS Real-Time Check:** [GpsManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/GpsManager.kt#L30) nhận tọa độ GPS $\to$ gọi `TrafficWarningManager.checkGpsLocation`.
5. **BLE Hex Dispatch:** Khi khoảng cách $\le 200\text{m}$, [BleManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ble/BleManager.kt#L450-L460) gửi gói tin 2-byte `[type, speedLimit]` tới `CHA_WARNING_UUID`.
6. **ESP32 Instant Display:** [main.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp#L1322) nhận ngắt $\to$ [gui.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/gui.cpp#L522) vẽ viền nhấp nháy đỏ và biển báo tròn trong 3 giây.
