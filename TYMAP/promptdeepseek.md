Bạn là chuyên gia phát triển nhúng và Android. Hãy phát triển và hoàn thiện **Hệ thống dẫn đường xe máy thông minh TYMAP**, bao gồm:

1. **Firmware cho ESP32‑S3** (Arduino / PlatformIO) điều khiển màn hình tròn GC9A01 240×240, giao tiếp BLE.
2. **Firmware cho ESP32‑C3** (Arduino / PlatformIO) điều khiển màn OLED 128×64 (SSD1306/SH1106), nhận ảnh 1‑bit qua BLE.
3. **Ứng dụng Android** (Kotlin, minSdk 30, targetSdk 36) – code hiện tại đã có đầy đủ các tính năng cơ bản. Bạn chỉ cần **bổ sung và tinh chỉnh** những phần được yêu cầu bên dưới, **giữ nguyên code đang chạy ổn định**.

---

## 1. HỒ SƠ GATT BLE (ĐÃ CÓ ĐỦ 13 CHARACTERISTIC)

| Characteristic | UUID | Hướng | Mô tả |
|----------------|------|-------|-------|
| CHA_NAV | `0b11deef-...` | App→ESP | Chuỗi key=value\n... |
| CHA_NAV_TBT_ICON | `d4d8fcca-...` | App→ESP | Chuỗi `hash=<hex>` hoặc 1 byte index hoặc 288 byte bitmap |
| CHA_ICON_DATA | `e2f3a4b5-...` | App→ESP | 4 byte hash + bitmap 48×48 1‑bit |
| CHA_GPS_SPEED | `98b6073a-...` | App→ESP | Chuỗi km/h |
| CHA_SETTINGS | `9d37a346-...` | App→ESP | key=value (brightness, popupDuration, speedWarning...) |
| CHA_TIME | `a1b2c3d4-...` | App→ESP | 4 byte LE uint32 |
| CHA_WEATHER | `b2c3d4e5-...` | App→ESP | JSON `{"t":30.5,"i":"01d"}` |
| CHA_MAP_IMAGE | `c3d4e5f6-...` | App→ESP | 4 byte size + JPEG |
| CHA_OLED_IMAGE | `e1f2a3b4-...` | App→ESP | 2 byte size + bitmap 1‑bit 128×64 |
| CHA_DEVICE_CTRL | `d4e5f6a7-...` | ⇄ | 1 byte bitfield (bit0=zoomIn, bit1=zoomOut, bit2=mapMode, bit3=exitPopup) |
| CHA_REMOTE_CMD | `f1a2b3c4-...` | App→ESP | 1 byte lệnh (0x10=HUD, 0x11=MAP, 0x12=STATUS, 0x20=Refresh, 0x30=Ping, 0xFF=Restart) |
| CHA_DEVICE_STATUS | `a1b2c3d4-...` | ESP→App | key=value (mode, voltage, rssi, display, icon_req, icon_ack) |
| CHA_NOTIFICATION | `c1d2e3f4-...` | App→ESP | JSON `{"app":"...","title":"...","message":"..."}` |

---

## 2. NHỮNG PHẦN CẦN BỔ SUNG / TINH CHỈNH

### 2.1. Firmware ESP32‑S3 (PlatformIO/Arduino)

- **Thư viện**: TFT_eSPI (GC9A01 240×240, font MyFont từ FontMaker), NimBLE‑Arduino, JPEGDEC, ESP32Time, OneButton.
- **Cache Icon (Hash)**: Mảng `{uint32_t hash, uint8_t bitmap[288]}` tối đa 50 phần tử trong RAM (FIFO). Khi nhận `CHA_NAV_TBT_ICON` (hash), tìm trong cache. Nếu không có, gửi `icon_req=<hash>` qua `CHA_DEVICE_STATUS`. Khi nhận `CHA_ICON_DATA`, lưu hash + bitmap, gửi `icon_ack=<hash>`.
- **HUD Mode**: Hiển thị icon, text chỉ dẫn, tốc độ, ETA. **Timeout 3 giây**: nếu không có `CHA_NAV` mới, tự động chuyển sang STATUS. Khi có dữ liệu mới, quay lại HUD.
- **Popup Map trong HUD**: Nếu `popupEnabled` (từ CHA_SETTINGS), khi nhận `CHA_MAP_IMAGE` trong lúc đang HUD, ẩn HUD, hiển thị JPEG toàn màn hình trong `popupDuration` giây (mặc định 5), sau đó quay lại HUD. Nếu nhận `CHA_DEVICE_CTRL` bit3=1, thoát popup ngay.
- **MAP Mode**: Giải mã JPEG từ `CHA_MAP_IMAGE` bằng JPEGDEC, hiển thị toàn màn hình.
- **STATUS Mode**: Đồng hồ, thời tiết, điện áp ắc quy, thông báo từ `CHA_NOTIFICATION`.
- **Menu chồng**: MODE button mở sprite PSRAM chọn HUD/MAP/STATUS.
- **ZOOM button**: Gửi `CHA_DEVICE_CTRL` (bit0/bit1).
- **OTA**: Placeholder tham khảo chronos-esp32.

