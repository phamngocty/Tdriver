# Spec: Hệ thống dẫn đường xe máy thông minh TYMAP

## 1. Tổng quan
TYMAP là hệ thống dẫn đường xe máy gồm:
- **App Android** (Kotlin) xử lý bản đồ, định tuyến, gửi dữ liệu qua BLE.
- **Firmware ESP32‑S3** (GC9A01 240×240) hiển thị HUD, ảnh bản đồ JPEG, Tile Streaming, Ảnh chỉ đường (Roads Only).
- **Firmware ESP32‑C3** (OLED 128×64) hiển thị HUD, ảnh đơn sắc, Ảnh chỉ đường (Roads Only) chuyển đổi sang 1-bit.

## 2. Yêu cầu chức năng

### 2.1. Sửa lỗi hiện tại (Ưu tiên 0)
1. **Polyline:** Đơn giản hóa bằng Douglas‑Peucker, xóa polyline cũ trước khi vẽ mới, bật anti‑alias, gộp `invalidate()`.
2. **GPS & Marker:** Off‑route detection trên `Dispatchers.Default`, cập nhật marker bằng `setPosition()`/`setRotation()`, dùng `ValueAnimator` nội suy góc xoay, dùng `animateTo()` cho camera, xin quyền `ACCESS_BACKGROUND_LOCATION`.
3. **Tile vệ tinh:** Cập nhật URL ESRI Satellite, thêm fallback về CartoDB Positron.
4. **Layout Settings:** Nhóm các cài đặt vào `MaterialCardView`, thêm `ChipGroup` lọc nhanh (Chung, Bản đồ, Định tuyến, Giọng nói, Dữ liệu).
5. **Chế độ xe máy/ô tô:** Thêm Spinner "Phương tiện", điều chỉnh profile routing engine (GraphHopper `motorcycle`, ORS `avoid_features=motorway`).

### 2.2. Giải pháp D: Tối ưu FPS & Nén ảnh (Ưu tiên 1)
- Cho phép chọn FPS 1, 2, 5, 10.
- Khi FPS > 2, tự động giảm chất lượng JPEG xuống 40‑50%.
- Kích hoạt `FrameSkipping` (CRC32) khi FPS ≥ 5.

### 2.3. Giải pháp E: Ảnh bản đồ chỉ đường (Roads Only) (Ưu tiên 2, thay thế Vector)
**Mục tiêu:** Tạo ảnh bản đồ nhẹ chỉ chứa đường đi và polyline, gửi qua BLE cho ESP32 hiển thị.
- **App Android:** Render bản đồ OSM với style tùy chỉnh:
  - **Nền:** Trong suốt (để ESP32 hiển thị nền đen tự nhiên).
  - **Roads (highway):** Tất cả các loại đường (từ đường lớn đến hẻm nhỏ), màu trắng/xám, độ dày tùy thuộc loại đường.
  - **Polyline tuyến đường:** Màu xanh dương (#0066FF), dày 4px.
  - **Marker vị trí xe:** Chấm xanh (#00FF00), bán kính 6px.
  - **Bỏ qua:** Địa hình, nước, công viên, nhà cửa, label.
- **Định dạng:** Ảnh JPEG 240×240, chất lượng 60-70%, dung lượng ước tính 1-3 KB.
- **Tùy chọn:** Thêm "Ảnh chỉ đường (Roads Only)" vào `spinnerMapCaptureMode` trong Settings, thay thế hoàn toàn chế độ Vector.
- **Tương thích:** ESP32-S3 hiển thị ảnh JPEG, ESP32-C3 chuyển đổi sang 1-bit nếu cần.

### 2.4. Giải pháp C: Tile Streaming (Ưu tiên 3, chỉ ESP32‑S3)
- Gửi trước các ô ảnh 240×240 vào PSRAM (tối đa 100 ô).
- ESP32 hiển thị ô phù hợp với GPS, vẽ polyline và marker đè lên.
- Xoay ảnh bằng phần mềm dựa trên bearing.
- Khi zoom thay đổi, xóa cache và tải lại.
- Thêm toggle "Tile Streaming" trong Settings.

### 2.5. Offline Maps (Ưu tiên 4)
- Tải tile OSM về bộ nhớ trong, chọn khu vực bằng khung chữ nhật, chọn nhiều mức zoom.
- Xem trước ảnh 240×240, hiển thị dung lượng ước tính, tiến trình tải.
- Quản lý vùng tải trong `OfflineMapActivity`.
- Tự động dùng offline nếu có tile phù hợp, có tùy chọn "Ưu tiên offline".

## 3. Yêu cầu phi chức năng
- ESP32‑S3 hỗ trợ JPEG, Roads Only, Tile Streaming.
- ESP32‑C3 hỗ trợ JPEG (chuyển đổi 1-bit), Roads Only.
- Tối ưu băng thông BLE, không làm giật UI Android.
