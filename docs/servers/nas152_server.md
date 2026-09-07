# 🖥️ HƯỚNG DẪN & CẤU TRÚC SERVER NAS (192.168.1.114)

## 🔑 Thông tin Kết nối Hệ Thống
- **Địa chỉ LAN:** `192.168.1.114`
- **Tên miền Public (DuckDNS):** `nas152.duckdns.org`
- **DuckDNS Token:** `75d515eb-26eb-440a-8c02-685607cbb07a`
- **SSH Command:** `ssh nas152@192.168.1.114`
- **SSH Password:** `271000`
- **Thư mục dịch vụ chính:** `/home/nas152/nas_services` (hoặc `/home/nas152/graphhopper-data`)
- **Docker Network chung:** `tymap_net` (Bridge)

---

## 🌐 Bảng Quy Hoạch URL Services & Phân Luồng Gateway

Hệ thống sử dụng **Nginx Proxy Manager (NPM)** làm Gateway bảo mật, cấp chứng chỉ **SSL Let's Encrypt tự động** và chia luồng trực tiếp đến các container backend:

| Nhóm Dịch Vụ | Tên Dịch vụ | Port Nội bộ (LAN) | Public Subdomain (4G/5G HTTPS) | Container / Service | Chức năng chính |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Hệ thống & Quản trị** | **CasaOS Dashboard** | `http://192.168.1.114/` | *(Chỉ mở LAN)* | `casaos-gateway` | Quản trị giao diện hệ điều hành CasaOS gốc (Port 80 mặc định) |
| | **NPM Web UI** | `http://192.168.1.114:81` | *(Chỉ mở LAN)* | `tymap_npm` | Quản trị Nginx Proxy Manager, điều phối luồng mạng & SSL |
| | **NPM Gateway Ports** | `Port 8082` (HTTP) / `Port 443` (HTTPS) | `https://*.nas152.duckdns.org` | `tymap_npm` | Cổng tiếp nhận lưu lượng HTTPS Let's Encrypt ra Internet |
| | **FileBrowser** | `http://192.168.1.114:8080` | *(Chỉ mở LAN)* | `big-bear-filebrowser` | Quản lý, tải lên/tải xuống tệp tin qua Web |
| | **Uptime Kuma** | `http://192.168.1.114:3003` | *(Chỉ mở LAN)* | `uptime-kuma` | Giám sát trạng thái uptime & tình trạng sống chết của services |
| | **SSH Server** | `Port 22` | *(Chỉ mở LAN / VPN)* | `sshd` (Systemd) | Truy cập dòng lệnh máy chủ (`ssh nas152@192.168.1.114`) |
| | **Windows SMB Share** | `Port 139, 445` | *(Chỉ mở LAN)* | `smbd` / `nmbd` | Chia sẻ thư mục qua mạng nội bộ Windows Network Drive |
| **Hệ sinh thái TyMap** | **GraphHopper Routing** | `http://192.168.1.114:8989/route` | `https://route.nas152.duckdns.org/route` | `tymap_graphhopper` | Định tuyến dẫn đường Turn-by-Turn cho xe máy & ô tô |
| | **Fusion Engine (HUD & Alert)** | `http://192.168.1.114:8088/` | `https://alert.nas152.duckdns.org/` | `tymap_fusion_engine` | Cảnh báo camera phạt nguội, tốc độ & cấp phát version.json |
| | **Nominatim Geocoding** | `http://192.168.1.114:8081/reverse` | `https://geo.nas152.duckdns.org/reverse` | `nominatim-vn` | Bóc tách & tìm kiếm tọa độ chính xác, reverse geocoding VN |
| | **Photon Autocomplete** | `http://192.168.1.114:2322/api` | `https://search.nas152.duckdns.org/api` | `tymap_photon` | Tìm kiếm địa chỉ mờ tiếng Việt cực nhanh (< 50ms) |
| | **DuckDNS Updater** | *(Chạy nền nội bộ)* | — | `tymap_duckdns` | Tự động đồng bộ IP Public lên DuckDNS mỗi 5 phút |
| | **Dashboard Quản lý Chính** | `http://192.168.1.114:8085/` | *(Chỉ mở LAN)* | `python3 -m http.server` | Giao diện điều khiển trung tâm, HUD simulation, radar mưa |
| | **Webhook Cập nhật PBF** | `http://192.168.1.114:8990/update_pbf` | *(Chỉ mở LAN)* | `webhook_server.py` | Tự động tải OSM PBF Việt Nam mới nhất & build lại cache |
| **Ứng dụng Lưu trữ & Khác** | **Gitea Git Server** | `http://192.168.1.114:3002/` (SSH: 222) | `https://git.nas152.duckdns.org/` | `gitea_old-gitea_old-1` | Máy chủ Git cá nhân, lưu mã nguồn, APK releases & firmware |
| | **Immich Photos** | `http://192.168.1.114:2283` | *(Chỉ mở LAN)* | `immich-server` | Quản lý, sao lưu & nhận diện ảnh/video tương tự Google Photos |
| | **Kaneo Kanban** | `http://192.168.1.114:5173` | *(Chỉ mở LAN)* | `kaneo-kaneo-1` | Quản lý dự án, công việc Kanban (Postgres Port 5432) |
| | **Karakeep** | `http://192.168.1.114:14592` | *(Chỉ mở LAN)* | `karakeep` | Quản lý và phát nhạc Karaoke gia đình |
| | **Tailscale VPN** | `100.81.207.28` | *(VPN nội bộ)* | `big-bear-tailscale` | Mạng riêng ảo kết nối NAS an toàn từ xa không cần mở port |

