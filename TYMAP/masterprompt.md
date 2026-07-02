```text
Bạn là chuyên gia phát triển nhúng và Android. Hãy thực hiện TUẦN TỰ các nhiệm vụ sau cho **Hệ thống dẫn đường xe máy thông minh TYMAP**.

---

# NHIỆM VỤ 1 – QUÉT VÀ LẬP TÀI LIỆU APP ANDROID HIỆN CÓ

Bạn là một chuyên gia phân tích mã nguồn Android. Hãy quét toàn bộ project Android trong workspace hiện tại và tạo file `TYMAP_OVERVIEW.md` mô tả chi tiết mọi thành phần của app. File này sẽ được dùng để người khác hiểu rõ app hiện có mà không cần đọc toàn bộ code.

## YÊU CẦU NỘI DUNG FILE TYMAP_OVERVIEW.md

### 1. CẤU TRÚC THƯ MỤC & FILE
- Liệt kê đầy đủ cây thư mục (package structure) với tất cả các file .kt, .xml, .gradle.
- Mỗi file ghi rõ đường dẫn và mục đích ngắn gọn (1 câu).

### 2. CÁC MÀN HÌNH & FRAGMENT
- Với mỗi Activity/Fragment:
  - Tên class, đường dẫn file.
  - Chức năng chính (1-2 câu).
  - Các View/Component chính (RecyclerView, MapView, Button...).
  - Các phương thức quan trọng (onCreate, onViewCreated, các hàm xử lý sự kiện...).
  - Cách nó giao tiếp với NavigationRepository hoặc Service (nếu có).

### 3. DỊCH VỤ NỀN (SERVICES)
- Với mỗi Service:
  - Tên class, loại (Foreground, Bound...).
  - Chức năng chính.
  - Các Manager/Engine mà nó điều phối.
  - Vòng đời (onStartCommand, onDestroy...).

### 4. BLE GATT PROFILE HIỆN TẠI
- Liệt kê TẤT CẢ các UUID đang dùng.
- Với mỗi UUID: tên characteristic, hướng truyền, định dạng dữ liệu, mục đích.
- Cách BleManager kết nối, gửi, nhận dữ liệu.

### 5. CÁC TÍNH NĂNG CHÍNH ĐÃ CÓ
- Mô tả chi tiết từng tính năng:
  - Bản đồ: tile source nào, tìm kiếm ra sao, định tuyến engine nào.
  - Dẫn đường: HUD data lấy từ đâu, gửi qua BLE thế nào.
  - Cài đặt: những mục nào đã có.
  - Kết nối BLE: quét, ghép nối, điều khiển ESP32.
  - Chia sẻ từ Google Maps: ShareReceiverActivity hoạt động thế nào.
  - Thông báo: NotificationListenerService đọc gì, gửi đi đâu.

### 6. CÁC THƯ VIỆN & DEPENDENCIES
- Liệt kê tất cả thư viện trong build.gradle (phiên bản, mục đích).

### 7. KIẾN TRÚC DỮ LIỆU
- Mô tả NavigationRepository (các StateFlow/SharedFlow gì).
- Các data class quan trọng (RouteInfo, NavigationData...).

### 8. LUỒNG DỮ LIỆU CHÍNH
- Vẽ sơ đồ text mô tả luồng dữ liệu chính (VD: GPS → NavigationService → BLE → ESP32).

## ĐỊNH DẠNG FILE
- Viết bằng Markdown (.md).
- Dùng tiếng Việt.
- Có thể chèn code block ngắn để minh họa nếu cần.
- Đặt tên file: TYMAP_OVERVIEW.md, lưu vào thư mục gốc của project.

## LƯU Ý
- Không bỏ sót bất kỳ file .kt nào.
- Không thêm bất kỳ code mới hay đề xuất gì. Chỉ MÔ TẢ code hiện có.

---

# NHIỆM VỤ 2 – PHÁT TRIỂN & HOÀN THIỆN TOÀN BỘ HỆ THỐNG

Sau khi đã có file `TYMAP_OVERVIEW.md`, bạn sẽ dựa vào đó và các yêu cầu dưới đây để **bổ sung, tinh chỉnh** code cho cả firmware ESP32 và ứng dụng Android.

## NGUYÊN TẮC QUAN TRỌNG NHẤT
- **GIỮ NGUYÊN CODE HIỆN CÓ ĐANG CHẠY ỔN ĐỊNH.**
- **CHỈ THÊM HOẶC SỬA** những phần được mô tả bên dưới.
- **KHÔNG ĐƯỢC XÓA** bất kỳ tính năng nào đã build trước đó.

---

## PHẦN A – HỆ THỐNG TỔNG QUAN

```
[ESP32‑S3 + GC9A01 240×240] ────┐
                                 ├── BLE ─── [Android App] ─── Internet ─── [API miễn phí]
