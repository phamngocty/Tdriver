# Interactive COM Port & Erase Flash Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cung cấp menu console tương tác hỗ trợ quét chọn cổng COM linh hoạt, tùy chọn Erase Flash trước khi nạp và chọn phân vùng nạp firmware trong `tymap-factory/tools`.

**Architecture:** Mở rộng `tymap-factory/tools/flasher.py` với các hàm phụ trợ quét thiết bị serial, xử lý tương tác CLI, bổ sung cờ lệnh `--erase` và lệnh `erase_flash` qua esptool. Tạo script Windows batch `flash_menu.bat` và cập nhật tài liệu `README.md`.

**Tech Stack:** Python 3, PySerial (`serial.tools.list_ports`), `esptool.py`, Windows Batch Script.

---

### Task 1: Cập nhật `flasher.py` hỗ trợ chọn cổng COM tương tác và Erase Flash

**Files:**
- Modify: `c:/Users/phamn/Documents/PlatformIO/Tdriver/tymap-factory/tools/flasher.py`

- [ ] **Step 1: Thêm hàm liệt kê cổng COM và chọn cổng tương tác `select_port_interactive()`**
- [ ] **Step 2: Thêm hàm xóa bộ nhớ chip `erase_flash(port, baud)`**
- [ ] **Step 3: Thêm hàm điều hướng menu tương tác `interactive_menu()`**
- [ ] **Step 4: Cập nhật hàm `main()` để tự động kích hoạt `interactive_menu()` khi không có tham số**
- [ ] **Step 5: Kiểm tra tính hợp lệ cú pháp Python của file `flasher.py`**

---

### Task 2: Tạo script batch tiện ích `flash_menu.bat`

**Files:**
- Create: `c:/Users/phamn/Documents/PlatformIO/Tdriver/tymap-factory/tools/flash_menu.bat`

- [ ] **Step 1: Viết script `flash_menu.bat` với UTF-8 và đường dẫn Python PlatformIO**
- [ ] **Step 2: Kiểm tra khả năng khởi chạy script**

---

### Task 3: Cập nhật tài liệu hướng dẫn `README.md`

**Files:**
- Modify: `c:/Users/phamn/Documents/PlatformIO/Tdriver/tymap-factory/README.md`

- [ ] **Step 1: Bổ sung mô tả file `flash_menu.bat` vào sơ đồ cấu trúc dự án**
- [ ] **Step 2: Thêm hướng dẫn sử dụng menu tương tác vào Mục 5**

---

### Task 4: Kiểm thử và xác thực toàn diện (Verification)

- [ ] **Step 1: Chạy thử nghiệm kiểm tra parser và import của `flasher.py`**
- [ ] **Step 2: Kiểm tra tính tương thích ngược của các lệnh CLI cũ (`all`, `factory`, `android`, `ios`)**
