# 📚 HỆ THỐNG QUẢN LÝ TÀI LIỆU DỰ ÁN (MASTER DOCUMENTATION INDEX)

> **Trung tâm điều hướng & tra cứu tài liệu toàn bộ dự án TYMAP / Tdriver**  
> *Vị trí thư mục:* `d:\Documents\PlatformIO\Tdriver\docs\`

---

## 🖥️ 1. Máy Chủ Server & Backend (`docs/servers/`)

- [**`nas152_server.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/servers/nas152_server.md)  
  *Nội dung:* Hướng dẫn SSH (`nas152@192.168.1.114`), tài khoản/mật khẩu, danh sách các Port (Dashboard `8085`, GraphHopper API `8989`, Webhook `8990`), vị trí file HTML và lệnh Docker/Shell trên NAS.
  *Tính năng Dashboard & GraphHopper:* Hỗ trợ Profile Xe máy (Scooter/Motorcycle) đi hẻm ngõ nhỏ Việt Nam, tùy chọn Custom Weighting (Né trạm thu phí, né phà, né đường hẹp), hỗ trợ PBF mới nhất từ Geofabrik.

---

## 🏛️ 2. Kiến Trúc Dự Án & Master Knowledge (`docs/architecture/`)

- [**`AI_PROJECT_CONTEXT.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/architecture/AI_PROJECT_CONTEXT.md)  
  *Nội dung:* Master Architecture chứa toàn bộ thông tin chiều sâu về Android App (Kotlin SDK 35) & ESP32-S3 Firmware (C++ PlatformIO), luồng dữ liệu BLE, GPS, render bản đồ 2.5D.
- [**`TYMAP_OVERVIEW.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/architecture/TYMAP_OVERVIEW.md)  
  *Nội dung:* Tổng quan thiết kế & mục tiêu phát triển hệ thống TYMAP.

---

## 🛠️ 3. Danh Sách Lỗi Cần Sửa & Việc Cần Làm (`docs/tasks_and_bugs/`)

- [**`firmware_bugs_to_fix.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/tasks_and_bugs/firmware_bugs_to_fix.md)  
  *Nội dung (trước đây là `loicansua.md`):* Danh sách lỗi cần sửa cho Firmware ESP32 & App: Cơ chế xoay bản đồ (Track Up), hiển thị full map không cắt, thêm tab Render giả lập 240x240px, tối ưu tốc độ gửi ảnh smart...
- [**`display_fix_gc9a01.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/tasks_and_bugs/display_fix_gc9a01.md)  
  *Nội dung:* Nhật ký fix lỗi hiển thị màn hình LCD tròn GC9A01.
- [**`icon_fix_notes.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/tasks_and_bugs/icon_fix_notes.md)  
  *Nội dung:* Nhật ký và ghi chú sửa icon hướng dẫn rẽ.

---

## ⚡ 4. Tính Năng & Giao Thức (`docs/features/`)

- [**`ble_commands.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/features/ble_commands.md)  
  *Nội dung:* Định nghĩa định dạng gói tin BLE GATT giữa Android & ESP32-S3.
- [**`map_hud_design.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/features/map_hud_design.md)  
  *Nội dung:* Thiết kế chi tiết chế độ bản đồ MAP HUD.
- [**`ota_guide.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/features/ota_guide.md)  
  *Nội dung:* Giao thức nạp phần mềm từ xa Over-The-Air (OTA) cho ESP32.
- [**`settings_structure.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/features/settings_structure.md)  
  *Nội dung:* Cấu trúc các mục Cài đặt trên ứng dụng Android.

---

## 🤖 5. Quy Tắc & Prompts AI (`docs/prompts_and_ai/`)

- [**`rules.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/prompts_and_ai/rules.md)  
  *Nội dung:* Quy tắc lập trình & tiêu chuẩn mã nguồn cho AI Agents.
- [**`masterprompt.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/prompts_and_ai/masterprompt.md)  
  *Nội dung:* Master prompt chỉ dẫn công việc.
- [**`promptdeepseek.md`**](file:///d:/Documents/PlatformIO/Tdriver/docs/prompts_and_ai/promptdeepseek.md)  
  *Nội dung:* Các prompts tối ưu hóa riêng cho mô hình DeepSeek.
