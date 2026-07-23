# Kế hoạch triển khai TYMAP

## Giai đoạn 0: Sửa lỗi hiện tại
1. Sửa Polyline (MapFragment.kt)
2. Sửa GPS & Marker (GpsManager.kt, MapFragment.kt)
3. Sửa tile vệ tinh (MapFragment.kt)
4. Cải thiện layout Settings (SettingsFragment.kt, fragment_settings.xml)
5. Thêm chế độ xe máy/ô tô (SettingsFragment.kt, RoutingEngine.kt)

## Giai đoạn 1: Giải pháp D (Tối ưu FPS & Nén ảnh)
1. Cập nhật Settings (spinner FPS, logic chất lượng JPEG)
2. Cập nhật NavigationService/MapRenderer (tự động giảm chất lượng khi FPS > 2, bật FrameSkipping)

## Giai đoạn 2: Giải pháp E (Ảnh chỉ đường - Roads Only)
1. **Android:** Tạo class `RoadsOnlyMapRenderer`:
   - Sử dụng `MapView` ẩn với custom `TileSource` hoặc `MapSnapshot`.
   - Áp dụng style: nền trong suốt, đường trắng/xám (tất cả loại), polyline xanh, marker xanh.
2. **Settings:** Thêm lựa chọn "Ảnh chỉ đường (Roads Only)" vào `spinnerMapCaptureMode`, thay thế Vector.
3. **NavigationService:** Khi chọn chế độ này, gọi `RoadsOnlyMapRenderer` để tạo ảnh JPEG và gửi qua `CHA_MAP_IMAGE`.
4. **ESP32‑S3:** Không cần thay đổi firmware (vẫn nhận JPEG như cũ).
5. **ESP32‑C3:** Không cần thay đổi firmware (vẫn nhận JPEG và chuyển đổi 1-bit).

## Giai đoạn 2.5: Tích hợp "Intelligent Chaser Engine" (ICE)
1. **Refactor `NavigationService`:**
   - Tạo `ChaserEngine` class chịu trách nhiệm giám sát và đồng bộ lộ trình.
   - Giảm ngưỡng `offRouteThreshold` xuống 15m và thời gian cooldown xuống 2 giây.
2. **Nâng cấp `RoutingEngine`:**
   - Thêm logic để ưu tiên gọi OSRM (tốc độ cao) cho cơ chế "bám đuôi".
3. **Tích hợp Popup:**
   - Trong `MapRenderer` / `NavigationService`, thêm trigger popup dựa trên `distToNext` từ lộ trình đã đồng bộ.
   - Render popup bằng chính `MapRenderer` hiện tại (hoặc `RoadsOnlyMapRenderer`).

## Giai đoạn 3: Giải pháp C (Tile Streaming) + Sửa lỗi còn lại
1. **Settings:** Thêm toggle "Tile Streaming".
2. **Android:** TileStreamingManager, gửi tile qua `CHA_MAP_TILE`, `CHA_MAP_CTRL`.
3. **ESP32‑S3:** Cache tile trong PSRAM (tối đa 100 ô), xoay ảnh theo bearing, vẽ polyline đè lên.

## Giai đoạn 4: Offline Maps
1. **Android:** Tạo `OfflineMapActivity`, `OfflineMapTileSource`, quản lý vùng tải.
2. **MapRenderer:** Ưu tiên dùng tile offline nếu có, nếu không dùng online.
