# Danh sách lệnh BLE TYMAP (App → ESP32)

Tài liệu này chi tiết toàn bộ các đặc tính (Characteristics) và định dạng dữ liệu mà App Android gửi sang thiết bị ESP32-S3.

## 1. Dẫn đường & Icon (Dòng lệnh chính)

| Characteristic | UUID | Kiểu dữ liệu | Mô tả |
| :--- | :--- | :--- | :--- |
| **CHA_NAV** | `0b11deef-1563-447f-aece-d3dfeb1c1f20` | Text (`key=value\n`) | Gửi `active=1` để vào HUD, `active=0` để thoát. Kèm `dist`, `title`, `dir`, `eta`. |
| **CHA_NAV_TBT_ICON** | `d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad` | `hash=<HEX>` | Gửi mã băm 8 ký tự Hex của icon. ESP sẽ kiểm tra cache hoặc yêu cầu tải ảnh. |
| **CHA_ICON_DATA** | `e2f3a4b5-c6d7-4890-e123-456789abcdef` | Binary (292 bytes) | Phản hồi yêu cầu ảnh: 4 byte Hash (LE) + 288 byte Bitmap (1-bit, 48x48). |

## 2. Thông tin Hệ thống & Cảm biến

| Characteristic | UUID | Kiểu dữ liệu | Mô tả |
| :--- | :--- | :--- | :--- |
| **CHA_GPS_SPEED** | `98b6073a-5cf3-4e73-b6d3-f8e05fa018a9` | Text (Số) | Tốc độ di chuyển hiện tại (km/h). |
| **CHA_TIME** | `a1b2c3d4-e5f6-4789-a012-3456789abcde` | Binary (4 bytes) | Unix Timestamp (Local Time) để đồng bộ đồng hồ ESP32. |
| **CHA_PHONE_BATTERY** | `e5f6a7b8-c9d0-4123-e456-789012cdef01` | JSON | `{"level":85, "charging":true}` |
| **CHA_WEATHER** | `b2c3d4e5-f6a7-4890-b123-456789abcdef` | JSON | `{"t":30.5, "i":"01d"}` (t: nhiệt độ, i: mã icon). |

## 3. Hình ảnh & Cấu hình

| Characteristic | UUID | Kiểu dữ liệu | Mô tả |
| :--- | :--- | :--- | :--- |
| **CHA_MAP_IMAGE** | `c3d4e5f6-a7b8-4901-c234-567890abcdef` | Binary (Chunks) | Ảnh chụp bản đồ (JPEG). Gói đầu là 4 byte kích thước. |
| **CHA_SETTINGS** | `9d37a346-63d3-4df6-8eee-f0242949f59f` | Text (`k=v\n`) | Cấu hình: `brightness`, `popupDuration`, `hudTimeout`. |
| **CHA_REMOTE_CMD** | `f1a2b3c4-d5e6-4789-a012-3456789abcde` | Byte | `0x10`:HUD, `0x11`:MAP, `0x12`:STATUS, `0x13`:INFO, `0x14`:NOTIF, `0xFF`:Reset. |

## 4. Thông báo (Social)

| Characteristic | UUID | Kiểu dữ liệu | Mô tả |
| :--- | :--- | :--- | :--- |
| **CHA_NOTIFICATION** | `c1d2e3f4-a5b6-4789-c012-3456789abcde` | JSON | `{"app":"Zalo", "title":"Nam", "message":"Alo"}`. |

---
**Ghi chú về Icon 1-bit**:
Dữ liệu ảnh 48x48 pixel được nén thành 288 byte. Mỗi bit đại diện cho 1 điểm ảnh (1 = Sáng, 0 = Tắt). Thứ tự bit là MSB First.
