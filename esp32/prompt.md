# Master Prompt Hoàn Chỉnh: Hệ thống Dẫn đường Xe máy Thông minh

Bạn là chuyên gia hệ thống nhúng và lập trình di động. Hãy thiết kế và triển khai toàn bộ firmware cho ESP32‑S3 và ứng dụng Android cho thiết bị dẫn đường xe máy. Hệ thống giao tiếp qua BLE. Mục tiêu: màn hình phụ trên xe máy (ESP32 + GC9A01) hiển thị dẫn đường từ điện thoại, không cần nhìn điện thoại.

## 1. Tổng quan kiến trúc & luồng dữ liệu

```
[Google Maps (thông báo)] ──> [NotificationListener] ──> (broadcast)
[GPS điện thoại] ──> [NavigationService] ──> (BLE) ──> [ESP32] ──> [GC9A01]
[OSRM/ORS API] <── [NavigationService]
[Tile Server] <── [NavigationService] ──> (render ảnh JPEG) ──> (BLE) ──> [ESP32]
[Open-Meteo] <── [NavigationService] ──> (BLE weather) ──> [ESP32]
[Nút bấm ESP32] ──> (BLE CHA_DEVICE_CTRL) ──> [NavigationService]
```

- **Điện thoại** chịu trách nhiệm nặng: định tuyến, tải tile bản đồ, render ảnh bản đồ 240×240, lấy thời tiết, giữ đồng hồ.
- **ESP32** chỉ nhận dữ liệu và hiển thị, gửi lệnh chuyển chế độ hoặc zoom khi có nút bấm.

## 2. Phần cứng ESP32‑S3

- Board: ESP32‑S3 DevKitC‑1, Flash 16 MB, PSRAM 16 MB.
- Màn: GC9A01 1.28" tròn 240×240 px, SPI: CS=10, DC=8, RST=9, MOSI=11, SCLK=12, BL=14.
- Nút bấm:
  - Nút MODE: GPIO0, kéo lên.
  - Nút ZOOM: GPIO1, kéo lên.
- Đo điện áp ắc quy: cầu phân áp 1/6 → ADC1_CH2.

## 3. Giao thức BLE (định nghĩa đầy đủ)

### 3.1. Các characteristic

| Characteristic   | UUID                                 | Hướng     | Định dạng                                  | Mô tả                                                                            |
| ---------------- | ------------------------------------ | --------- | ------------------------------------------ | -------------------------------------------------------------------------------- |
| CHA_NAV          | 0b11deef-1563-447f-aece-d3dfeb1c1f20 | App → ESP | Text, các cặp key=value cách nhau bởi `\n` | Dữ liệu dẫn đường: `nextRd`, `distToNext`, `eta`, `ete`, `totalDist`, `iconHash` |
| CHA_NAV_TBT_ICON | d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad | App → ESP | 1 byte                                     | Mã icon rẽ (0–20), ESP tra bảng icon nội bộ                                      |
| CHA_GPS_SPEED    | 98b6073a-5cf3-4e73-b6d3-f8e05fa018a9 | App → ESP | Text                                       | Số nguyên km/h, ví dụ `60`                                                       |
| CHA_SETTINGS     | 9d37a346-63d3-4df6-8eee-f0242949f59f | App → ESP | Text                                       | Key=value: `brightness`, `lightTheme`, `speedLimit`                              |
| CHA_TIME         | a1b2c3d4-e5f6-4789-a012-3456789abcde | App → ESP | 4 bytes (uint32 LE)                        | Unix timestamp                                                                   |
| CHA_WEATHER      | b2c3d4e5-f6a7-4890-b123-456789abcdef | App → ESP | JSON                                       | `{"t":30,"i":"01d"}` (nhiệt độ, mã icon)                                         |
| CHA_MAP_IMAGE    | c3d4e5f6-a7b8-4901-c234-567890abcdef | App → ESP | 4 byte header (uint32 LE size) + JPEG data | Ảnh bản đồ 240×240, nén JPEG                                                     |
| CHA_DEVICE_CTRL  | d4e5f6a7-b8c9-4012-d345-678901bcdef0 | ESP → App | 1 byte bitfield                            | bit0=zoom in, bit1=zoom out, bit2=Map mode on/off, bit3=refresh                  |

