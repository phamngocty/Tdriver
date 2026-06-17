https://github.com/cartodb/basemap-styles  tôi tháy mấy map cartdb rồi hay thêm lại 
Dưới đây là **phân tích chi tiết toàn bộ tính năng của Google Maps** mà bạn muốn mô phỏng trong Tab Map của app. Tôi đã liệt kê, đặt tên và mô tả từng thành phần một cách rõ ràng. Bạn có thể sao chép nội dung này và nạp vào AI agent để yêu cầu xây dựng MapFragment.

---

# PHÂN TÍCH CHI TIẾT: TÁI TẠO GIAO DIỆN & CHỨC NĂNG GOOGLE MAPS CHO TAB MAP

Bạn là một chuyên gia phát triển Android. Hãy xây dựng MapFragment với giao diện và hành vi giống hệt Google Maps, sử dụng OSMdroid làm nền tảng bản đồ. Dưới đây là danh sách đầy đủ các tính năng cần có.

## 1. BẢN ĐỒ NỀN (MAP VIEW)
- **MapView** từ OSMdroid, hỗ trợ đầy đủ cử chỉ: kéo (pan), pinch-to-zoom, xoay (rotate), nghiêng (tilt) nếu có.
- **Tile source** mặc định: CartoDB Positron. Có thể thay đổi qua nút Layers hoặc Settings, gồm:
  - CartoDB Positron (sáng)
  - OSM Mapnik (sáng, cổ điển)
  - CartoDB Dark Matter (tối, dùng cho chế độ tối)
  - Custom (MapCN) – yêu cầu nhập URL tile template chứa `{z}`, `{x}`, `{y}`.
- **La bàn (Compass)**: xuất hiện ở góc trên bên phải khi bản đồ bị xoay (rotation != 0). Nhấn vào la bàn sẽ xoay bản đồ về hướng Bắc (North up).

## 2. CÁC NÚT NỔI TRÊN BẢN ĐỒ
Tất cả nút hình tròn, nền trắng (hoặc xám đậm ở chế độ tối), có bóng đổ, sắp xếp theo chiều dọc ở bên phải màn hình.

### 2.1. Nút "Vị trí của tôi" (My Location)
- Vị trí: phía trên bên phải, dưới thanh tìm kiếm.
- Biểu tượng: hình bia ngắm (crosshair).
- Hành vi:
  - Khi chưa ở chế độ theo dõi (follow mode): nhấn sẽ di chuyển camera đến vị trí GPS hiện tại, không xoay.
  - Khi đang ở chế độ theo dõi (navigation): nhấn để chuyển đổi giữa hai chế độ:
    - **Theo dõi hướng di chuyển** (Track up): camera xoay theo hướng di chuyển (bearing), nút đổi màu xanh dương.
    - **Bắc luôn lên trên** (North up): camera không xoay, nút trở về màu trắng/xám.

### 2.2. Nút "Lớp bản đồ" (Layers)
- Vị trí: ngay dưới nút My Location.
- Biểu tượng: hình các lớp xếp chồng (layers) hoặc hình thoi.
- Hành vi: mỗi lần nhấn sẽ chuyển đổi tile source theo thứ tự:
  - CartoDB Positron → OSM Mapnik → CartoDB Dark Matter → Custom (nếu đã cấu hình URL).
  - Hiển thị tên tile source ngắn dưới dạng Toast hoặc label nhỏ.

### 2.3. Nút Zoom (Zoom + và Zoom -)
- Vị trí: góc dưới bên phải, xếp dọc.
- Biểu tượng: dấu cộng (+) và dấu trừ (-).
- Hành vi: tăng/giảm mức zoom lên 1 đơn vị. Nếu đạt zoom tối thiểu/tối đa thì nút tương ứng bị vô hiệu hóa (màu xám).

