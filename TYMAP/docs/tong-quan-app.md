# Tổng Quan Ứng Dụng TYMAP

**TYMAP** là ứng dụng Android dành cho màn hình HUD (Head-Up Display) trên ô tô, kết nối với thiết bị ESP32 qua BLE để hiển thị thông tin dẫn đường, bản đồ và trạng thái xe lên màn hình HUD chuyên dụng.

---

## 1. Cấu Trúc Thư Mục & Package

```
app/src/main/java/com/example/tymap/
├── MainActivity.kt              # Activity chính, chứa ViewPager2 + BottomNavigation
├── ShareReceiverActivity.kt     # Nhận intent chia sẻ từ Google Maps
│
├── ble/
│   ├── BleConstants.kt          # Định nghĩa tất cả UUID của service/characteristic BLE
│   └── BleManager.kt           # MyBleManager — quản lý kết nối BLE với ESP32
│
├── repository/
│   └── NavigationRepository.kt  # Singleton lưu trữ toàn bộ state dùng chung (StateFlow/SharedFlow)
│
├── service/
│   ├── NavigationService.kt     # Foreground Service chính — điều phối GPS, BLE, TTS, bản đồ
│   ├── GmapsNotificationService.kt  # NotificationListenerService — đọc thông báo Google Maps
│   ├── GpsManager.kt            # Quản lý cập nhật vị trí GPS
│   ├── RoutingEngine.kt         # Client gọi API định tuyến (5 engine), parse kết quả
│   └── TtsManager.kt            # Quản lý Text-To-Speech tiếng Việt
│
├── ui/
│   ├── MainPagerAdapter.kt      # Adapter cho ViewPager2 3 trang
│   ├── ConnectionFragment.kt    # Tab 1: Kết nối BLE + API + Điều khiển thiết bị
│   ├── MapFragment.kt           # Tab 2: Bản đồ OSM + Tìm kiếm + Định tuyến + Dẫn đường
│   ├── SettingsFragment.kt      # Tab 3: Cài đặt ứng dụng
│   ├── BluetoothDeviceAdapter.kt    # RecyclerView adapter cho danh sách thiết bị BLE
│   ├── ApiServiceAdapter.kt         # RecyclerView adapter cho danh sách API dịch vụ
│   ├── RouteAlternativeAdapter.kt   # RecyclerView adapter cho danh sách tuyến đường thay thế
│   ├── SuggestionAdapter.kt         # Adapter cho gợi ý tìm kiếm địa điểm
│   └── theme/
│       ├── Color.kt             # Định nghĩa màu sắc Material3
│       ├── Theme.kt             # Theme Compose (sáng/tối)
│       └── Type.kt              # Typography Scale
│
└── utils/
    ├── IconUtils.kt             # Chuyển Bitmap → ảnh 1bpp cho màn hình e-ink
    ├── PolylineDecoder.kt       # Giải mã polyline encoded (định dạng Google)
    └── PrefsHelper.kt           # SharedPreferences + EncryptedSharedPreferences
```

---

## 2. Màn Hình / Fragment / Activity

### MainActivity (Activity chính)

- Single Activity architecture
- Hosts một **ViewPager2** với 3 trang (truy cập qua BottomNavigationView)
- Theme Material3 DayNight, không ActionBar
- Chứa `NavigationService` chạy nền ngay khi khởi động

### ConnectionFragment (Tab 1 — Kết nối)

- **Quét BLE**: Quét thiết bị BLE trong 10 giây, hiển thị danh sách BluetoothDeviceAdapter
- **Lịch sử ghép nối**: Spinner chọn thiết bị đã ghép nối trước đó
- **Điều khiển ESP32**:
  - Chuyển chế độ: HUD / MAP / STATUS
  - Điều chỉnh độ sáng (brightness slider)
  - Nút Ping (kiểm tra kết nối)
  - Nút Restart (khởi động lại ESP32)
  - Zoom từ xa (Remote Zoom In/Out)
- **API Services**: Danh sách các dịch vụ định tuyến (dạng thẻ mở rộng được), nhập API key, nút kiểm tra
- **HUD Preview**: Xem trước dữ liệu đang gửi đến ESP32
- **Notification Permission**: Kiểm tra/cấp quyền đọc thông báo cho NotificationListenerService

