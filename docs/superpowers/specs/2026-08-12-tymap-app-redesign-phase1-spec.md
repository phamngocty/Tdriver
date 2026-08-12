# Tài Liệu Thiết Kế Cấu Trúc: Tái Cấu Trúc Giao Diện TYMAP App (Phân Đoạn 1)

> **Ngày tạo:** 2026-08-12  
> **Dự án:** TYMAP Android Application (`TYMAP/app`)  
> **Mục tiêu:** Nâng cấp khung vỏ ứng dụng (App Shell) & Thanh điều hướng Liquid Floating Bubble Bottom Navigation.

---

## 1. TỔNG QUAN PHÂN ĐOẠN 1 (PHASE 1 SCOPE)

Phân đoạn 1 sẽ tập trung tái cấu trúc phần **Khung vỏ (Shell)** của toàn bộ ứng dụng TYMAP:
1. **Thanh điều hướng Liquid Bottom Navigation (`LiquidBubbleNavView`):** Thay thế thanh Bottom Navigation cũ bằng thanh bong bóng nổi Liquid Glassmorphism (Option B).
2. **Cockpit Dark Glassmorphic Theme:** Đặt theme tối đồng nhất `#090D16` làm nền toàn ứng dụng, loại bỏ màu sáng xám chuẩn Android cũ.
3. **Thanh Header/App Bar thống nhất:** Cập nhật Header với font chữ gradient và huy hiệu trạng thái kết nối Bluetooth BLE live status badge.

---

## 2. DANH SÁCH TỆP TIN THỰC THI (FILES TO MODIFY / CREATE)

| Thao tác | Tệp tin | Mô tả |
| :--- | :--- | :--- |
| **[NEW]** | `TYMAP/app/src/main/res/drawable/bg_liquid_nav_capsule.xml` | Drawable nền cho thanh capsule thủy tinh mờ (`#0F172A`, corners 32dp, stroke 1dp) |
| **[NEW]** | `TYMAP/app/src/main/res/drawable/bg_liquid_active_bubble.xml` | Drawable bong bóng active gradient ($135^\circ$ từ `#6366F1` đến `#8B5CF6`) |
| **[NEW]** | `TYMAP/app/src/main/res/layout/layout_liquid_bubble_nav.xml` | Layout XML cho 4 tab điều hướng (Bản đồ, Cài đặt, Thông báo, Render) |
| **[NEW]** | `TYMAP/app/src/main/java/com/example/tymap/ui/LiquidBubbleNavView.kt` | Custom View quản lý hiệu ứng bong bóng nhô cao (-16dp) với `OvershootInterpolator` |
| **[MODIFY]** | `TYMAP/app/src/main/res/layout/activity_main.xml` | Thay thế `BottomNavigationView` bằng `LiquidBubbleNavView` |
| **[MODIFY]** | `TYMAP/app/src/main/java/com/example/tymap/MainActivity.kt` | Liên kết `LiquidBubbleNavView` với `ViewPager2` và hàm `updateRenderTabVisibility()` |
| **[MODIFY]** | `TYMAP/app/src/main/res/values/colors.xml` | Khai báo bảng màu Cockpit Dark Theme (`colorBackgroundDeep`, `colorAccentCyan`, `colorAccentPurple`) |

---

## 3. QUY TRÌNH KIỂM THỬ & XÁC NHẬN (VERIFICATION PLAN)

1. **Biên dịch & Build:**
   Chạy lệnh `./gradlew assembleDebug` để đảm bảo code Kotlin và Layout XML biên dịch thành công 100%.
2. **Kiểm tra chức năng điều hướng:**
   - Đảm bảo khi bấm vào từng tab (Bản đồ, Cài đặt, Thông báo, Render), bong bóng trồi lên mượt mà và `ViewPager2` chuyển màn tương ứng.
   - Đảm bảo khi vuốt màn hình `ViewPager2`, bong bóng trên thanh Liquid Navigation tự động di chuyển theo.
3. **Kiểm tra Tab Render Động:**
   Xác nhận tính năng ẩn/hiện tab Render khi `render_tab_unlocked` thay đổi vẫn hoạt động chuẩn xác.