### 2.4. Nút "Chia sẻ" (Share)
- Vị trí: gần thanh tìm kiếm (có thể thay thế biểu tượng trên thanh) hoặc trong bottom sheet địa điểm.
- Biểu tượng: mũi tên chia sẻ.
- Hành vi: chỉ hiển thị khi một địa điểm đang được chọn (có marker). Nhấn vào mở Android share sheet với đường dẫn geo-URL (ví dụ: `https://maps.google.com/maps?q=lat,lng`).

## 3. THANH TÌM KIẾM (SEARCH BAR)
- Giao diện: `CardView` nổi phía trên cùng, bo góc tròn, có bóng đổ. Placeholder: "Tìm kiếm tại đây".
- Khi người dùng nhập, gọi API Nominatim:  
  `https://nominatim.openstreetmap.org/search?q=<query>&format=json&limit=5`
- Hiển thị danh sách gợi ý ngay bên dưới thanh tìm kiếm (dạng dropdown), mỗi mục gồm tên địa điểm (in đậm) và địa chỉ phụ.
- Khi chọn một mục:
  - Đặt **marker đỏ** (hình giọt nước) tại vị trí.
  - Camera di chuyển đến đó, zoom vừa phải.
  - Hiển thị **bong bóng thông tin** (InfoWindow) nhỏ phía trên marker chứa tên địa điểm.
  - Hiển thị **Place Bottom Sheet**.

## 4. PLACE BOTTOM SHEET (BOTTOM SHEET ĐỊA ĐIỂM)
Xuất hiện sau khi chọn một địa điểm từ tìm kiếm hoặc nhận điểm đến từ chia sẻ.

- Trạng thái thu gọn (collapsed):
  - Tên địa điểm (in đậm, 18sp).
  - Địa chỉ (màu xám, nhỏ hơn).
  - Hai nút hành động: **"Chỉ đường"** (Directions, biểu tượng mũi tên xanh) và **"Bắt đầu"** (Start, nút nổi màu xanh lá/blue).
- Trạng thái mở rộng (expanded): có thể hiển thị thêm thông tin (danh mục, website nếu có từ Nominatim) hoặc chỉ là khoảng trống bổ sung.
- **"Chỉ đường"**: gọi API định tuyến, vẽ tuyến đường tối ưu (màu xanh đậm) và 1-2 tuyến thay thế (màu xanh nhạt/xám) lên bản đồ. Bottom sheet chuyển sang trạng thái "xem trước tuyến đường" với thời gian, khoảng cách, và nút "Bắt đầu".
- **"Bắt đầu"**: khởi động NavigationService với điểm đến, chuyển bottom sheet thành Navigation Bottom Sheet.

## 5. CHỌN TUYẾN ĐƯỜNG THAY THẾ
- Sau khi nhấn "Chỉ đường", nếu có nhiều tuyến, các tuyến thay thế được vẽ màu nhạt hơn và có thể chạm vào để chọn.
- Khi chạm vào một tuyến thay thế:
  - Tuyến đó trở thành tuyến chính (màu xanh đậm), các tuyến khác chuyển sang màu nhạt.
  - Thông tin chuyến đi (thời gian, khoảng cách) cập nhật tương ứng.
  - Bottom sheet phản ánh tuyến được chọn.

## 6. NAVIGATION BOTTOM SHEET (TRONG KHI DẪN ĐƯỜNG)
Khi dẫn đường đang hoạt động, bottom sheet chuyển sang chế độ chỉ dẫn.

- **Trạng thái thu gọn** (mặc định khi dẫn đường):
  - Bên trái: **biểu tượng rẽ** (mũi tên rẽ trái/phải/thẳng – lấy từ bộ icon nội bộ dựa trên maneuver index 0-20).
  - Dòng trên: "Rẽ trái vào Nguyễn Huệ" (hành động + tên đường).
  - Dòng dưới: "trong 200 m" (khoảng cách đến chỗ rẽ).
  - Bên phải: nút tắt tiếng (loa) và nút đóng (X).
