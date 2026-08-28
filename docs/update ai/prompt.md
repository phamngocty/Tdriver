1. Phân hệ Dịch vụ NAS (Backend Services)
Khai tử Overpass API: Xóa bỏ hoàn toàn container này để giải phóng 1.5GB RAM cho hệ thống.

Triển khai Fusion Engine (Node.js): Xây dựng trạm trung chuyển (Middleware) siêu nhẹ thay thế Overpass. Nhiệm vụ: Đọc và gộp 2 file osm_cameras.csv (dữ liệu trích xuất từ OSM) và camera_vn.csv (dữ liệu ngầm tự sưu tầm), lọc trùng lặp không gian và nén thành JSON.

GraphHopper (Đã có): Giữ nguyên làm lõi định tuyến chính. Đọc vietnam-latest.osm.pbf kết hợp custom profile để né cao tốc, ưu tiên xe máy.

Nominatim (Cần fix lỗi): Đảm nhiệm ghim điểm (Reverse Geocoding). Khắc phục lỗi cấu hình mạng để App Android có thể kết nối nội bộ qua Tailscale/LAN.

Photon (Triển khai mới): Xử lý gợi ý tìm kiếm (Autocomplete) tốc độ cao, hỗ trợ gõ tiếng Việt không dấu.



1. Phân hệ Ứng dụng Android (Logic & Xử lý Bất đồng bộ)
Thiết kế Bất đồng bộ (Async Flow):

Ưu tiên 1: Gọi GraphHopper vẽ Polyline tuyến đường lên bản đồ ngay lập tức (không chờ đợi).

Ưu tiên 2 (Chạy nền): Gửi chuỗi Polyline đó lên NAS (Fusion Engine) để lọc chính xác các điểm camera/tốc độ nằm dọc theo lộ trình.

Ưu tiên 3 (Chạy nền): Gọi API Open-Meteo lấy thông tin thời tiết tại điểm đến.

Khắc phục lỗi Nominatim: Thêm cấu hình android:usesCleartextTraffic="true" vào Manifest và bổ sung header User-Agent vào HTTP Request để NAS không từ chối kết nối.

Tính toán Ngoại tuyến (Haversine): Lưu mảng cảnh báo từ Fusion Engine vào RAM điện thoại. Dùng tín hiệu GPS đo khoảng cách liên tục khi di chuyển (không phụ thuộc 4G) để kích hoạt cảnh báo.

3. Phân hệ Ứng dụng Android (Trải nghiệm Tìm kiếm - UX/UI)
Tối ưu Giao diện Photon: Thay đổi placeholder thành "Nhập tên đường, ngã tư hoặc khu vực...". Xóa bỏ các text rác "[Photon Autocomplete]" trong danh sách hiển thị. Hiển thị nút "Ghim vị trí trực tiếp" khi mảng kết quả rỗng.

Cơ sở dữ liệu Cá nhân (SQLite/Room): Tích hợp database cục bộ để lưu các địa điểm quen thuộc (Nhà riêng, Công ty, Yêu thích) với tốc độ truy xuất tức thì (Bỏ qua tính năng đóng góp cộng đồng).

Trộn luồng Tìm kiếm (Merged Search): Khi nhập từ khóa, gộp chung và ưu tiên hiển thị kết quả từ SQLite lên đầu danh sách, kết quả từ Photon nằm ngay bên dưới.

4. Phân hệ Giao tiếp Phần cứng (ESP32 & BLE)
Giao thức Gói tin nhị phân (Hex): Android đóng gói dữ liệu cảnh báo thành chuỗi Hex siêu nhỏ gọn (Ví dụ: [0x0B, 0x3C] báo camera 60km/h).

Xử lý Ngắt (Interrupt): Mạch ESP32 nhận gói tin BLE và sử dụng hàm ngắt để kích hoạt giao diện cảnh báo nhấp nháy đỏ ngay lập tức trên màn hình GC9A01 mà không bị trễ.

Đây là danh sách  port đang chạy trên nas.  Tránh trùng port
**Kaneo + Postgres**
• Status: Healthy
• Port: 5173, 5432

**Immich + ML + Postgres + Redis**
• Status: Healthy
• Port: 2283

**Karakeep + Meilisearch + Chrome**
• Status: Healthy
• Port: 14592

**Gitea (old)**
• Status: Up
• Port: 3002, 222

**Filebrowser**
• Status: Healthy
• Port: 8080

**AdGuard Home**
• Status: Up (host mode)
• Port: -

**DuckDNS**
• Status: Up
• Port: -

**GraphHopper (routing)**
• Status: Healthy
• Port: 8989

**Nominatim (geocoding)**
• Status: Up
• Port: 8081

**Tailscale**
• Status: Up
• Port: -

Đây là báo cáo từ nasbot