### MapFragment (Tab 2 — Bản đồ)

- **Bản đồ**: sử dụng **osmdroid** MapView
- **Nguồn tile (6 loại)**:
  1. CartoDB Positron (sáng)
  2. OSM Mapnik
  3. CartoDB Dark Matter (tối)
  4. CartoDB Voyager
  5. ESRI Satellite (vệ tinh)
  6. Tùy chỉnh (Custom URL, dùng cho MapCN)
- **Định vị**: Marker vị trí người dùng (chấm xanh + hình quạt chỉ hướng)
- **Compass**: La bàn theo hướng thiết bị
- **Tìm kiếm địa điểm**: Photon (chính) → Nominatim (dự phòng), hiển thị danh sách gợi ý (SuggestionAdapter)
- **Tuyến đường**: Vẽ polyline, chọn tuyến thay thế qua RouteAlternativeAdapter
- **Bottom Sheet** (3 trạng thái):
  - **Place Info**: Thông tin điểm đến
  - **Route Preview**: Chi tiết tuyến đường (khoảng cách, thời gian, các bước)
  - **Active Navigation**: Dẫn đường chủ động
- **Chế độ dẫn đường**: Track-up (bản đồ xoay theo hướng đi), auto-zoom, cảnh báo tốc độ
- **Điều khiển từ xa**: Lắng nghe lệnh zoom từ ESP32 qua SharedFlow

### SettingsFragment (Tab 3 — Cài đặt)

Xem chi tiết tại mục 7.

### ShareReceiverActivity

- Nhận `Intent.ACTION_SEND` với `mimeType: text/plain` (chia sẻ link từ Google Maps)
- Phân tích URL thông minh:
  - Link dạng `/dir/...`: trích xuất tọa độ điểm đến (cặp tọa độ thứ 2)
  - Link có `@lat,lng`: trích xuất tọa độ
  - Link có `q=` hoặc `query=`: trích xuất tọa độ
- Chuyển tiếp tọa độ sang MainActivity để hiển thị trên bản đồ

---

## 3. Dịch Vụ Nền (Service)

### NavigationService (Foreground Service)

- Loại foreground: `location|connectedDevice`
- **Vòng đời**: Chạy ngay khi app mở, dừng khi app tắt
- **Các thành phần điều phối**:
  - **GpsManager**: Lấy vị trí GPS mỗi 1 giây (GPS provider), 2 giây (Network provider)
  - **MyBleManager**: Giao tiếp BLE với ESP32
  - **TtsManager**: Đọc chỉ dẫn bằng giọng nói tiếng Việt
  - **RoutingEngine**: Tính toán tuyến đường
- **Headless MapView**: Tạo MapView 240×240px ẩn để render ảnh bản đồ gửi qua BLE
- **BroadcastReceiver**: Nhận dữ liệu HUD từ GMapsNotificationListener

### GmapsNotificationService (NotificationListenerService)

- Lắng nghe thông báo từ package `com.google.android.apps.maps`
- Trích xuất title (chỉ dẫn) và text (chi tiết) từ thông báo Google Maps
- Gửi broadcast `ACTION_GMAPS_HUD` để NavigationService xử lý
- Cho phép app hoạt động như "Google Maps HUD" — hứng chỉ dẫn Google Maps và gửi qua BLE

### GpsManager

- Request location updates với Fused Location Provider hoặc LocationManager
- Tần suất: 1000ms (GPS), 2000ms (Network)
- Cập nhật vào `NavigationRepository.updateLocation()`

### TtsManager

- Sử dụng Android TextToSpeech engine
- Hỗ trợ tiếng Việt (`vi-VN`)
- Các kiểu giọng đọc: chỉ dẫn đường, cảnh báo tốc độ, cảnh báo lệch tuyến

---

## 4. BLE GATT Profile

**Service UUID**: `0000feed-0000-1000-8000-00805f9b34fb`