### 2.2. Firmware ESP32‑C3 (PlatformIO/Arduino)

- **Thư viện**: U8g2 (OLED 128×64), NimBLE‑Arduino, ESP32Time, OneButton (tùy chọn).
- **HUD Mode**: Text chỉ dẫn rút gọn. Timeout 3 giây về STATUS.
- **MAP Mode**: Nhận `CHA_OLED_IMAGE` (1024 byte), hiển thị `u8g2.drawXBM(0,0,128,64,buffer)`.
- **STATUS Mode**: Thời gian, điện áp, thông báo từ `CHA_NOTIFICATION`.
- **BLE**: Gửi `CHA_DEVICE_STATUS` định kỳ với `display=OLED128x64`.

### 2.3. Ứng dụng Android

**Code hiện tại đã có đầy đủ các tính năng:**
- 4 tab: Connection, Map, Settings, Notifications
- OSMdroid với 6 tile sources
- Photon→Nominatim search
- 5 routing engines với fallback
- Headless MapView 240×240
- ScreenCaptureManager (chụp Google Maps)
- CropConfigActivity (cấu hình vùng cắt)
- Hash icon protocol
- OLED bitmap conversion
- Notification forwarding
- ShareReceiverActivity với UrlParser

**Những điểm cần bổ sung/tinh chỉnh:**

1. **Google Maps Continuous với fallback**: Trong `NavigationService`, khi chế độ `map_capture_mode = 1` (Google Maps), kiểm tra `isGoogleMapsForeground()`. Nếu true, dùng `ScreenCaptureManager` chụp màn hình. Nếu false, **tự động fallback sang OSM headless MapView** (đã có sẵn).

2. **Popup Map Trigger**: Khi `turn_screenshot_enabled = true` và ESP32 đang ở HUD, app phải gửi `CHA_MAP_IMAGE` khi `distToNext ≤ 500m` và `≤ 200m`. Logic này cần được thêm vào `NavigationService` (nếu chưa có).

3. **Auto-connect BLE**: Khi `NavigationService` khởi động, tự động kết nối đến MAC cuối cùng trong `paired_history` (đã có sẵn trong ConnectionFragment, cần đảm bảo logic tự động gọi `connect()` khi service start).

4. **Device Type Detection**: App đọc `CHA_DEVICE_STATUS` → `display` để biết S3 hay C3, từ đó chọn gửi `CHA_MAP_IMAGE` (JPEG) hay `CHA_OLED_IMAGE` (bitmap 1‑bit). Logic này đã có trong BleManager, cần đảm bảo MapRenderer cũng tuân thủ.

5. **Settings OLED Options**: Khi thiết bị là C3, tự động ẩn các tùy chọn JPEG (FPS, quality) và hiển thị các tùy chọn OLED (Threshold, Dithering, Invert, Color Filter). Đã có trong SettingsFragment, cần kiểm tra hoạt động.

---

## 3. YÊU CẦU CHẤT LƯỢNG CODE

- **Giữ nguyên code hiện có đang chạy ổn định.**
- Chỉ thêm hoặc sửa những phần được mô tả trong mục 2.
- Code Kotlin và C++ sạch sẽ, comment rõ ràng.
- Xử lý lỗi: mất kết nối BLE, API timeout, thiếu quyền.
- **Xuất ra project sẵn sàng biên dịch và chạy.**

---

## 4. LIÊN KẾT THAM KHẢO
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