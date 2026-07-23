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
- **Tùy chọn Chế độ gửi ảnh (Map Capture Mode):**
  - Chế độ 0: Bản đồ OSM tĩnh (Continuous)
  - Chế độ 1: Chụp Google Maps (Liên tục)
  - Chế độ 2: Chụp Google Maps (Popup ngã rẽ)
  - Chế độ 3: Bản đồ OSM cuốn chiếu (Tile Streaming)
  - Chế độ 4: Ảnh chỉ đường (Roads Only)
  - Chế độ 5: Google Maps Popup OSM (Bản đồ OSM Popup ngã rẽ - bình thường ở màn hình HUD, tới ngã rẽ 500m/200m tự bật Popup OSM, qua ngã rẽ >50m quay lại HUD).
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

### 2.6. Giải pháp "Intelligent Chaser Engine" (ICE) (Ưu tiên 2.5)
- **Tổng quan:** ICE là cơ chế đồng bộ hóa lộ trình thông minh, cho phép OSM của TYMAP "bắt chước" lộ trình của Google Maps trong thời gian thực dựa trên định vị GPS mà không truy cập dữ liệu nội bộ của Google Maps.
- **Nguyên lý "Kẻ Bám Đuôi Thông Minh":**
  1. **Khởi tạo:** Khi người dùng bắt đầu dẫn đường (bởi Google Maps hoặc TYMAP), TYMAP sử dụng OSRM để tạo lộ trình dự kiến.
  2. **Giám sát:** Ứng dụng liên tục so sánh vị trí GPS thực tế với lộ trình OSRM hiện tại.
  3. **Phát hiện & Thích nghi:** Phát hiện ngay lập tức sự kiện "lệch tuyến" khi người dùng di chuyển lệch quá 15m.
  4. **Đồng bộ hóa thông minh:** Tự động gọi lại OSRM với vị trí GPS hiện tại làm điểm xuất phát mới trong vòng <= 2 giây, giúp lộ trình mới tự điều chỉnh bám theo đường người dùng đang di chuyển.
  5. **Popup OSM Thông minh:** Khi khoảng cách tới ngã rẽ đạt ngưỡng 500m hoặc 200m, ứng dụng render ảnh bản đồ ngã rẽ và gửi hiển thị popup lên ESP32. Sau khi qua ngã rẽ (> 50m) hoặc hết thời gian `popupDuration`, ESP32 tự quay lại chế độ HUD.
- **Yêu cầu chức năng:**
  - **FR-ICE-01:** Cơ chế phát hiện lệch tuyến có độ trễ tối đa 2 giây và ngưỡng kích hoạt 15 mét.
  - **FR-ICE-02:** Tự động gọi lại OSRM trong vòng 1 giây khi lệch tuyến.
  - **FR-ICE-03:** Ảnh popup OSM render bằng chế độ bản đồ hiện tại (OSM đầy đủ, Roads Only, hoặc Vector).
  - **FR-ICE-04:** Popup tự động ẩn và quay về HUD sau khi qua ngã rẽ (> 50m) hoặc sau `popupDuration` giây.

## 3. Yêu cầu phi chức năng
- ESP32‑S3 hỗ trợ JPEG, Roads Only, Tile Streaming.
- ESP32‑C3 hỗ trợ JPEG (chuyển đổi 1-bit), Roads Only.
- Tối ưu băng thông BLE, không làm giật UI Android.