[ESP32‑C3 + OLED 128×64] ───────┘
```

- **App Android** là trung tâm: GPS, định tuyến, render bản đồ, thời tiết, giọng nói, gửi HUD và ảnh map.
- **ESP32** là màn hình phụ: 3 chế độ HUD/MAP/STATUS, gửi nút bấm và trạng thái về app.
- Chỉ dùng API miễn phí (Photon, Nominatim, OSRM, OpenRouteService, GraphHopper, Valhalla, Mapbox free tier, Open‑Meteo, Overpass).

---

## PHẦN B – PHẦN CỨNG ESP32‑S3 (ĐÃ TEST THÀNH CÔNG)

### Board & Chân kết nối
- Board: ESP32‑S3‑DevKitC‑1, Flash 4MB, PSRAM 2MB.
- Màn: GC9A01 240×240 SPI (chỉ MOSI, không MISO).

| GC9A01 | ESP32‑S3 |
|--------|----------|
| VCC    | 3.3V     |
| GND    | GND      |
| SCL    | GPIO 12  |
| SDA    | GPIO 11  |
| RST    | GPIO 10  |
| DC     | GPIO 9   |
| CS     | GPIO 13  |
| BL     | 3.3V hoặc GPIO PWM |

- Nút: MODE (GPIO0), ZOOM (GPIO1) — kéo lên ngoài.
- Ắc quy: GPIO3 qua cầu phân áp 100k/27k, tụ 100nF, Zener 3.3V.

### platformio.ini (ĐÃ TEST)
```ini
[env:esp32-s3-devkitc-1]
platform = platformio/espressif32 @ 6.6.0
board = esp32-s3-devkitc-1
framework = arduino
monitor_speed = 115200

board_build.flash_mode = qio
board_upload.flash_size = 4MB
board_build.partitions = default.csv

build_flags =
	-D BOARD_HAS_PSRAM
	-D ARDUINO_USB_CDC_ON_BOOT=1
	-D ARDUINO_RUNNING_CORE=1
	-D ARDUINO_EVENT_RUNNING_CORE=1
	-D USER_SETUP_LOADED=1
	-D GC9A01_DRIVER
	-D TFT_WIDTH=240
	-D TFT_HEIGHT=240
	-D TFT_MISO=-1
	-D TFT_MOSI=11
	-D TFT_SCLK=12
	-D TFT_CS=13
	-D TFT_DC=9
	-D TFT_RST=10
	-D SPI_FREQUENCY=40000000
	-D USE_HSPI_PORT=1
	-D LOAD_GLCD=1
	-D LOAD_FONT2=1
	-D LOAD_FONT4=1

lib_deps =
	bodmer/TFT_eSPI @ ^2.5.43
