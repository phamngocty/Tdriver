# Design Specification: Interactive COM Port & Erase Flash Menu for TYMAP Flasher

- **Date:** 2026-09-29
- **Scope:** `tymap-factory/tools/flasher.py`, `tymap-factory/tools/flash_menu.bat`, `tymap-factory/README.md`
- **Target Platform:** ESP32-C3 (PlatformIO / Windows Python)

---

## 1. Mục Tiêu & Yêu Cầu

Cung cấp cho người dùng một công cụ nạp firmware thông minh, trực quan có menu tương tác để:
1. Quét và hiển thị toàn bộ cổng COM hiện hữu trên máy tính, cho phép chọn cổng theo số thứ tự, gõ trực tiếp tên cổng, hoặc nhấn phím làm mới [R].
2. Tùy chọn xóa sạch bộ nhớ Flash chip (`erase_flash`) trước khi nạp để giải quyết các vấn đề cấu hình NVS cũ hoặc lỗi Boot loop.
3. Cho phép chọn các phân vùng nạp:
   - Nạp trọn gói Dual-Boot (Bootloader + Partitions + Factory + Android + iOS)
   - Nạp lẻ từng phân vùng: Factory Web Portal / Android TYMAP BLE / iOS Sygic BLE
   - Xóa trắng Flash độc lập không nạp
4. Duy trì 100% tương thích ngược với các file `.bat` và CLI commands đã có.

---

## 2. Kiến Trúc & Thiết Kế Module

### 2.1 Cập nhật `flasher.py`
- **`list_available_ports()`**: Sử dụng `serial.tools.list_ports.comports()` để thu thập thông tin thiết bị (Port, Description). Xác định cổng khuyến nghị nếu mô tả chứa từ khóa ("esp", "ch340", "cp210", "uart", v.v.).
- **`select_port_interactive()`**: Hiển thị bảng chọn COM có đánh số `[1]`, `[2]`, phím `[R]` để quét lại, và tự động chọn cổng mặc định khi bấm Enter.
- **`erase_flash(port, baud)`**: Chạy lệnh `esptool.py --chip esp32c3 --port <port> --baud <baud> erase_flash`.
- **`interactive_menu()`**:
  - Bước 1: Gọi `select_port_interactive()` lấy cổng COM.
  - Bước 2: Hiển thị menu chọn tác vụ (Nạp trọn gói, nạp lẻ, hoặc chỉ xóa flash).
  - Bước 3: Nếu chọn nạp firmware, hỏi xác nhận có muốn Erase Flash trước hay không (`y/N`).
  - Bước 4: Thực thi tuần tự (Erase nếu được yêu cầu -> Nạp firmware tương ứng).
- **Hỗ trợ CLI Arguments:**
  - Nếu chạy không tham số: Tự động vào `interactive_menu()`.
  - Nếu có tham số (ví dụ: `python flasher.py all COM3` hoặc `python flasher.py android`): Hoạt động như trước. Hỗ trợ thêm cờ `--erase` nếu muốn xóa flash qua CLI.

### 2.2 Tạo mới `flash_menu.bat`
- File batch khởi chạy nhanh công cụ với môi trường Python của PlatformIO:
  `"C:\Users\phamn\.platformio\penv\Scripts\python.exe" flasher.py` (hoặc fallback `python flasher.py`).
- Đặt `chcp 65001` để hiển thị tiếng Việt UTF-8 chuẩn trên Windows Console.

### 2.3 Cập nhật `README.md`
- Thêm tài liệu hướng dẫn sử dụng `flash_menu.bat` vào mục nạp firmware của dự án.

---

## 3. Tiêu Chí Nghiệm Thu (Success Criteria)
1. Chạy `flasher.py` hiển thị đúng danh sách cổng COM kết nối, nhận diện được cổng ESP32/CH340.
2. Tùy chọn Erase Flash hoạt động trơn tru với `esptool.py erase_flash`.
3. Nạp firmware sau khi Erase Flash (hoặc không Erase Flash) hoàn thành với exit code 0.
4. Các script `.bat` cũ (`flash_all.bat`, `flash_android.bat`, `flash_ios.bat`, `flash_factory.bat`) vẫn hoạt động bình thường không gặp lỗi cú pháp.
