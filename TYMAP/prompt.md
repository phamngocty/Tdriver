Bạn là chuyên gia phát triển Android (Kotlin) và ESP32 (C++/Arduino). Hãy thực hiện các công việc sau cho dự án TYMAP theo đúng thứ tự ưu tiên. TUÂN THỦ nghiêm ngặt file rules.md.

## Công việc 0: Sửa lỗi hiện tại
1. **Sửa Polyline:** Đơn giản hóa Douglas‑Peucker, xóa polyline cũ, bật anti‑alias, gộp invalidate.
2. **Sửa GPS & Marker:** Off‑route detection trên `Dispatchers.Default`, cập nhật marker bằng `setPosition()`/`setRotation()`, dùng `animateTo()`, xin quyền `ACCESS_BACKGROUND_LOCATION`.
3. **Sửa Tile vệ tinh:** URL ESRI `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`, fallback CartoDB Positron.
4. **Layout Settings:** Nhóm vào `MaterialCardView`, thêm `ChipGroup` lọc nhanh.
5. **Chế độ xe máy/ô tô:** Thêm Spinner "Phương tiện", điều chỉnh profile routing engine.

## Công việc 1: Giải pháp D (Tối ưu FPS & Nén ảnh)
1. **Settings:** Spinner `spinnerMapFps` có 1, 2, 5, 10 FPS.
2. **NavigationService:** Nếu FPS > 2, tự động giảm `jpegQuality` xuống 40%. Nếu FPS ≤ 2, giữ nguyên cài đặt.
3. **FrameSkipping:** Kích hoạt khi FPS ≥ 5.

## Công việc 2: Giải pháp E (Ảnh chỉ đường - Roads Only)
1. **Android:** Tạo class `RoadsOnlyMapRenderer` (trong `service/`).
   - Sử dụng `MapView` ẩn (headless) 240×240 với custom `TileSource` có nền trong suốt.
   - HOẶC sử dụng `MapSnapshot` với `MapRenderer` tùy chỉnh, vẽ các đường đi từ OSM data.
   - Áp dụng style:
     - Nền: Trong suốt (alpha=0).
     - Đường đi (highway): Tất cả loại (motorway, trunk, primary, secondary, tertiary, residential, service...), màu trắng/xám.
     - Polyline tuyến đường: Màu xanh dương (#0066FF), dày 4px.
     - Marker vị trí xe: Chấm xanh (#00FF00), bán kính 6px.
     - Bỏ qua: Địa hình, nước, công viên, nhà cửa, label.
   - Render ra `Bitmap` 240×240 (nền trong suốt sẽ thành đen khi nén JPEG).
   - Nén JPEG chất lượng 60-70%, mục tiêu 1-3 KB.
2. **Settings:** Thêm lựa chọn "Ảnh chỉ đường (Roads Only)" vào `spinnerMapCaptureMode`, thay thế hoàn toàn chế độ Vector.
3. **NavigationService:** Khi chọn "Roads Only", gọi `RoadsOnlyMapRenderer` thay vì `MapRenderer` thông thường. Gửi ảnh qua `CHA_MAP_IMAGE` như cũ.
4. **ESP32‑S3 & C3:** KHÔNG cần thay đổi firmware. ESP32 nhận JPEG như bình thường, giải mã và hiển thị.

## Công việc 3: Giải pháp C (Tile Streaming) + Sửa lỗi còn lại
1. **Settings:** Thêm toggle "Tile Streaming".
2. **Android:** TileStreamingManager, gửi tile qua `CHA_MAP_TILE` (UUID: d1e2f3a4-...), `CHA_MAP_CTRL` (UUID: e2f3a4b5-...).
3. **ESP32‑S3:** Cache tile trong PSRAM (tối đa 100 ô), xoay ảnh theo bearing, vẽ polyline đè lên.

## Công việc 4: Offline Maps
1. **Android:** Tạo `OfflineMapActivity`, `OfflineMapTileSource`, quản lý vùng tải.
2. **MapRenderer:** Ưu tiên dùng tile offline nếu có, nếu không dùng online.

## Lưu ý chung
- GIỮ NGUYÊN code đang chạy ổn định, chỉ thêm/sửa có kiểm soát.
- Đảm bảo biên dịch thành công cho cả Android và PlatformIO.