```

**Quan trọng:** Không dùng platform version mới hơn 6.6.0 vì gây boot loop. Phải bật `BOARD_HAS_PSRAM`. Luôn `pio run --target erase` trước lần upload đầu tiên.

---

## PHẦN C – PHẦN CỨNG ESP32‑C3
- Board: ESP32‑C3‑DevKitM‑1, Flash 4MB, không PSRAM.
- Màn: OLED 128×64 I2C (0x3C) hoặc SPI, dùng U8g2.
- Nút: tùy chọn.
- Ắc quy: tương tự S3.

---

## PHẦN D – HỒ SƠ GATT BLE (13 CHARACTERISTIC)

| Characteristic | UUID | Hướng | Định dạng |
|----------------|------|-------|-----------|
| CHA_NAV | `0b11deef-...` | App→ESP | Text `key=value\n` |
| CHA_NAV_TBT_ICON | `d4d8fcca-...` | App→ESP | `hash=<hex>` hoặc 1 byte index hoặc 288 byte bitmap |
| CHA_ICON_DATA | `e2f3a4b5-...` | App→ESP | 4 byte hash + 288 byte bitmap 1‑bit 48×48 |
| CHA_GPS_SPEED | `98b6073a-...` | App→ESP | Text km/h |
| CHA_SETTINGS | `9d37a346-...` | App→ESP | Text `key=value\n` |
| CHA_TIME | `a1b2c3d4-...` | App→ESP | 4 byte LE uint32 Unix |
| CHA_WEATHER | `b2c3d4e5-...` | App→ESP | JSON `{"t":30.5,"i":"01d"}` |
| CHA_MAP_IMAGE | `c3d4e5f6-...` | App→ESP | 4 byte size LE + JPEG |
| CHA_OLED_IMAGE | `e1f2a3b4-...` | App→ESP | 2 byte size LE + bitmap 1‑bit 128×64 |
| CHA_DEVICE_CTRL | `d4e5f6a7-...` | ⇄ | 1 byte bitfield |
| CHA_REMOTE_CMD | `f1a2b3c4-...` | App→ESP | 1 byte lệnh |
| CHA_DEVICE_STATUS | `a1b2c3d4-...` | ESP→App | Text `key=value\n` |
| CHA_NOTIFICATION | `c1d2e3f4-...` | App→ESP | JSON thông báo |

- MTU 512, auto‑retry 3 lần.
- Ảnh gửi theo chunk (MTU‑3).

---

## PHẦN E – FIRMWARE ESP32‑S3 (PlatformIO/Arduino)

### Thư viện
TFT_eSPI (cấu hình qua build_flags), NimBLE‑Arduino, JPEGDEC, ESP32Time, OneButton.

### PSRAM
- Buffer JPEG 32KB, sprite menu 240×240, cache icon 50 phần tử — tất cả trong PSRAM.

### Cache Icon (Hash, FIFO, RAM)
- Mảng `{uint32_t hash, uint8_t bitmap[288]}`.
- Khi nhận `CHA_NAV_TBT_ICON`: tìm hash → nếu thiếu gửi `icon_req`.
- Khi nhận `CHA_ICON_DATA`: lưu, gửi `icon_ack`, vẽ ngay.

### Chế độ hiển thị
- **HUD**: icon, text, tốc độ, ETA. **Timeout 3 giây không có `CHA_NAV` mới → tự động chuyển sang STATUS. Khi có dữ liệu mới → quay lại HUD.**
- **MAP**: nhận JPEG, giải mã JPEGDEC, full màn hình.
- **STATUS**: đồng hồ, thời tiết, điện áp, thông báo từ `CHA_NOTIFICATION`.
- **Popup Map (trong HUD)**: Khi `popupEnabled`, nhận `CHA_MAP_IMAGE` lúc đang HUD → ẩn HUD, hiển thị JPEG toàn màn hình trong `popupDuration` giây (mặc định 5, cấu hình từ app). Hết thời gian hoặc nhận `CHA_DEVICE_CTRL` bit3=1 → quay lại HUD.

### Menu & Nút
- MODE: short = mở menu sprite PSRAM chọn HUD/MAP/STATUS, long = chọn, timeout 5s.
- ZOOM: chỉ MAP, gửi `CHA_DEVICE_CTRL` bit0/bit1.

### Lệnh từ xa
- `CHA_REMOTE_CMD`: 0x10=HUD, 0x11=MAP, 0x12=STATUS, 0x20=Refresh, 0x30=Ping, 0xFF=Restart.

---

## PHẦN F – FIRMWARE ESP32‑C3 (PlatformIO/Arduino)

- U8g2, NimBLE‑Arduino, ESP32Time.
- HUD (text rút gọn, timeout 3s), MAP (`CHA_OLED_IMAGE` 1024 byte → `u8g2.drawXBM`), STATUS.
- Cache icon 20 phần tử.
- Gửi `CHA_DEVICE_STATUS` với `display=OLED128x64`.

---

## PHẦN G – ỨNG DỤNG ANDROID (BỔ SUNG VÀO CODE HIỆN CÓ)

### Kiến trúc
- Single Activity + ViewPager2 + BottomNavigation (4 tab: Connection, Map, Settings, Notifications).
- NavigationService (Foreground), NavigationRepository (Singleton).

### Tính năng cần đảm bảo có đủ (nếu thiếu thì bổ sung)

#### Tab Connection
- Quét BLE, lịch sử, trạng thái, điều khiển ESP32 (nút chế độ, độ sáng, ping, restart, zoom).
- Quản lý API key (ORS, GraphHopper, Mapbox, Photon).

#### Tab Map
- OSMdroid 6 tile sources (Positron, Mapnik, Dark Matter, Voyager, Satellite, Custom URL MapCN).
- Photon → Nominatim search.
- 5 engine định tuyến (OSRM, ORS, GraphHopper, Valhalla, Mapbox) với fallback theo thứ tự ưu tiên kéo thả.
- Đa điểm dừng (≤3), tìm dọc đường (Overpass), đo khoảng cách, yêu thích (Room).
- Long press thả ghim, reverse geocode Nominatim.
- Place/Navigation Bottom Sheet, Track‑up, auto‑zoom, cảnh báo tốc độ, phát hiện lệch tuyến (tự động tính lại sau 10s).
- Chụp Google Maps (MediaProjection, foreground check, crop, fallback OSM nếu Maps không foreground).
- Popup Map khi đến ngã rẽ (500m/200m) gửi `CHA_MAP_IMAGE`.
- CropConfigActivity kéo thả khung vuông 1:1.

#### Tab Settings
- Hiển thị, Bản đồ, Định tuyến, Giọng nói (đầy đủ như các prompt trước).
- Data Sending: capture mode, FPS, threshold, JPEG quality, idle timeout, popup duration.
- **OLED Options (LUÔN HIỂN THỊ, không cần kết nối C3):** Threshold, Dithering, Invert, Color Filter (color picker, tolerance, filter invert).
- Google Maps Screenshot toggle + popup duration.

#### Tab Notifications
- Bật/tắt từng app (Cuộc gọi, SMS, Zalo,...), nút Test gửi `CHA_NOTIFICATION`.

### Icon Hash Protocol (trong NavigationService)
- Lấy icon Bitmap từ Google Maps notification.
- Tính CRC32 → gửi `hash=<hex>` qua `CHA_NAV_TBT_ICON`.
- Khi nhận `icon_req=<hash>` từ `CHA_DEVICE_STATUS` → gửi `CHA_ICON_DATA` (hash + 288 byte).

### MapRenderer
- Xác định S3/C3 → gửi JPEG hoặc bitmap 1‑bit.
- C3: pipeline Threshold/Dithering/ColorFilter → 1024 byte.

### Auto-connect BLE
- Khi NavigationService start, tự kết nối MAC cuối cùng trong lịch sử.

### Device Detection
- Đọc `CHA_DEVICE_STATUS` → `display` để chọn định dạng ảnh.

### UrlParser (domain riêng)
- `extractUrlFromText()`, `resolveRedirect()` (OkHttp, `followRedirects(false)`), `parseCoordinates()` (regex `/dir/` > `@lat,lng` > `q=lat,lng`), `toOsmUrl()`.

### ShareReceiverActivity
- Nhận `ACTION_SEND` → UrlParser → tọa độ → MapFragment; nếu không có tọa độ → Photon → Nominatim.

### Quyền
- Location, BLE, Foreground Service, Notifications, Notification Listener, Usage Stats, System Alert Window.

---

## PHẦN H – YÊU CẦU CHẤT LƯỢNG CODE
- Kotlin/C++ sạch, comment rõ ràng.
- Tách domain/network.
- Xử lý lỗi BLE, API timeout, thiếu quyền, Google Maps không foreground.
- **Giữ nguyên code đang chạy ổn định, chỉ thêm/sửa.**
- **Xuất ra project sẵn sàng biên dịch và chạy.**

---

## PHẦN I – LIÊN KẾT THAM KHẢO
- Photon: https://photon.komoot.io/
- Nominatim: https://nominatim.openstreetmap.org/
- OSRM: https://router.project-osrm.org/
- OpenRouteService: https://openrouteservice.org/
- GraphHopper: https://www.graphhopper.com/
- Valhalla: https://valhalla1.openstreetmap.de/
- Mapbox: https://www.mapbox.com/
- CartoDB: https://carto.com/
- MapCN: https://github.com/AnmolSaini16/mapcn
- Open‑Meteo: https://open-meteo.com/
- Overpass: https://overpass-api.de/
- chronos‑esp32: https://github.com/fbiego/chronos-esp32
- FontMaker: https://github.com/daonguyen207/FontMaker
- image2cpp: https://javl.github.io/image2cpp/
```

