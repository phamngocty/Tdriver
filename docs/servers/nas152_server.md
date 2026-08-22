# 🖥️ HƯỚNG DẪN & CẤU TRÚC SERVER NAS (192.168.1.114)

## 🔑 Thông tin SSH Kết nối
- **Command:** `ssh nas152@192.168.1.114`
- **Password:** `271000`
- **Thư mục dự án chính:** `/home/nas152/graphhopper-data`

---

## 🌐 Danh sách URL Services & Endpoints trên NAS

| Dịch vụ | URL Endpoint | Mô tả & Chức năng |
| :--- | :--- | :--- |
| **Dashboard Quản lý Chính** | `http://192.168.1.114:8085/` | Giao diện điều khiển trung tâm: Phản hồi siêu tốc Fast-Track (15-30ms), Giả lập chạy thử lộ trình (Simulation HUD), Bảng chi tiết bước rẽ (Turn-by-turn Drawer), Dự báo mưa ETA, Chọn Profile xe máy VN. Chi tiết xem tại [Tài liệu Đặc tả](file:///d:/Documents/PlatformIO/Tdriver/docs/features/dashboard_improvements_and_android_specs.md) |
| **GraphHopper Routing API** | `http://192.168.1.114:8989/route` | API trả về JSON chỉ đường (Turn-by-turn) cho App Android / Client |
| **GraphHopper UI Gốc** | `http://192.168.1.114:8989/maps/` | Giao diện bản đồ gốc của GraphHopper Engine |
| **API Webhook Cập nhật PBF** | `http://192.168.1.114:8990/update_pbf` | Endpoint gửi lệnh trigger tự động tải PBF Việt Nam mới nhất (`wget https://download.geofabrik.de/asia/vietnam-latest.osm.pbf`) & rebuild |
| **File Browser NAS (Hệ thống)** | `http://192.168.1.114:8080/` | Dịch vụ quản lý file có sẵn của hệ thống NAS (Tránh dùng port này cho TYMAP) |
| **Photon Autocomplete (Dự kiến)** | `http://192.168.1.114:2322/` | API gợi ý từ khóa tìm kiếm địa chỉ |
| **Nominatim Geocoding (Dự kiến)** | `http://192.168.1.114:8081/` | API bóc tách & tìm kiếm tọa độ chính xác (**Đã đổi sang 8081**) |
| **Overpass API (Dự kiến)** | `http://192.168.1.114:8088/` | API truy vấn biển báo & camera phạt nguội |

---

## 📂 Cấu trúc Thư Mục Master Data & Mã Nguồn trên NAS

### 1. Dữ liệu Lõi dùng chung (Master Data - Plan 2)
Thư mục: `/home/nas152/tymap_data`
```text
/home/nas152/tymap_data/
 ├── osm_source/
 │    └── vietnam-latest.osm.pbf     <-- File gốc đọc chung (GraphHopper, Nominatim, Overpass)
 ├── graphhopper_cache/              <-- Cache ma trận đường đi của GraphHopper
 ├── nominatim_db/                   <-- Database PostgreSQL của Nominatim
 ├── overpass_db/                    <-- DB cảnh báo camera/tốc độ của Overpass
 └── tileserver_data/
      └── vietnam.mbtiles            <-- File mbtiles cho TileServer GL
```

### 2. Thư mục Cấu hình & Web Dashboard
Thư mục: `/home/nas152/graphhopper-data`

| File trên NAS | Chức năng | Hướng dẫn chỉnh sửa |
| :--- | :--- | :--- |
| `/home/nas152/graphhopper-data/docker-compose.yml` | **Quản lý toàn bộ Container TYMAP** | Chạy `docker-compose up -d` để khởi động cùng lúc GraphHopper (8989), Nominatim (8081), Overpass (8088). |
| `/home/nas152/graphhopper-data/index.html` | **Giao diện Dashboard chính (Port 8085)** | Sửa file này để tích hợp thẻ trạng thái (Health Check) & Test API cho các dịch vụ. |
| `/home/nas152/graphhopper-data/webhook_server.py` | **Service Webhook (Port 8990)** | Sửa file này nếu muốn thêm API xử lý backend ngầm. |
| `/home/nas152/graphhopper-data/update_pbf.sh` | **Script cập nhật dữ liệu PBF** | Chứa câu lệnh tự động tải PBF mới, verify MD5, xóa cache và restart Docker. |
| `/home/nas152/graphhopper-data/config-loc.yml` | **Cấu hình GraphHopper Engine** | Chứa cấu hình profile xe (`car`, `bike`, `scooter`...) và các thông số cài đặt. |

---

## 🛠️ Các lệnh quản lý & kiểm tra nhanh trên NAS

### 1. Khởi chạy / Quản lý toàn bộ Docker Stack (GraphHopper, Nominatim, Overpass)
```bash
# Di chuyển vào thư mục cấu hình
cd /home/nas152/graphhopper-data

# Khởi động toàn bộ các dịch vụ nền
docker-compose up -d

# Xem trạng thái các container
docker ps --filter "name=tymap_"

# Xem log của từng dịch vụ
docker logs --tail 50 -f tymap_graphhopper
docker logs --tail 50 -f tymap_nominatim
docker logs --tail 50 -f tymap_overpass
```

### 2. Khởi chạy / Kiểm tra Web Dashboard (Port 8085)
```bash
# Kiểm tra log dashboard
cat /home/nas152/graphhopper-data/web_dashboard.log

# Khởi chạy lại Web Dashboard nếu cần
cd /home/nas152/graphhopper-data && nohup python3 -m http.server 8085 > web_dashboard.log 2>&1 &
```

### 3. Cập nhật dữ liệu PBF Master Data
```bash
# Chạy trực tiếp script cập nhật PBF bằng tay
/home/nas152/graphhopper-data/update_pbf.sh
```