### 3.2. Cách gửi/nhận

- **App gửi dữ liệu nhỏ** (CHA_NAV, CHA_GPS_SPEED…): ghi trực tiếp vào characteristic dưới dạng byte array (chuỗi text).
- **App gửi ảnh JPEG** (CHA_MAP_IMAGE):
  1. Gửi 4 byte kích thước (Little‑Endian).
  2. Chia JPEG thành các gói ≤ (MTU‑3) byte, gửi liên tiếp bằng Write Without Response.
- **ESP32 nhận ảnh**:
  1. Nhận 4 byte đầu → biết tổng kích thước.
  2. Cấp phát bộ đệm PSRAM (max 32 KB).
  3. Tích lũy dữ liệu, đếm số byte đã nhận.
  4. Khi đủ → gọi hàm giải mã JPEG.
- **ESP32 gửi lệnh điều khiển** (CHA_DEVICE_CTRL): gửi 1 byte khi có sự kiện nút bấm. App nhận bằng notification.

## 4. Firmware ESP32 (Arduino + PlatformIO)

### 4.1. Cấu trúc file

```
src/
  main.cpp
  ble_manager.h / ble_manager.cpp
  jpeg_handler.h / jpeg_handler.cpp
  mode_manager.h / mode_manager.cpp
  ui_renderer.h / ui_renderer.cpp
  icons.h
  MyFont.h
```

### 4.2. Thư viện

- TFT_eSPI (GC9A01, SPI, 240×240)
- NimBLE-Arduino
- JPEGDEC
- ESP32Time
- OneButton

### 4.3. Luồng chính (main.cpp)

```cpp
TFT_eSPI tft;
ESP32Time rtc;
NimBLEServer* pServer;
Mode currentMode = MODE_HUD;
bool menuActive = false;
int menuSelection = 0;
uint8_t* jpegBuffer = nullptr;
uint32_t jpegSize = 0;
uint32_t jpegReceived = 0;

void setup() {
  // Khởi tạo TFT, BLE, nút bấm, rtc...
  // Cấp phát jpegBuffer = (uint8_t*)ps_malloc(32*1024);
}

void loop() {
  // Xử lý nút bấm (OneButton.tick())
  // Cập nhật thời gian mỗi giây (rtc.getEpoch())
  // Nếu có dữ liệu BLE mới (cờ từ callback), xử lý và vẽ lại
  // Vẽ UI theo chế độ hiện tại
}
```

### 4.4. BLE Manager (ble_manager.cpp)

**Nhận dữ liệu từ App:**

- **CHA_NAV**: callback `onWrite` → lưu vào `navString` toàn cục.
- **CHA_NAV_TBT_ICON**: lưu vào biến `iconIndex`.
- **CHA_GPS_SPEED**: lưu `speedStr`.
- **CHA_SETTINGS**: parse key=value, áp dụng (độ sáng…).
- **CHA_TIME**: 4 byte → `uint32_t ts` → `rtc.setTime(ts)`.
- **CHA_WEATHER**: lưu `weatherJson`.
- **CHA_MAP_IMAGE**:
  - Nếu chưa có header: đọc 4 byte đầu → `jpegSize`, reset `jpegReceived = 0`.
  - Chép dữ liệu vào `jpegBuffer + jpegReceived`, tăng `jpegReceived`.
  - Nếu `jpegReceived == jpegSize` → gọi `processJPEG()`.

**Gửi dữ liệu lên App:**

- **CHA_DEVICE_CTRL**: khi nút zoom hoặc chuyển chế độ, gửi 1 byte với các bit tương ứng.

### 4.5. Xử lý JPEG (jpeg_handler.cpp)

- Dùng JPEGDEC, callback `pdraw`:
  ```cpp
  void pdraw(JPEGDRAW* draw) {
    tft.pushImage(draw->x, draw->y, draw->iWidth, draw->iHeight, draw->pPixels);
  }
  ```
- `processJPEG()`: `jpegdec.openRAM(jpegBuffer, jpegSize, pdraw)` → `jpegdec.decode(0,0,0)`.

### 4.6. Menu chọn chế độ (mode_manager.cpp)