---

## PHẦN J – QUY TẮC CẢI THIỆN HIỆU NĂNG & GPS (BẮT BUỘC)

### 1. Quy tắc lập trình & Hiệu năng:
*   **APP-19 (Đơn giản hóa Polyline):** Trước khi vẽ polyline, áp dụng thuật toán Douglas-Peucker để đơn giản hóa số điểm với ngưỡng 3 pixel ở mức zoom hiện tại.
*   **APP-20 (Quản lý overlays):** Mỗi khi vẽ lại polyline, duyệt overlays và xóa sạch tất cả đối tượng `Polyline` cũ trước khi thêm mới.
*   **APP-23 (Anti-alias & Invalidate):** Bật thuộc tính chống răng cưa (`isAntiAlias = true`) cho `outlinePaint` của `Polyline`. Gom các sự kiện vẽ lại bằng `postInvalidateDelayed(50)` thay vì gọi `invalidate()` trực tiếp.
*   **APP-24 (GPS Callback & Threads):** Không chạy các tác vụ tính toán nặng (off-route, giải mã polyline) trên Main Thread. Chuyển các tác vụ nặng sang `Dispatchers.Default`. Tần suất off-route detection phải được giới hạn tối thiểu 2 giây/lần.
*   **APP-25 (Camera map):** Camera map bắt buộc sử dụng `animateTo()` (khoảng 200ms-300ms) để bám theo vị trí thay vì gán cứng qua `setCenter()`.
*   **APP-26 (GPS Nền):** Đảm bảo dịch vụ chạy nền (Foreground Service) khai báo đầy đủ type: `location` và xin quyền `ACCESS_BACKGROUND_LOCATION` để tránh bị ngắt kết nối GPS khi tắt màn hình.
*   **APP-27 (Cấu hình GPS):** GPS request cấu hình `minDistanceMeters = 2f` và `Priority.PRIORITY_HIGH_ACCURACY` để cân bằng độ nhạy và thời lượng pin.
*   **APP-28 (Xoay Marker mượt mà):** Hướng xoay marker xe/người dùng phải được nội suy mượt mà qua `ValueAnimator` với duration khoảng 300ms giữa các lần cập nhật vị trí, không gán trực tiếp gây giật.
*   **APP-29 (Phương tiện di chuyển):** Bổ sung cấu hình chọn phương tiện (Ô tô / Xe máy) trong Cài đặt. Khi ở chế độ Xe máy: GraphHopper sử dụng profile `motorcycle`, các engine khác (OSRM, ORS, Valhalla, Mapbox) thêm logic cấu hình/tham số tránh đường cao tốc (highway/motorway).
*   **APP-30 (Bản đồ vệ tinh & Fallback):** Cấu hình URL cho ảnh vệ tinh ESRI là `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`. Nếu quá trình tải tile thất bại, tự động chuyển về bản đồ nền mặc định `CartoDB Positron` để tránh màn hình xám.