| Characteristic | UUID | Hướng | Định dạng | Mô tả |
|---|---|---|---|---|
| **CHA_NAV** | `0b11deef-1563-447f-aece-d3dfeb1c1f20` | Phone → ESP32 | Chuỗi text `dist=...\ninst=...\nroad=...\neta=...\nete=...` | Dữ liệu dẫn đường (khoảng cách, chỉ dẫn, tên đường, ETA, thời gian đến) |
| **CHA_NAV_TBT_ICON** | `d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad` | Phone → ESP32 | Byte (iconIndex) hoặc ByteArray (288 bytes, 48×48 1bpp) | Biểu tượng chỉ dẫn lượt rẽ (turn-by-turn) |
| **CHA_GPS_SPEED** | `98b6073a-5cf3-4e73-b6d3-f8e05fa018a9` | Phone → ESP32 | Chuỗi số (km/h) | Tốc độ hiện tại |
| **CHA_SETTINGS** | `9d37a346-63d3-4df6-8eee-f0242949f59f` | Phone → ESP32 | `brightness=50\n...` | Cài đặt thiết bị (độ sáng, chế độ) |
| **CHA_TIME** | `a1b2c3d4-e5f6-4789-a012-3456789abcde` | Phone → ESP32 | 4 bytes (Little Endian, Unix timestamp giây) | Đồng bộ thời gian |
| **CHA_WEATHER** | `b2c3d4e5-f6a7-4890-b123-456789abcdef` | Phone → ESP32 | JSON: `{"t": 32.5, "i": "01d"}` | Dữ liệu thời tiết (nhiệt độ, icon) |
| **CHA_MAP_IMAGE** | `c3d4e5f6-a7b8-4901-c234-567890abcdef` | Phone → ESP32 | 4 bytes (kích thước) + chunks JPEG (MTU-3 mỗi chunk) | Ảnh bản đồ dạng JPEG, gửi theo chunk |
| **CHA_DEVICE_CTRL** | `d4e5f6a7-b8c9-4012-d345-678901bcdef0` | **Hai chiều** | Bitfield (1 byte): bit0=zoomIn, bit1=zoomOut, bit2=mapMode, bit3=refresh | Điều khiển thiết bị / nhận lệnh từ ESP32 |
| **CHA_REMOTE_CMD** | `f1a2b3c4-d5e6-4789-a012-3456789abcde` | Phone → ESP32 | 1 byte: 0x10(HUD), 0x11(MAP), 0x12(STATUS), 0x20(Refresh), 0x30(Ping), 0xFF(Restart) | Lệnh điều khiển từ xa |
| **CHA_DEVICE_STATUS** | `a1b2c3d4-e5f6-4789-b012-3456789abcde` | ESP32 → Phone | Text: `mode=HUD\nvoltage=3.7\nrssi=-65` | Trạng thái thiết bị (chế độ, điện áp, RSSI, kết quả ping) |

**Đặc điểm kết nối**:
- MTU: yêu cầu 512 bytes
- Gửi ảnh map theo chunk (kích thước = MTU - 3)
- Notification được bật trên `CHA_DEVICE_CTRL` và `CHA_DEVICE_STATUS` (ESP32 chủ động gửi)
- Các characteristic còn lại dùng write (có response hoặc không)

---

## 5. Tính Năng Bản Đồ

### Nguồn dữ liệu bản đồ (Tile Sources)
1. **CartoDB Positron** — bản đồ sáng, phong cách hiện đại
2. **OSM Mapnik** — bản đồ OpenStreetMap chuẩn
3. **CartoDB Dark Matter** — bản đồ tối
4. **CartoDB Voyager** — bản đồ màu sắc
5. **ESRI Satellite** — ảnh vệ tinh
6. **Custom URL** — nhập URL tile tùy chỉnh (dùng cho MapCN)

### Tìm kiếm địa điểm
- **Photon (geocoding-photon.komoot.io)** — ưu tiên chính
- **Nominatim (OpenStreetMap)** — dự phòng khi Photon lỗi
- Hiển thị danh sách gợi ý với SuggestionAdapter

### Định tuyến (5 Engine)
| Engine | API Key | Alternatives | Ghi chú |
|---|---|---|---|
| **OSRM** | Không cần | Có | `router.project-osrm.org`, miễn phí |
| **OpenRouteService** | Cần | — | `api.openrouteservice.org`, POST request |
| **GraphHopper** | Cần | Có | `graphhopper.com`, GET request |
| **Valhalla** | Không cần | — | `valhalla1.openstreetmap.de`, POST request |
| **Mapbox** | Cần | — | `api.mapbox.com`, Directions API |

