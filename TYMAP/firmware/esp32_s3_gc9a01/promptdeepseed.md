

Tôi sẽ tạo bộ **Spec Kit** cho **"Intelligent Chaser Engine" (ICE)** – trái tim của tính năng mới, được tích hợp hoàn hảo vào kế hoạch tổng thể đã có.

---

## Bộ Spec Kit: "Intelligent Chaser Engine" (ICE)

### 1. `spec.md` — Đặc tả kỹ thuật (Cập nhật cuối cùng)

```markdown
# Spec: Hệ thống Dẫn đường & Hiển thị Thông minh TYMAP

## 1. Giải pháp "Intelligent Chaser Engine" (ICE) - Ưu tiên 2.5

### 1.1. Tổng quan
ICE là một cơ chế đồng bộ hóa lộ trình thông minh, cho phép OSM của TYMAP "bắt chước" lộ trình của Google Maps trong thời gian thực mà không cần truy cập vào dữ liệu nội bộ của Google Maps.

### 1.2. Nguyên lý "Kẻ Bám Đuôi Thông Minh"
1.  **Khởi tạo:** Khi người dùng bắt đầu dẫn đường (bằng Google Maps hoặc TYMAP), TYMAP sẽ sử dụng **OSRM để tạo một lộ trình dự kiến** đến điểm đích.
2.  **Giám sát:** App liên tục so sánh vị trí GPS thực tế của người dùng với lộ trình OSRM đã tạo.
3.  **Phát hiện & Thích nghi:** Khi người dùng đi lệch khỏi lộ trình OSRM (ví dụ: do làm theo chỉ dẫn khác của Google Maps), hệ thống sẽ **ngay lập tức** phát hiện sự kiện "lệch tuyến".
4.  **Đồng bộ hóa thông minh:** Thay vì cố gắng đọc polyline từ Google Maps, TYMAP sẽ **gọi lại OSRM** với vị trí GPS hiện tại làm điểm xuất phát. Lộ trình mới được tạo ra sẽ "bám đuổi" và trở nên gần như trùng khớp với lộ trình của Google Maps.
5.  **Popup OSM Thông minh:** Khi khoảng cách đến ngã rẽ tiếp theo (trên lộ trình OSRM đã đồng bộ) đạt ngưỡng 500m hoặc 200m (cấu hình trong Settings), app sẽ render ảnh bản đồ OSM (từ `RoadsOnlyMapRenderer` hoặc chế độ `MapRenderer` hiện tại) và hiển thị popup lên ESP32. Sau khi qua ngã rẽ, ESP32 quay lại chế độ HUD.

### 1.3. Yêu cầu chức năng
- **FR-ICE-01:** Cơ chế phát hiện lệch tuyến phải có độ trễ tối đa 2 giây và ngưỡng kích hoạt là 15 mét.
- **FR-ICE-02:** Khi phát hiện lệch tuyến, phải tự động gọi lại OSRM trong vòng 1 giây.
- **FR-ICE-03:** Ảnh popup OSM phải được render bằng chế độ hiển thị bản đồ hiện tại (OSM đầy đủ, Roads Only, hoặc Vector).
- **FR-ICE-04:** Popup phải tự động biến mất và quay về HUD sau khi qua ngã rẽ (khoảng cách > 50m) hoặc sau `popupDuration` giây.
```

### 2. `plan.md` — Kế hoạch hành động (Cập nhật cuối cùng)

```markdown
# Kế hoạch Triển khai TYMAP (Cập nhật)

## Giai đoạn 2.5: Tích hợp "Intelligent Chaser Engine" (ICE)
1.  **Refactor `NavigationService`:**
    -   Tạo `ChaserEngine` class chịu trách nhiệm giám sát và đồng bộ lộ trình.
    -   Giảm ngưỡng `offRouteThreshold` xuống 15m và thời gian cooldown xuống 2 giây.
2.  **Nâng cấp `RoutingEngine`:**
    -   Thêm logic để ưu tiên gọi OSRM (tốc độ cao) cho cơ chế "bám đuôi".
3.  **Tích hợp Popup:**
    -   Trong `MapRenderer`, thêm trigger popup dựa trên `distToNext` từ lộ trình đã đồng bộ.
    -   Render popup bằng chính `MapRenderer` hiện tại (hoặc `RoadsOnlyMapRenderer`).
```

### 3. `prompt.md` — Prompt cho AI Agent

```text
Bạn là chuyên gia phát triển Android. Hãy triển khai **Giải pháp "Intelligent Chaser Engine" (ICE)** cho dự án TYMAP. Đây là một cơ chế đồng bộ lộ trình thông minh, không cần can thiệp vào Google Maps.

## YÊU CẦU CHI TIẾT
1.  **Tạo `ChaserEngine.kt`** trong `service/`:
    -   Class này nhận GPS location và lộ trình hiện tại từ `NavigationRepository`.
    -   Logic: Nếu khoảng cách từ GPS đến polyline > 15m, kích hoạt sự kiện `onRouteDeviation`.
    -   `NavigationService` sẽ lắng nghe sự kiện này và gọi lại `RoutingEngine` với vị trí GPS hiện tại.

2.  **Sửa đổi `NavigationService.kt` và `RoutingEngine.kt`**:
    -   Cho phép gọi lại OSRM nhanh chóng khi có sự kiện từ `ChaserEngine`.
    -   Đảm bảo thời gian phản hồi (từ lúc phát hiện lệch đến lúc có lộ trình mới) dưới 2 giây.

3.  **Tích hợp Popup OSM**:
    -   Sử dụng chính cơ chế Popup HUD hiện tại.
    -   Khi `distToNext` (từ lộ trình đã đồng bộ) đạt 500m hoặc 200m, kích hoạt chụp/gửi ảnh map (sử dụng `MapRenderer` hoặc `RoadsOnlyMapRenderer`).
    -   ESP32 không cần thay đổi gì.

## LƯU Ý QUAN TRỌNG
-   **KHÔNG ĐỌC DỮ LIỆU TỪ GOOGLE MAPS.** Mọi thứ phải dựa trên GPS và OSRM.
-   Giữ nguyên code hiện có, chỉ thêm mới.
```

### 4. `rules.md` — Quy tắc dự án (Cập nhật cuối cùng)

```markdown
# RULES — Hệ thống dẫn đường xe máy thông minh TYMAP

## 5. QUY TẮC DẪN ĐƯỜNG & ĐỒNG BỘ (ICE RULES)
- **ICE‑1**: Tuyệt đối không truy cập vào tiến trình hoặc dữ liệu nội bộ của ứng dụng Google Maps.
- **ICE‑2**: Mọi đồng bộ lộ trình phải được thực hiện gián tiếp thông qua GPS và OSRM.
- **ICE‑3**: Ngưỡng phát hiện lệch tuyến cho ICE là 15 mét, thời gian chờ (debounce) là 2 giây.
- **ICE‑4**: Khi kích hoạt popup ngã rẽ, ảnh phải được render từ OSM engine của chúng ta, phản ánh chính xác lộ trình đã đồng bộ.
```

