# Tài Liệu Thiết Kế Tái Cấu Trúc Toàn Bộ Giao Diện TYMAP Android App (Cockpit Dark Glassmorphism)

> **Ngày tạo:** 2026-08-12  
> **Dự án:** TYMAP Android Application (`TYMAP/app`)  
> **Phạm vi:** Tái cấu trúc toàn bộ 4 màn hình chính (`MapFragment`, `SettingsFragment`, `NotificationsFragment`, `RenderFragment`) và các thành phần giao diện theo chuẩn Cockpit Dark Glassmorphism.

---

## 1. MỤC TIÊU & TỔNG QUAN HỆ THỐNG THIẾT KẾ

Áp dụng ngôn ngữ thiết kế **Cockpit Dark Glassmorphism** đồng nhất cho tất cả màn hình của ứng dụng TYMAP trên điện thoại Android:

### 1.1. Bảng màu & Phong cách thiết kế chuẩn (Design Tokens)
- **Background chính:** Deep Space Navy (`#090D16`).
- **Thẻ Glass Panel:** Background `#D9131B2E` với viền mờ `1dp solid rgba(255,255,255,0.08)`, bo góc $18\text{dp}$, bóng đổ $8\text{dp}$.
- **Accent Colors:**
  - **Electric Cyan (`#38BDF8`):** Cho chỉ dẫn bản đồ, tên đường, thông tin dẫn đường.
  - **Cyber Purple (`#8B5CF6`):** Cho kết nối Bluetooth BLE, cài đặt hệ thống & giao diện active.
  - **Emerald Green (`#10B981`):** Cho trạng thái kết nối Online, ETA đúng giờ & công tắc gạt Bật.
  - **Neon Amber (`#F59E0B`):** Cho cảnh báo tốc độ & trạng thái bận.

---

## 2. CHI TIẾT THIẾT KẾ CÁC MÀN HÌNH CHÍNH

### 2.1. Màn Hình Bản Đồ & Dẫn Đường (`fragment_map.xml` & `bottom_sheet_navigation.xml`)
1. **Floating Search Card:**
   - Bo góc $24\text{dp}$, background `#D91E293B`, viền `1dp solid rgba(56,189,248,0.3)`.
   - Icon kính lúp màu Electric Cyan `#38BDF8`.
2. **ESP32 Live Mirror Widget:**
   - Widget hình tròn $130 \times 130\text{dp}$ đặt nổi giữa màn hình, mô phỏng màn hình đồng hồ tròn $240 \times 240\text{px}$ của ESP32.
   - Viền cyan glow `2dp solid #38BDF8`, hiển thị mũi tên rẽ realtime, khoảng cách rẽ & tốc độ GPS.
3. **Thẻ Chỉ Dẫn Rẽ Google Maps:**
   - Card Glassmorphism hiển thị icon rẽ to $32\text{dp}$ màu `#38BDF8`, tên đường sắp rẽ, khoảng cách đến điểm rẽ và ETA thời gian còn lại.
4. **Các nút bấm nổi Floating Action Buttons (FAB):**
   - Đổi background các nút vị trí, lớp bản đồ, la bàn sang hình tròn Dark Glass `#D91E293B` với viền `#44FFFFFF` đồng bộ với Cockpit Theme.

### 2.2. Màn Hình Cài Đặt & Kết Nối BLE (`fragment_settings.xml` & `fragment_connection.xml`)
1. **Thẻ Trạng Thái Kết Nối BLE:**
   - Hiển thị tên thiết bị `TYMAP GC9A01 Smartwatch`, địa chỉ MAC, và huy hiệu Live RSSI Signal Meter (vd: `-62 dBm` màu Xanh Lá).
2. **Bộ Chọn Mẫu Mặt Đồng Hồ (Watchface Style Selector):**
   - Hàng chip card cho các mẫu mặt đồng hồ STATUS (S1 Minimalist, S2 Racing, S3 Dual Energy) với trạng thái active viền tím `#8B5CF6`.
3. **Công Tắc Cấu Hình Chế Độ HUD:**
   - Các hàng công tắc Glassmorphism cho phép bật/tắt tự động đổi chế độ MAP HUD (&le; 300m) và MAP PURE (&le; 100m).

### 2.3. Màn Hình Quản Lý Thông Báo (`fragment_notifications.xml` & `item_notification_app.xml`)
1. **Lắng Nghe Thông Báo Google Maps:**
   - Thẻ Glassmorphism cho dịch vụ `GMapsNotificationListener` với huy hiệu xanh Cyan "Listener Active".
2. **Danh Sách Ứng Dụng Thông Báo:**
   - Các item ứng dụng (`Google Maps`, `Zalo`, `Messenger`, `Cuộc gọi`) thiết kế dạng Card Glass bo góc $14\text{dp}$, icon to rõ và công tắc gạt neon mượt mà.

### 2.4. Màn Hình Render Debug & Bộ Lọc Màu (`fragment_render.xml`)
1. **Thẻ Canvas Preview:**
   - Khung xem trước hình ảnh render Rolling Map với viền neon `#8B5CF6`.
2. **Trình Điều Khiển Bộ Lọc Màu OLED:**
   - Khung điều khiển bộ lọc màu OLED (Đen/Trắng, Cyan Accent, Gold Accent) thiết kế dạng Card Glass.

---

## 3. DANH SÁCH TỆP TIN CAN THIỆP (FILES TO MODIFY / CREATE)

| Loại tệp | Đường dẫn tệp | Mô tả |
| :--- | :--- | :--- |
| **Drawable** | `TYMAP/app/src/main/res/drawable/bg_glass_card.xml`<br>`bg_glass_search.xml`<br>`bg_esp32_mirror_widget.xml` | Các drawable nền Glassmorphism mờ & widget gương ESP32 |
| **Map Screen Layout** | `TYMAP/app/src/main/res/layout/fragment_map.xml`<br>`bottom_sheet_navigation.xml` | Tái cấu trúc layout màn hình bản đồ & thẻ chỉ dẫn rẽ |
| **Settings Layout** | `TYMAP/app/src/main/res/layout/fragment_settings.xml`<br>`fragment_connection.xml` | Tái cấu trúc layout cài đặt & kết nối BLE |
| **Notif Layout** | `TYMAP/app/src/main/res/layout/fragment_notifications.xml`<br>`item_notification_app.xml` | Tái cấu trúc layout danh sách thông báo |
| **Render Layout** | `TYMAP/app/src/main/res/layout/fragment_render.xml` | Tái cấu trúc layout màn hình render debug |

---

## 4. KẾ HOẠCH KIỂM THỬ & XÁC NHẬN (VERIFICATION PLAN)

1. **Biên dịch Gradle:** Run `./gradlew assembleDebug` đảm bảo không có lỗi XML Resource ID hay syntax Kotlin.
2. **Visual Check & Layout Verification:** Đảm bảo tất cả 4 màn hình hiển thị chuẩn Cockpit Dark Glassmorphism, không bị đè chữ, đè nút hay lệch viền window insets.