- Tất cả đều parse ra `RouteInfo` (polyline + khoảng cách + thời gian + các bước)
- Cơ chế fallback: thử từng engine theo thứ tự ưu tiên do người dùng cấu hình
- Hỗ trợ nhiều tuyến thay thế (alternative routes)

### Hiển thị tuyến đường
- Vẽ polyline nhiều màu trên MapView
- Cho phép chọn tuyến qua RouteAlternativeAdapter (Bottom Sheet)
- Marker đánh dấu điểm xuất phát và điểm đến

### Dẫn đường (Navigation)
- **Track-up mode**: Bản đồ xoay theo hướng di chuyển
- **Auto-zoom**: Tự động zoom khi đến gần điểm rẽ
- **Cảnh báo tốc độ**: Hiển thị overlay khi vượt ngưỡng
- **Phát hiện lệch tuyến**: Tính khoảng cách đến polyline, tự động tính lại tuyến mới (cooldown 10 giây)
- **Turn-by-turn**: Chỉ dẫn từng bước, cập nhật khoảng cách và ETA theo thời gian thực

### Chế độ ẩn (Headless Map)
- NavigationService tạo MapView 240×240px chạy ngầm
- Render ảnh bản đồ và gửi JPEG qua BLE (CHA_MAP_IMAGE)
- Dùng khi thiết bị ESP32 ở chế độ MAP

---

## 6. Xử Lý Chia Sẻ Từ Google Maps

**Luồng xử lý**:

1. Người dùng chọn "Chia sẻ" → "TYMAP" từ Google Maps
2. `ShareReceiverActivity` nhận `ACTION_SEND` với URL Google Maps
3. URL được rút gọn (goo.gl/maps/...) → giải nén (follow redirect) để lấy URL đầy đủ
4. Phân tích thông minh URL đầy đủ:
   - `/dir/lat,lng/lat,lng` → lấy cặp tọa độ thứ 2 (điểm đến)
   - `@lat,lng` → lấy tọa độ
   - `?q=lat,lng` hoặc `?query=lat,lng` → lấy tọa độ
5. Chuyển tọa độ sang `MainActivity` và hiển thị trên MapFragment

**Cơ chế thay thế — Notification Listener**:
- `GMapsNotificationListener` đọc thông báo từ Google Maps khi đang dẫn đường
- Trích xuất chỉ dẫn, khoảng cách, tên đường
- Gửi broadcast để NavigationService cập nhật HUD và gửi qua BLE

---

## 7. Cài Đặt (Settings)

### Hiển thị (Display)
| Mục | Giá trị | Mô tả |
|---|---|---|
| Giao diện (Theme) | Hệ thống / Sáng / Tối | Theme Material3 |
| Định dạng giờ | 12h / 24h | — |
| Đơn vị đo | Hệ mét (km/m) / Hệ Anh (mi/ft) | — |

### Bản đồ (Map)
| Mục | Giá trị | Mô tả |
|---|---|---|
| Nguồn tile | Positron / Mapnik / Dark Matter / Voyager / Vệ tinh / Tùy chỉnh | — |
| Tile URL tùy chỉnh | Text | Dùng cho MapCN |
| Zoom mặc định | 3–20 (float) | — |
| Auto-zoom | Bật / Tắt | Tự động zoom khi dẫn đường |
| Hướng bản đồ | Bắc / Hướng đi | North-up / Track-up |

### Định tuyến (Routing)
| Mục | Giá trị | Mô tả |
|---|---|---|
| Engine ưu tiên | OSRM / ORS / GraphHopper / Valhalla / Mapbox | Engine chính |
| Thứ tự fallback | Dialog kéo-thả | Thứ tự các engine dự phòng |
| Khoảng cách lệch tuyến | 5–100 (m) | Ngưỡng phát hiện off-route |
| Cảnh báo tốc độ | Bật / Tắt + ngưỡng (km/h) | — |

