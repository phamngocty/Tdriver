# TYMAP - BỘ NHỚ TRI THỨC VỀ CHẾ ĐỘ MAP HUD & HỆ THỐNG GIAO DIỆN (KNOWLEDGE MEMORY)

> **Dự án:** TYMAP (Hệ thống Đồng hồ Thông minh & Dẫn đường Xe máy)  
> **Phần cứng:** ESP32-S3 + Màn hình tròn GC9A01 1.28 inch 240x240 Pixel (`ZJY128R-IG01` Datasheet)  
> **Ứng dụng Android:** TYMAP App (Kotlin, Osmdroid, BLE GATT, MediaProjection Screen Capture)  
> **Cập nhật mới nhất:** 2026-08-09 (Ghi nhớ Quy tắc Số 1: Ưu tiên hàng đầu Google Maps Notifications & Chi tiết cơ chế chuyển CRUISE HUD / MAP HUD / MAP PURE)  

---

## 0. TRIẾT LÝ DỰ ÁN ZERO-COST & NGUỒN DỮ LIỆU ƯU TIÊN HÀNG ĐẦU (PRIMARY DATA PRIORITY)

> [!IMPORTANT]
> **ƯU TIÊN HÀNG ĐẦU SỐ 1: DẪN ĐƯỜNG TỪ THÔNG BÁO GOOGLE MAPS**
> - Dự án được định hướng vận hành với **chi phí bằng 0 (Zero-Cost)**.
> - Google Maps là dịch vụ dẫn đường tối ưu nhất tại Việt Nam. Do đó, **Google Maps Notification Listener (`GMapsNotificationListener.kt`)** chính là **NGUỒN DỮ LIỆU CHÍNH SỐ 1** (tên đường `nextStreet`, khoảng cách `distToNext`, icon rẽ Google Maps `customIconBitmap`, ETE/ETA) nuôi toàn bộ các chế độ hiển thị trên ESP32.
> - **Chế độ Google Maps Popup OSM (khi cách ngã rẽ <= 100m):** Đọc thông báo Google Maps, phát hiện xe sắp tới điểm rẽ (<= 100m) và tự động chụp/render ảnh ngã rẽ gửi qua BLE sang ESP32 để người lái xem bản đồ chính xác đúng lúc cần.
> - Cả 3 chế độ `CRUISE HUD`, `MAP HUD` và `MAP PURE` **đều dùng chung 1 nguồn dữ liệu chuẩn xác duy nhất từ Google Maps Notification này!**

---

## 1. PHƯƠNG THỨC CHUYỂN ĐỔI GIỮA CRUISE HUD, MAP HUD VÀ MAP PURE

Người dùng có 2 cơ chế chuyển đổi linh hoạt:

### 1.1. Cơ chế Tự Động Thông Minh (Auto-Adaptive - Khuyên dùng):
Không cần chạm vào nút bấm khi đang lái xe, màn hình tự động biến đổi theo khoảng cách ngã rẽ từ Google Maps:

```
[Đi đường thẳng / Khoảng cách ngã rẽ Google Maps > 300m] 
   └───> Hiển thị: CRUISE HUD (Mũi tên to ở tâm, tên đường, khoảng cách + Tốc độ GPS km/h)

[Sắp tới điểm rẽ / Khoảng cách ngã rẽ Google Maps <= 300m] 
   └───> Tự chuyển sang: MAP HUD (Hình ảnh bản đồ ngã rẽ 70% + Thẻ nổi Google Maps 30%)

[Cách điểm rẽ <= 100m khi đang ở HUD Mode] 
   └───> Tự bật: MAP PURE (Bản đồ ngã rẽ thuần 100%) trong 5s rồi tự quay về CRUISE HUD

[Đã qua ngã rẽ > 50m] 
   └───> Tự quay lại: CRUISE HUD
```

### 1.2. Cơ chế Chuyển Thủ Công Bằng Nút Bấm Cứng (Manual Toggle):
- **Qua Menu Bánh xe 5 góc:** Nhấn nút `MODE` $\rightarrow$ Chọn mục `MAP` hoặc `HUD` $\rightarrow$ Nhấn giữ `MODE` 1.5s để chọn.
- **Chuyển nhanh Bản đồ thuần (`MAP PURE`):** Khi đang ở `MAP HUD`, **Nhấn giữ nút `ZOOM` 1.5s** sẽ ẩn ngay thẻ Floating Card để xem **Bản đồ thuần 100% (`MAP PURE`)**. Nhấn giữ 1.5s lần nữa để mở lại thẻ `MAP HUD`.

---

## 2. MENU CHUYỂN CHẾ ĐỘ BÁNH XE 5 CUNG TRÒN (`drawMenuOverlay()`)

Giao diện Menu được vẽ chuẩn xác dạng bánh xe 5 góc ôm sát viền tròn 240x240 (`drawMenuOverlay()` trong `gui.cpp`):

```
                       [ 12h: HUD ↑ ]
             (10h: NOTIF ✉)      (2h: MAP 🗺)
                     \   Center   /
                      \ [STATUS] /
             (7h: INFO ℹ)        (5h: STATUS 🕒)
```

---

## 3. CÁC MẪU GIAO DIỆN ĐỔI ĐỘNG TỪ ĐIỆN THOẠI KHÔNG NẠP LẠI CODE (BLE & NVS FLASH)

- **STATUS Mode:** Mẫu S1 (Minimalist Smartwatch), Mẫu S2 (Sport Racing Gauge), Mẫu S3 (Dual Energy Pill).
- **NOTIF Mode:** Mẫu N1 (Floating Card 3D Popup 5s), Mẫu N2 (Fullscreen Focus Card).
- **Đổi giao diện:** Người dùng chọn trên màn hình Cài đặt của App Android $\rightarrow$ BLE gửi opcode sang ESP32 $\rightarrow$ Lưu Flash NVS (`Preferences.h`) và đổi giao diện lập tức trong 0.1 giây.

---

## 4. SƠ ĐỒ DỮ LIỆU BLE & MÃ NGUỒN LIÊN QUAN

- **Bluetooth GATT Characteristics:**
  - `CHA_MAP_IMAGE` (`c3d4e5f6-a7b8-4901-c234-567890abcdef`): Luồng ảnh JPEG bản đồ ngã rẽ.
  - `CHA_NAV` (`0b11deef-1563-447f-aece-d3dfeb1c1f20`): Tên đường rẽ Google Maps (`nextStreet`) & khoảng cách (`distToNext`).
  - `CHA_NAV_TBT_ICON` (`d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad`): Icon hướng rẽ vector `navDirIdx`.
  - `CHA_ICON_DATA` (`e2f3a4b5-c6d7-4890-e123-456789abcdef`): Bitmap 1bpp Google Maps custom icon.
  - `CHA_GPS_SPEED` (`98b6073a-5cf3-4e73-b6d3-f8e05fa018a9`): Tốc độ GPS (`gpsSpeed`).
  - `CHA_NOTIFICATION` (`c1d2e3f4-a5b6-4789-c012-3456789abcde`): Nhận thông báo điện thoại JSON.
  - `CHA_SETTINGS` (`1c2d3e4f-5a6b-7890-b123-456789abcdef`): Đồng bộ cài đặt & style giao diện từ App Android.
- **Tệp mã nguồn chính cần can thiệp:**
  - Firmware: `TYMAP/firmware/esp32_s3_gc9a01/src/gui.h`, `gui.cpp`, `main.cpp`.
  - Android App: `TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt`, `GMapsNotificationListener.kt`, `SettingsFragment.kt`.