- Nút MODE (OneButton):
  - **Click ngắn**: nếu menu chưa mở → `menuActive = true`, `menuSelection = currentMode`. Vẽ overlay (sprite 240×240, nền mờ, 3 icon+tên). Nếu menu đang mở → `menuSelection = (menuSelection+1)%3`, vẽ lại con trỏ.
  - **Long click (1s)**: nếu menu đang mở → `currentMode = menuSelection`, `menuActive = false`, gửi `CHA_DEVICE_CTRL` với bit2=1 nếu MAP, =0 nếu khác. Nếu menu chưa mở → `currentMode = MODE_HUD`.
  - **Timeout 5s**: tắt menu, giữ nguyên chế độ.
- Nút ZOOM (chỉ khi `currentMode == MODE_MAP`):
  - Click ngắn → gửi `0x01` (zoom in).
  - Long click → gửi `0x02` (zoom out).

### 4.7. Hiển thị giao diện (ui_renderer.cpp)

- **Chế độ HUD**:
  1. Parse `navString` để lấy `nextRd`, `distToNext`, `eta`...
  2. Vẽ icon rẽ (`iconIndex`) từ `icons.h` tại vị trí trung tâm trên.
  3. Dùng font `MyFont` vẽ tên đường, khoảng cách, tốc độ, ETA.
- **Chế độ STATUS**:
  1. Lấy thời gian từ `rtc.getTime()` → định dạng HH:MM, vẽ lớn.
  2. Parse `weatherJson` → chọn icon thời tiết, vẽ kèm nhiệt độ.
  3. Đọc ADC điện áp → tính Volt, vẽ.
- **Chế độ MAP**:
  1. Khi có ảnh mới (`newJpegReady`), gọi `processJPEG()`.
  2. Ảnh được vẽ trực tiếp bởi callback pdraw, không cần xử lý thêm.

## 5. Ứng dụng Android (Kotlin)

### 5.1. Cấu trúc

```
MainActivity (ViewPager2 + BottomNavigationView)
├── ConnectionFragment (Tab 1)
├── MapFragment (Tab 2)
└── SettingsFragment (Tab 3)

NavigationService (Foreground Service)
├── BleManager
├── GpsManager
├── RoutingEngine
├── MapRenderer (tạo ảnh JPEG)
├── WeatherProvider
├── TimeSync
└── TextToSpeechManager

ShareReceiverActivity
NotificationListenerService
```

### 5.2. NavigationService – trung tâm xử lý

**BleManager:**

- Kết nối tới ESP32 (MAC lưu trong SharedPrefs).
- Gửi dữ liệu:
  - `CHA_NAV`: gửi text (ví dụ `"nextRd=Nguyen Hue\ndistToNext=120m\neta=15:30"`).
  - `CHA_NAV_TBT_ICON`: gửi byte (0–20).
  - `CHA_GPS_SPEED`: gửi text (km/h).
  - `CHA_TIME`: gửi 4 byte Unix timestamp.
  - `CHA_WEATHER`: gửi JSON `{"t":30,"i":"01d"}`.
  - `CHA_MAP_IMAGE`: gửi header + JPEG (chia gói).
- Nhận dữ liệu:
  - `CHA_DEVICE_CTRL`: đọc notification, parse bitfield để biết khi nào bật/tắt Map mode, zoom in/out.

**RoutingEngine:**

- Khi có điểm đến (từ Tab 2 hoặc Share), gọi OSRM:
  ```
  https://router.project-osrm.org/route/v1/driving/{lng1},{lat1};{lng2},{lat2}?steps=true&geometries=polyline&overview=full
  ```
- Parse JSON → lấy encoded polyline và danh sách steps.
- Fallback sang ORS/GraphHopper nếu có key và OSRM lỗi.
- **Tự động phát hiện lệch đường**: mỗi GPS fix, tính khoảng cách vuông góc từ vị trí hiện tại đến polyline. Nếu > ngưỡng (20m), gọi lại API với vị trí hiện tại → cập nhật polyline và steps.

**HUD Data Sourcing:**