### Giọng nói (Voice)
| Mục | Mô tả |
|---|---|
| Hướng dẫn bằng giọng nói | Bật/Tắt |
| Âm lượng | Điều chỉnh |
| Kiểu giọng | Lựa chọn |
| Cảnh báo lệch tuyến | Bật/Tắt |
| Cảnh báo tốc độ | Bật/Tắt |
| Ngôn ngữ | Tiếng Việt (mặc định) |

### Gửi dữ liệu (Data Sending)
| Mục | Giá trị | Mô tả |
|---|---|---|
| FPS | 1–30 (fps) | Số khung hình/giây khi gửi ảnh map |
| Ngưỡng di chuyển | 5–100 (m) | Chỉ gửi lại ảnh map khi di chuyển đủ xa |
| Chất lượng JPEG | 0–100 | Chất lượng ảnh map gửi qua BLE |
| Idle timeout | 30–300 (giây) | Tạm dừng gửi ảnh khi không di chuyển |

---

## 8. Thư Viện Bên Ngoài

| Thư viện | Phiên bản | Mục đích |
|---|---|---|
| **osmdroid** (org.osmdroid:osmdroid-android) | 6.1.20 | Bản đồ OpenStreetMap (thay thế Google Maps) |
| **Nordic BLE** (no.nordicsemi.android:ble-ktx) | 2.11.0 | Giao tiếp BLE với ESP32 (BleManager) |
| **OkHttp** (com.squareup.okhttp3:okhttp) | 5.0.0-alpha.14 | HTTP client cho API định tuyến & geocoding |
| **Gson** (com.google.code.gson:gson) | 2.14.0 | Parse JSON |
| **AndroidX Security Crypto** | 1.1.0 | Mã hóa API keys (EncryptedSharedPreferences, AES256-GCM) |
| **AndroidX ViewPager2** | 1.1.0 | Chuyển tab (kết nối / bản đồ / cài đặt) |
| **AndroidX Fragment KTX** | 1.8.9 | Fragment extensions |
| **Material3 Compose** | BOM 2025.12.00 | Giao diện Compose (theme, colors) |
| **Kotlin Compose Plugin** | 2.2.10 | Compose compiler |
| **AGP** | 9.2.1 | Android Gradle Plugin |
| **SDK** | minSdk 30, targetSdk 36, compileSdk 36 | Android 11+ |

---

## Tổng Kết Kiến Trúc

```
┌─────────────────────────────────────────────────────────────────┐
│                        Người dùng                               │
├─────────────────────────────────────────────────────────────────┤
│  MainActivity                                                    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  ViewPager2                                              │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐                │    │
│  │  │Connection│ │   Map    │ │ Settings │                │    │
│  │  │ Fragment │ │ Fragment │ │ Fragment │                │    │
│  │  └──────────┘ └──────────┘ └──────────┘                │    │
│  └─────────────────────────────────────────────────────────┘    │
│                          │                                       │
│                          ▼                                       │
│  NavigationRepository (StateFlow - Singleton)                    │
└─────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│  NavigationService (Foreground Service)                          │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────────┐ ┌──────────┐ │
│  │  GPS   │ │  BLE   │ │  TTS   │ │  Routing   │ │ Headless │ │
│  │Manager │ │Manager │ │Manager │ │  Engine    │ │ MapView  │ │
│  └────────┘ └────────┘ └────────┘ └────────────┘ └──────────┘ │
└─────────────────────────────────────────────────────────────────┘
                          │
                          ▼
              ┌─────────────────────┐
              │  ESP32 HUD Device   │
              │  (BLE GATT Server)  │
              └─────────────────────┘
```

**Luồng dữ liệu chính**:
1. GPS → NavigationService → NavigationRepository → MapFragment (hiển thị)
2. NavigationService → BLE → ESP32 (dữ liệu HUD, speed, map image)
3. Google Maps notification → GMapsNotificationListener → NavigationService → BLE
4. ShareReceiverActivity → MainActivity → MapFragment (hiển thị điểm đến)
5. ESP32 → BLE → NavigationRepository → ConnectionFragment (trạng thái thiết bị)

---

*Tài liệu được tạo từ mã nguồn ngày 2026-06-22.*
