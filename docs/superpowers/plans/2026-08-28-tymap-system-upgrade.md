# Kế hoạch Nâng cấp Hệ thống TYMAP (Backend, Android & ESP32 BLE)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nâng cấp toàn diện hệ thống TYMAP gồm 4 phân hệ: Thay thế Overpass bằng Fusion Engine Node.js trên NAS, thiết kế luồng bất đồng bộ trên Android, tối ưu hóa tìm kiếm kết hợp SQLite và Photon, và đồng bộ cảnh báo tốc độ qua BLE sang ESP32 GC9A01.

**Architecture:** 
- **NAS Backend**: Node.js Fusion Engine gom `osm_cameras.csv` + `camera_vn.csv` với thuật toán Spatial Deduplication, GraphHopper xe máy, Nominatim với User-Agent fix, Photon Autocomplete (port 2322).
- **Android App**: Luồng Async 3 tầng (GraphHopper -> Fusion Engine -> Open-Meteo), SQLite `SavedPlaceDbHelper` lưu địa điểm cá nhân, Merged Search (ưu tiên SQLite trước, Photon sau), Offline Haversine GPS alert (< 200m).
- **ESP32 GC9A01**: Nhận packet Hex BLE (`0x01` Cam / `0x02` Speed + value), kích hoạt ngắt giao diện nháy đỏ tức thì.

**Tech Stack:** Kotlin, Android SDK (Coroutines, SQLite, BleManager), Node.js (Fusion Engine, Express), Docker Compose, C++/ESP32 Arduino/PlatformIO (NimBLE, TFT_eSPI/GC9A01).

---

### Task 1: Xây dựng Fusion Engine (Node.js) & Cập nhật Docker Compose trên NAS

**Files:**
- Create: `nas_services/fusion_engine/server.js`
- Create: `nas_services/fusion_engine/package.json`
- Create: `nas_services/fusion_engine/Dockerfile`
- Create: `nas_services/docker-compose.yml`
- Modify: `docs/servers/nas152_server.md`

- [ ] **Step 1: Viết server.js cho Fusion Engine**
  Đọc 2 file CSV (`osm_cameras.csv`, `camera_vn.csv`), khử trùng lặp trong bán kính 25m, cung cấp API:
  - `POST /api/warnings/route`: Nhận danh sách tọa độ polyline, trả về điểm camera/biển báo dọc đường.
  - `GET /api/warnings/nearby`: Nhận toạ độ tâm và bán kính, trả về điểm xung quanh.
  - `GET /health`: Kiểm tra trạng thái.

- [ ] **Step 2: Cập nhật docker-compose.yml và tài liệu máy chủ NAS**
  Gỡ bỏ `tymap_overpass` container (tiết kiệm 1.5GB RAM), bổ sung `tymap_fusion_engine` (port 8088 hoặc 8086) và `tymap_photon` (port 2322).

---

### Task 2: Triển khai SQLite Database & Merged Search trên Android

**Files:**
- Create: `TYMAP/app/src/main/java/com/example/tymap/repository/SavedPlaceDbHelper.kt`
- Modify: `TYMAP/app/src/main/java/com/example/tymap/ui/MapFragment.kt`
- Modify: `TYMAP/app/src/main/res/layout/fragment_map.xml`

- [ ] **Step 1: Tạo lớp `SavedPlaceDbHelper.kt`**
  Lưu trữ các địa điểm cá nhân (Nhà riêng, Cơ quan, Yêu thích, Gần đây) bằng SQLite native của Android.

- [ ] **Step 2: Cập nhật giao diện và luồng tìm kiếm trong `MapFragment.kt`**
  - Cập nhật placeholder tìm kiếm thành `"Nhập tên đường, ngã tư hoặc khu vực..."`.
  - Thực hiện tìm kiếm song song: Truy vấn từ `SavedPlaceDbHelper` + gọi Photon Autocomplete.
  - Gộp kết quả: Đưa các địa điểm đã lưu lên đầu danh sách, sau đó đến kết quả gợi ý từ Photon.
  - Xóa bỏ các chuỗi rác như `[Photon Autocomplete]`.
  - Hiển thị mục `"Ghim vị trí trực tiếp trên bản đồ"` khi kết quả tìm kiếm rỗng.

---

### Task 3: Tối ưu Luồng Bất Đồng Bộ, Fix Kết Nối Nominatim & Offline Haversine

**Files:**
- Modify: `TYMAP/app/src/main/java/com/example/tymap/service/RoutingEngine.kt`
- Modify: `TYMAP/app/src/main/java/com/example/tymap/service/TrafficWarningManager.kt`
- Modify: `TYMAP/app/src/main/java/com/example/tymap/utils/UrlParser.kt`

- [ ] **Step 1: Tối ưu luồng bất đồng bộ trong `RoutingEngine.kt`**
  - Ưu tiên 1 (Tức thì): GraphHopper tính và vẽ đường Polyline ngay lập tức.
  - Ưu tiên 2 (Chạy nền IO): Gửi chuỗi Polyline lên NAS Fusion Engine để lọc điểm cảnh báo vào RAM Cache.
  - Ưu tiên 3 (Chạy nền IO): Gọi Open-Meteo lấy thông tin thời tiết điểm đến.

- [ ] **Step 2: Cập nhật `TrafficWarningManager.kt`**
  - Kết nối tới Fusion Engine thay vì Overpass cũ.
  - Đảm bảo quét Offline với `warningCache` bằng thuật toán Haversine / `Location.distanceBetween` khi có GPS mới.
  - Gửi tín hiệu cảnh báo qua `BleManager.sendTrafficWarning`.

- [ ] **Step 3: Khắc phục kết nối Nominatim**
  - Bổ sung header `User-Agent: TYMAP-Android/1.0 (contact@tymap.local)` vào tất cả các request gửi đến Nominatim.

---

### Task 4: Chuẩn hóa Gói tin BLE & Xử lý Ngắt Hiển thị Cảnh báo trên ESP32

**Files:**
- Modify: `TYMAP/app/src/main/java/com/example/tymap/ble/BleManager.kt`
- Modify: `TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp`
- Modify: `TYMAP/firmware/esp32_s3_gc9a01/src/gui.cpp`

- [ ] **Step 1: Chuẩn hóa gói tin Hex trong `BleManager.kt`**
  - Gửi payload 2-byte: `[Type, SpeedLimit]`.

- [ ] **Step 2: Tối ưu phản hồi ngắt và hiệu ứng nháy đỏ trên ESP32 GC9A01**
  - Trong NimBLE write callback của `CHA_WARNING_UUID`, kích hoạt trạng thái cảnh báo ngay lập tức.
  - Trong `gui.cpp`, tối ưu hàm `drawTrafficWarningOverlay()` để hiển thị viền đỏ nhấp nháy và icon camera / biển tốc độ tròn rõ nét không trễ khung hình.
