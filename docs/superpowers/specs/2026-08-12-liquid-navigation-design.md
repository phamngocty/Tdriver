# Thiết Kế Chi Tiết: Liquid Navigation Bar (Fluid Floating Bubble) Cho TYMAP Android App

> **Ngày tạo:** 2026-08-12  
> **Dự án:** TYMAP Android Application (`TYMAP/app`)  
> **Tính năng:** Thay thế BottomNavigationView chuẩn bằng thanh điều hướng Liquid Floating Bubble hiện đại.

---

## 1. MỤC TIÊU & TỔNG QUAN

Nâng cấp giao diện điều hướng đáy (Bottom Navigation) của ứng dụng Android TYMAP từ thanh điều hướng phẳng truyền thống sang dạng **Fluid Floating Bubble (Bong bóng Nổi Liquid)**.

### Mục tiêu chính:
1. **Trải nghiệm trực quan cao cấp (Premium UI/UX):** Tạo điểm nhấn hiện đại với hiệu ứng bong bóng nhô cao (-16dp) khi tab được chọn, kết hợp màu sắc gradient Tím-Xanh (`#6366F1` $\rightarrow$ `#8B5CF6`) và bóng đổ Neon Glow.
2. **Hiệu ứng vật lý mượt mà (Fluid Motion):** Sử dụng `OvershootInterpolator` cho chuyển động đàn hồi 300ms khi người dùng chuyển đổi giữa các tab.
3. **Giữ nguyên logic nghiệp vụ:** Đảm bảo tương thích 100% với `ViewPager2`, không ảnh hưởng tới luồng nhận thông báo Google Maps, Bluetooth BLE Service hay tính năng ẩn/hiện động của tab `Render`.

---

## 2. KIẾN TRÚC GIAO DIỆN & THÀNH PHẦN MÃ NGUỒN

### Các tệp tin can thiệp:

| Loại tệp | Đường dẫn tệp | Vai trò |
| :--- | :--- | :--- |
| **Custom View / Layout** | `TYMAP/app/src/main/res/layout/layout_liquid_bubble_nav.xml` | Structure giao diện thanh Liquid Bubble nổi |
| **Layout chính** | `TYMAP/app/src/main/res/layout/activity_main.xml` | Thay thế `BottomNavigationView` bằng `LiquidBubbleNavView` |
| **Custom View Code** | `TYMAP/app/src/main/java/com/example/tymap/ui/LiquidBubbleNavView.kt` | Logic vẽ bong bóng, tính toán vị trí icon, xử lý animator |
| **Drawable Resources** | `TYMAP/app/src/main/res/drawable/bg_liquid_nav_capsule.xml`<br>`bg_liquid_active_bubble.xml` | Background capsule thủy tinh mờ & bong bóng active |
| **Activity Logic** | `TYMAP/app/src/main/java/com/example/tymap/MainActivity.kt` | Gắn callback chuyển tab `ViewPager2` với `LiquidBubbleNavView` |

---

## 3. THIẾT KẾ CHI TIẾT (UI & ANIMATION SPECIFICATION)

### 3.1. Kích thước & Kiểu dáng (Styling)

- **Container Capsule (Thanh chứa nổi):**
  - Margin: Bottom 12dp, Left 16dp, Right 16dp.
  - Height: 64dp.
  - Background: Bo góc 32dp (`bg_liquid_nav_capsule.xml`), màu nền `#0F172A` với viền `1dp solid rgba(255,255,255,0.12)`.
  - Elevation: 12dp.

- **Bong bóng Active (Floating Bubble):**
  - Shape: Hình tròn $44 \times 44\text{dp}$ (`bg_liquid_active_bubble.xml`).
  - Gradient: Linear $135^\circ$ từ `#6366F1` đến `#8B5CF6`.
  - Offset Y Active: Trồi lên phía trên **$-16\text{dp}$** so với trục giữa thanh navigation.
  - Shadow: Radial Glow màu `#6366F1` với alpha 0.4.

- **Icons & Text Label:**
  - Map (`ic_map`), Cài đặt (`ic_settings`), Thông báo (`ic_notifications`), Render (`ic_layers`).
  - Màu Tab Active: Trắng `#FFFFFF` (nằm trong Bong bóng).
  - Màu Tab Inactive: Xám `#64748B`.
  - Nhãn Text: Nằm phía dưới icon, kích thước 11sp, hiển thị màu sắc tương ứng với trạng thái tab.

### 3.2. Hiệu ứng Chuyển động (Animation Logic)

1. Khi người dùng bấm chọn một Tab $i$:
   - **Bong bóng Active cũ:** Hạ thấp về vị trí gốc ($0\text{dp}$), thu nhỏ scale từ $1.15 \rightarrow 1.0$, mờ màu background gradient về xám mờ.
   - **Bong bóng Active mới:** Trồi lên vị trí $-16\text{dp}$, phóng to scale từ $1.0 \rightarrow 1.15$, hiện rõ background gradient + glow.
   - **Interpolator:** `OvershootInterpolator(1.4f)` giúp tạo cảm giác nảy nhẹ bồng bềnh (bouncing bubble).
   - **Thời gian:** 280ms.

---

## 4. TƯƠNG THÍCH ĐỒNG BỘ VỚI MAINACTIVITY

1. **Tương tác với `ViewPager2`:**
   - Khi chọn tab trên `LiquidBubbleNavView` $\rightarrow$ gọi `viewPager.currentItem = position`.
   - Khi vuốt `ViewPager2` (hoặc chuyển tab bằng mã lệnh) $\rightarrow$ callback `onPageSelected(position)` gọi `liquidNavView.setSelectedTab(position, animate = true)`.
2. **Xử lý tab Render động (`updateRenderTabVisibility()`):**
   - Khi tab Render bị ẩn (`render_tab_unlocked == false`), thanh `LiquidBubbleNavView` tự động điều chỉnh chia đều 3 cột (thay vì 4 cột).
   - Khi tab Render được unlock $\rightarrow$ mượt mà hiển thị cột thứ 4.

---

## 5. KẾ HOẠCH KIỂM THỬ (VERIFICATION PLAN)

### 5.1. Kiểm tra tĩnh & Biên dịch (Build Verification):
- Chạy `./gradlew assembleDebug` để đảm bảo không có lỗi cú pháp Kotlin hay XML Resource ID.

### 5.2. Kiểm tra giao diện & Chức năng (Functional Checks):
- Chuyển đổi giữa 4 tab (Bản đồ $\leftrightarrow$ Cài đặt $\leftrightarrow$ Thông báo $\leftrightarrow$ Render) xem hiệu ứng bong bóng nổi có mượt mà không bị giật lag.
- Đảm bảo khi mở khóa tab Render từ Cài đặt, thanh Liquid Navigation cập nhật chính xác vị trí bong bóng cho cả 4 tab.