---

## 📂 Cấu trúc Thư Mục Master Data & Cấu Hình trên NAS

### 1. Dữ liệu Lõi dùng chung (Master Data)
Thư mục: `/home/nas152/tymap_data`
```text
/home/nas152/tymap_data/
 ├── osm_source/
 │    └── vietnam-latest.osm.pbf     <-- File OSM gốc đọc chung (GraphHopper, Nominatim)
 ├── graphhopper_cache/              <-- Cache ma trận đường đi của GraphHopper
 ├── nominatim_db/                   <-- Database PostgreSQL của Nominatim
 ├── photon_data/                    <-- Dữ liệu index tìm kiếm nhanh của Photon
 ├── cameras/                        <-- Dữ liệu camera CSV cho Fusion Engine
 │    ├── osm_cameras.csv
 │    └── camera_vn.csv
 └── npm/                            <-- Dữ liệu cấu hình & Chứng chỉ SSL của NPM
      ├── data/                      <-- SQLite DB & Nginx config
      └── letsencrypt/               <-- Chứng chỉ SSL Let's Encrypt
```

### 2. File Cấu hình Docker Stack (`nas_services/docker-compose.yml`)
```yaml
version: '3.8'

networks:
  tymap_net:
    name: tymap_net
    driver: bridge

services:
  # 1. Nginx Proxy Manager (Cổng điều phối & Cấp SSL)
  tymap_npm:
    image: 'jc21/nginx-proxy-manager:latest'
    container_name: tymap_npm
    restart: unless-stopped
    ports:
      - "8082:80"   # Port HTTP phụ cho NPM (nhường Port 80 mặc định cho CasaOS)
      - "443:443"   # Đón HTTPS bảo mật từ 4G/5G vào App Android
      - "81:81"     # Web UI quản trị (LAN: http://192.168.1.114:81)
    environment:
      DB_SQLITE_FILE: "/data/database.sqlite"
      DISABLE_IPV6: 'true'
    volumes:
      - /home/nas152/tymap_data/npm/data:/data
      - /home/nas152/tymap_data/npm/letsencrypt:/etc/letsencrypt
    networks:
      - tymap_net

  # 2. DuckDNS Auto-Updater (Đồng bộ IP Public mỗi 5 phút)
  tymap_duckdns:
    image: lscr.io/linuxserver/duckdns:latest
    container_name: tymap_duckdns
    restart: unless-stopped
    environment:
      - PUID=1000
      - PGID=1000
      - TZ=Asia/Ho_Chi_Minh
      - SUBDOMAINS=nas152
      - TOKEN=75d515eb-26eb-440a-8c02-685607cbb07a
      - UPDATE_IP=both
      - LOG_FILE=false
    networks:
      - tymap_net

  # 3. GraphHopper Routing Engine (Port 8989)
  tymap_graphhopper:
    image: israelhikingmap/graphhopper:latest
    container_name: tymap_graphhopper
    restart: unless-stopped
    ports:
      - "8989:8989"
    environment:
      - JAVA_OPTS=-Xmx2g -Xms1g
    volumes:
      - /home/nas152/tymap_data/osm_source/vietnam-latest.osm.pbf:/data/vietnam-latest.osm.pbf
      - /home/nas152/graphhopper-data/config-loc.yml:/graphhopper/config.yml
      - /home/nas152/tymap_data/graphhopper_cache:/data/default-gh
    networks:
      - tymap_net

  # 4. Nominatim Geocoding (Port 8081)
  tymap_nominatim:
    image: mediagis/nominatim:4.4
    container_name: tymap_nominatim
    restart: unless-stopped
    ports:
      - "8081:8080"
    environment:
      - PBF_URL=file:///data/vietnam-latest.osm.pbf
      - REPLICATION_URL=https://download.geofabrik.de/asia/vietnam-updates/
    volumes:
      - /home/nas152/tymap_data/osm_source/vietnam-latest.osm.pbf:/data/vietnam-latest.osm.pbf
      - /home/nas152/tymap_data/nominatim_db:/var/lib/postgresql/14/main
    networks:
      - tymap_net

  # 5. Photon Autocomplete (Port 2322)
  tymap_photon:
    image: rtuszik/photon-docker:latest
    container_name: tymap_photon
    restart: unless-stopped
    ports:
      - "2322:2322"
    volumes:
      - /home/nas152/tymap_data/photon_data:/photon/photon_data
    networks:
      - tymap_net

  # 6. Fusion Engine (Port 8088 - Cảnh báo Camera & Tốc độ)
  tymap_fusion_engine:
    build:
      context: ./fusion_engine
      dockerfile: Dockerfile
    container_name: tymap_fusion_engine
    restart: unless-stopped
    ports:
      - "8088:8088"
    environment:
      - PORT=8088
      - DATA_DIR=/app/data
    volumes:
      - /home/nas152/tymap_data/cameras:/app/data
    networks:
      - tymap_net
```

