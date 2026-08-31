# Thiết Kế Cải Tiến Cảnh Báo Tốc Độ & Biển Báo Giao Thông Trên ESP32-S3 (GC9A01)

## 1. Tổng quan & Mục tiêu

Khắc phục lỗi logic cảnh báo tốc độ trên đồng hồ TYMAP ESP32-S3 (màn hình tròn GC9A01 240x240):
- **Hiện trạng lỗi:** Khi đang ở `MAP_MODE`, cảnh báo quá tốc độ làm đồng hồ bị ép nhảy sang `NOTIF_MODE` (hiển thị popup chữ toàn màn hình), sau đó khi hết thời gian popup thì bị văng về `STATUS_MODE` / `HUD_MODE` và mất bản đồ. Ngoài ra, hình tròn cảnh báo cũ vẽ ở giữa tâm màn hình (r=48px) che khuất toàn bộ nội dung.
- **Mục tiêu mới:**
  1. Giữ nguyên chế độ hiển thị hiện tại (`MAP_MODE`, `HUD_MODE`, `STATUS_MODE`, `MAP_HUD_MODE`, v.v.) khi có cảnh báo tốc độ hoặc camera phạt nguội.
  2. Bỏ hoàn toàn thông báo popup văn bản dạng text khi cảnh báo tốc độ.
  3. Hiển thị biển báo mini kích thước chuẩn (đường kính ~36-38px) tại **Góc trên bên phải** (`x: 185, y: 40`) trên tất cả các chế độ màn hình.
  4. Bổ sung hiệu ứng viền nhấp nháy đỏ nhẹ ở mép màn hình tròn khi xe vượt quá tốc độ giới hạn để cảnh báo trực quan.

---

## 2. Kiến trúc & Phân chia trách nhiệm

### Phía Android App (`TYMAP/app`)
- **[NavigationService.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt):**
  - Xóa bỏ lệnh gửi `bleManager.writeNotification(...)` trong logic kiểm tra quá tốc độ.
  - Tiếp tục gửi gói tin cảnh báo nhị phân 2-byte qua `CHA_WARNING_UUID` (`Byte 0`: Loại cảnh báo `0x01` Camera / `0x02` Giới hạn tốc độ; `Byte 1`: Tốc độ giới hạn km/h).

### Phía Firmware ESP32-S3 (`TYMAP/firmware/esp32_s3_gc9a01`)
- **[gui.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/gui.cpp):**
  - Cập nhật hàm `drawTrafficWarningOverlay()`:
    - Chuyển tọa độ tâm biển báo sang góc trên phải: `cx = 185, cy = 40, r = 18` (kích thước tổng thể ~38px).
    - Biển báo giới hạn tốc độ: Viền đỏ (độ dày 3px), nền trắng, số hiển thị màu đen đậm, có chữ "km/h" nhỏ phía dưới hoặc số lớn rõ ràng.
    - Biển báo Camera phạt nguội: Nền tối/đỏ, viền nổi bật, hiển thị icon Camera hoặc chữ "CAM".
    - Khi `gpsSpeed > trafficWarningValue` (và đang có biển báo tốc độ): Vẽ viền nhấp nháy đỏ nhẹ ở bán kính ngoài cùng màn hình (`r = 117..119`) chu kỳ 250ms.
- **[main.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp):**
  - Giữ nguyên `currentMode` khi nhận dữ liệu từ `CHA_WARNING_UUID`.
  - Khắc phục lỗi timeout của `NOTIF_MODE` (dòng 1826): Khi hết popup tin nhắn thật, khôi phục `currentMode = previousModeBeforeNotif` thay vì gán cứng `STATUS_MODE`.
  - Dọn dẹp đoạn code duplicate xử lý `CHA_WARNING_UUID` ở dòng 1383.

---

## 3. Chi tiết Giao diện Biển Báo trên từng Màn hình (240x240)

| Màn hình | Tọa độ Biển Báo | Tác động giao diện |
| :--- | :--- | :--- |
| **MAP_MODE** | `cx = 185, cy = 40` | Không che vị trí xe ở tâm (120, 120), không che thanh tốc độ ở đáy |
| **HUD_MODE** | `cx = 185, cy = 40` | Không che mũi tên chỉ hướng rẽ (120, 75) hay tên đường (120, 135) |
| **STATUS_MODE** | `cx = 185, cy = 40` | Không che đồng hồ kỹ thuật số chính giữa (120, 52) hay thời tiết |
| **MAP_HUD_MODE** | `cx = 185, cy = 40` | Không che bản đồ phía trên và thẻ HUD nổi ở đáy |

---

## 4. Kế hoạch Kiểm tra & Xác minh (Verification Plan)

1. **Kiểm tra biên dịch:**
   - Biên dịch Firmware ESP32-S3 GC9A01 bằng PlatformIO (`pio run -e esp32-s3-devkitc-1` hoặc môi trường tương ứng).
2. **Kiểm tra Logic:**
   - Giả lập nhận BLE Warning gói tin `[0x02, 0x3C]` (Giới hạn 60 km/h) khi đang ở `MAP_MODE` -> Xác nhận màn hình vẫn giữ nguyên `MAP_MODE` và hiển thị biển báo 60 ở góc trên phải.
   - Giả lập `gpsSpeed = 68` (> 60) -> Xác nhận viền đỏ ngoài cùng nhấp nháy cảnh báo.
   - Giả lập sau 3-5 giây không còn cảnh báo -> Biển báo tự động tắt và quay lại trạng thái bình thường.