- Ưu tiên: nhận broadcast từ `NotificationListenerService` (parse thông báo Google Maps) → `nextRd`, `distToNext`, `eta`, iconHash.
- Fallback: dùng OSRM step hiện tại → `nextRd` = step.name, `distToNext` = step.distance, icon = map maneuver type → index 0–20.
- Gửi `CHA_NAV` và `CHA_NAV_TBT_ICON` mỗi khi step thay đổi hoặc mỗi 2 giây.

**MapRenderer:**

- Chỉ hoạt động khi `CHA_DEVICE_CTRL` bit2 = 1.
- Tạo MapView ẩn (OSMdroid, tile source từ Settings).
- Cập nhật vị trí tâm theo GPS, zoom theo lệnh từ ESP32.
- Vẽ polyline, marker vị trí (xoay theo bearing).
- Chụp Bitmap 240×240, nén JPEG (quality 70%).
- Gửi qua `CHA_MAP_IMAGE`: gửi 4 byte size, sau đó gửi dữ liệu JPEG thành các gói.
- Tần suất: tối đa 2 fps, chỉ gửi khi vị trí thay đổi > ngưỡng pixel (cấu hình trong Settings).

**WeatherProvider:**

- Mỗi 10 phút gọi Open‑Meteo: `https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&current_weather=true`
- Trích `temperature`, `weathercode` → map sang icon string (01d, 02d…) → gửi `CHA_WEATHER`.

**TimeSync:**

- Gửi `System.currentTimeMillis()/1000` qua `CHA_TIME` khi kết nối và mỗi 5 phút.

**TextToSpeechManager:**

- Khi `distToNext` < 100m hoặc < 30m, đọc hướng dẫn (ví dụ: "Rẽ phải vào Nguyễn Huệ sau 100 mét").

### 5.3. Các Fragment

- **ConnectionFragment**: quản lý kết nối BLE, hiển thị log, nhập API key ORS/GraphHopper.
- **MapFragment**:
  - MapView tương tác (OSMdroid), search bar Nominatim, nút "Chỉ đường".
  - Khi service đang chạy, observe dữ liệu vị trí/polyline từ service và hiển thị realtime (có thể dùng `LocalBroadcastManager` hoặc `SharedFlow`).
- **SettingsFragment**: cấu hình theme, tile source, zoom, fps, off‑route threshold…

### 5.4. Xử lý Share từ Google Maps

- `ShareReceiverActivity` nhận URL, parse lấy tọa độ, mở `MainActivity` với điểm đến.

## 6. Kịch bản hoạt động thực tế (lái xe)

1. Người dùng mở app, vào Tab 2, tìm điểm đến hoặc nhận share từ Google Maps.
2. Nhấn "Bắt đầu" → `NavigationService` khởi động, kết nối BLE, bắt đầu gửi dữ liệu.
3. ESP32 đang ở HUD: hiển thị mũi tên rẽ, đường, khoảng cách, tốc độ.
4. Người dùng nhấn nút MODE → menu overlay hiện ra → chọn MAP → ESP32 gửi `CHA_DEVICE_CTRL` (bit2=1) → service bắt đầu render ảnh JPEG và gửi → màn hình ESP32 hiển thị bản đồ di chuyển mượt.
5. Khi đi lệch, service tự động tính lại lộ trình, cập nhật HUD và ảnh bản đồ.
6. Nhấn nút MODE lần nữa chọn STATUS → ESP32 hiển thị đồng hồ, thời tiết, điện áp.
7. Kết thúc hành trình, người dùng dừng service từ notification hoặc Tab 2.

## 7. Yêu cầu chung cho code

- Code phải **hoàn chỉnh, có comment**, sẵn sàng biên dịch.
- Xử lý lỗi: mất BLE (tự động kết nối lại), không có thông báo Google Maps (fallback OSRM), lỗi API (thử lại hoặc fallback), không đủ PSRAM (kiểm tra cấp phát).
- Tối ưu: sử dụng PSRAM hợp lý, tránh tràn heap, truyền BLE hiệu quả.
- Giao diện Android dùng Material Design, hỗ trợ chế độ tối.

Hãy triển khai **toàn bộ firmware và ứng dụng Android** dựa trên thiết kế trên. Bắt đầu với cấu trúc project, sau đó viết từng file.

```

```