---

## 🛠️ Các lệnh quản lý & kiểm tra nhanh trên NAS

### 1. Khởi chạy / Quản lý toàn bộ Docker Stack
```bash
# Di chuyển vào thư mục cấu hình
cd /home/nas152/nas_services

# Khởi động toàn bộ các dịch vụ nền
docker compose up -d

# Xem trạng thái các container
docker ps --filter "name=tymap"

# Xem log kiểm tra từng dịch vụ
docker logs --tail 50 -f tymap_npm
docker logs --tail 50 -f tymap_duckdns
docker logs --tail 50 -f tymap_graphhopper
docker logs --tail 50 -f tymap_fusion_engine
```

### 2. Cấu hình Proxy Hosts trên NPM Web UI (Port 81)
1. Đăng nhập vào `http://192.168.1.114:81` (Tài khoản: `admin@example.com` / `changeme`).
2. Tạo 4 Proxy Hosts với SSL Let's Encrypt (Force SSL + HTTP/2 Support):
   * `route.nas152.duckdns.org` $\to$ `tymap_graphhopper:8989`
   * `search.nas152.duckdns.org` $\to$ `tymap_photon:2322`
   * `geo.nas152.duckdns.org` $\to$ `tymap_nominatim:8081`
   * `alert.nas152.duckdns.org` $\to$ `tymap_fusion_engine:8088`

### 3. Cập nhật dữ liệu PBF Master Data
```bash
# Tải file PBF Việt Nam mới nhất & rebuild
/home/nas152/graphhopper-data/update_pbf.sh
```