- **Kéo lên để mở rộng**: hiển thị đầy đủ thông tin chuyến đi:
  - ETA (thời gian đến dự kiến).
  - Thời gian còn lại.
  - Tổng khoảng cách.
  - Nút **"Kết thúc"** (End) màu đỏ, nhấn để dừng dẫn đường.
- **Tự động thu nhỏ khi pan bản đồ**: Khi người dùng kéo bản đồ, sheet thu về trạng thái thu gọn. Một nút **"Gần đây"** (Recenter) xuất hiện ở góc dưới bên phải (trên nút zoom). Nhấn vào đó sẽ đưa camera về chế độ theo dõi (follow) và mở rộng sheet lại.
- **Khi đến nơi**: sheet hiển thị "Đã đến nơi" và nút "Thoát".

## 7. ĐÁNH DẤU VỊ TRÍ XE TRÊN BẢN ĐỒ (VEHICLE MARKER)
- Trong khi dẫn đường, vị trí xe được biểu thị bằng một **mũi tên màu xanh dương** (hoặc vòng tròn xanh có mũi tên chỉ hướng).
- Marker này **xoay theo góc bearing** (hướng di chuyển) lấy từ GPS.
- Camera mặc định theo dõi xe và xoay theo hướng di chuyển (nếu đang ở chế độ Track up).

## 8. CẢNH BÁO TỐC ĐỘ (SPEED WARNING)
- Nếu tốc độ GPS vượt quá ngưỡng cài đặt (mặc định 60 km/h):
  - Hiển thị biểu tượng **đồng hồ tốc độ màu đỏ** ở góc dưới bên trái bản đồ.
  - Rung điện thoại (nếu có quyền rung).
  - Có thể phát cảnh báo giọng nói ("Bạn đang chạy quá tốc độ") nếu bật trong Settings.

## 9. CHẾ ĐỘ BAN ĐÊM (NIGHT MODE)
- Dựa vào theme đang chọn trong Settings (Sáng/Tối/Tự động hệ thống).
- Khi ở chế độ tối, tile source chuyển sang CartoDB Dark Matter (hoặc dark style tương tự).
- Tất cả nút bấm, bottom sheet, thanh tìm kiếm chuyển sang tông màu tối (nền xám đen, chữ trắng).

## 10. NHẬN ĐIỂM ĐẾN TỪ CHIA SẺ (SHARE HANDLING)
- Activity `ShareReceiverActivity` nhận Intent `ACTION_SEND` với MIME `text/plain` từ Google Maps.
- Parse URL (dạng dài hoặc rút gọn goo.gl) để lấy tọa độ đích.
- Truyền tọa độ đó sang MapFragment, MapFragment sẽ:
  - Đặt marker đỏ tại điểm đến.
  - Di chuyển camera đến đó.
  - Hiển thị Place Bottom Sheet.

## 11. TƯƠNG TÁC VỚI BẢN ĐỒ KHI KHÔNG DẪN ĐƯỜNG
- Kéo bản đồ tự do.
- Nhấn giữ (long press) có thể không cần chức năng gì, nhưng có thể thêm vào sau.
- Chỉ có thanh tìm kiếm và các nút nổi là các phần tử tương tác chính.

## 12. YÊU CẦU KỸ THUẬT BỔ SUNG
- Sử dụng ViewBinding cho layout.
- Sử dụng `BottomSheetBehavior` từ Material Components cho các bottom sheet.
- Các marker, polyline được quản lý qua OSMdroid overlays.
- Tuân thủ Material Design, hỗ trợ edge-to-edge (safe insets).
- Xử lý các sự kiện vòng đời Fragment đúng cách để tránh rò rỉ bộ nhớ.

Hãy viết code Kotlin hoàn chỉnh cho MapFragment và các layout XML tương ứng, đáp ứng tất cả các tính năng trên.
```

