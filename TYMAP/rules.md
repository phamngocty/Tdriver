# RULES — Hệ thống dẫn đường xe máy thông minh TYMAP

## 1. QUY TẮC CHUNG (GENERAL RULES)
- **GR‑1**: Chỉ sử dụng API và dịch vụ miễn phí.
- **GR‑2**: Code phải viết bằng Kotlin (Android) và C++ (ESP32), có comment đầy đủ.
- **GR‑3**: **Giữ nguyên code hiện có đang chạy ổn định**, chỉ thêm hoặc sửa theo mô tả.
- **GR‑4**: Toàn bộ hằng số cấu hình phải lưu trong SharedPreferences hoặc file cấu hình, không hard‑code.

## 2. QUY TẮC HIỂN THỊ BẢN ĐỒ (MAP DISPLAY)
- **MAP‑1**: ESP32‑S3 (có PSRAM) hỗ trợ JPEG đầy đủ, Roads Only (JPEG nhẹ), và Tile Streaming.
- **MAP‑2**: ESP32‑C3 (không PSRAM) hỗ trợ JPEG đầy đủ, Roads Only (JPEG nhẹ), và ảnh OLED 1‑bit.
- **MAP‑3**: Polyline phải được đơn giản hóa (Douglas‑Peucker) trước khi vẽ hoặc gửi.
- **MAP‑4**: Marker vị trí xe phải cập nhật bằng `setPosition()` và `setRotation()`, không xóa/thêm mới.
- **MAP‑5**: Ảnh "Roads Only" phải có nền trong suốt (để ESP32 hiển thị nền đen), chứa tất cả loại đường, polyline xanh dương, marker xanh lá.

## 3. QUY TẮC BLE
- **BLE‑1**: UUID các characteristic không được thay đổi nếu không có lý do chính đáng.
- **BLE‑2**: Ảnh JPEG (đầy đủ hoặc Roads Only) gửi qua `CHA_MAP_IMAGE`.
- **BLE‑3**: Khi gửi Tile Streaming, sử dụng `CHA_MAP_TILE` và `CHA_MAP_CTRL`.

## 4. QUY TẮC ANDROID
- **APP‑1**: Phải có 4 tab: Connection, Map, Settings, Notifications.
- **APP‑2**: Mọi tác vụ nặng (parse polyline, off‑route) phải chạy trên background thread.
