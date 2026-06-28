---
name: codebase-to-overview
description: Quét toàn bộ codebase (bất kỳ nền tảng nào) và tạo file PROJECT_OVERVIEW.md chi tiết. Dùng khi user yêu cầu "tạo tài liệu tổng quan", "overview dự án", "mô tả project", "documentation", "tổng quan codebase", "phân tích dự án", "project overview".
usage: |
  Gọi skill: user nói "tạo overview cho project này"
  Hoặc dùng /create-skill trong chat.
platforms: [copilot, claude, cursor, codex, any]
---

# SKILL: Tạo tài liệu tổng quan dự án (Codebase to Overview)

Bạn là một chuyên gia phân tích mã nguồn đa nền tảng. Hãy quét TOÀN BỘ dự án **{LOẠI_DỰ_ÁN}** có tên là **{TÊN_DỰ_ÁN}** và tạo một file `{TÊN_DỰ_ÁN}_OVERVIEW.md` mô tả chi tiết mọi thành phần của dự án. File này sẽ giúp người khác hiểu rõ dự án mà không cần đọc toàn bộ code.

## THÔNG TIN DỰ ÁN (điền trước khi gửi)

- **Loại dự án:** {LOẠI_DỰ_ÁN}
- **Tên dự án:** {TÊN_DỰ_ÁN}
- **Ngôn ngữ chính:** {NGÔN_NGỮ}
- **Nền tảng / Board / Môi trường:** {NỀN_TẢNG}
- **Framework / SDK:** {FRAMEWORK}
- **Các thư viện chính:** {THƯ_VIỆN}
- **Ngôn ngữ overview:** {NGÔN_NGỮ_OVERVIEW: Tiếng Việt / English}

## YÊU CẦU NỘI DUNG FILE `{TÊN_DỰ_ÁN}_OVERVIEW.md`

### 1. CẤU TRÚC THƯ MỤC & FILE

- Liệt kê đầy đủ cây thư mục (folder structure) với tất cả các file mã nguồn.
- Mỗi file ghi rõ **đường dẫn đầy đủ** và **mục đích ngắn gọn** (1 câu).

### 2. CÁC THÀNH PHẦN CHÍNH (MODULES / CLASSES / FUNCTIONS / COMPONENTS)

- Với mỗi module, class, hàm hoặc component chính:
  - **Tên và vị trí** (file nào).
  - **Chức năng chính** (1-2 câu).
  - **Các thành phần con** (hàm, biến, callback, UI components…).
  - **Cách nó tương tác** với các thành phần khác.
  - **Phần cứng liên quan** (nếu là dự án nhúng).

### 3. DỊCH VỤ NỀN / BACKGROUND PROCESSES (nếu có)

- Tên, loại (Foreground Service, Background Thread, ISR, Timer…).
- Chức năng chính, vòng đời, trigger.

### 4. VÒNG ĐỜI & LUỒNG HOẠT ĐỘNG CHÍNH

- **Firmware:** `setup()` và `loop()`, state machine, interrupt, timer, task.
- **App mobile/web:** Activity/Fragment/App lifecycle, navigation graph, background tasks.
- Vẽ sơ đồ text mô tả luồng hoạt động chính.

### 5. GIAO TIẾP & API (COMMUNICATION / PROTOCOLS)

- Liệt kê tất cả giao thức: BLE (UUID, characteristic), WiFi/HTTP/REST/MQTT, UART/SPI/I2C/CAN, File system/Database.
- Mô tả cách khởi tạo, gửi, nhận dữ liệu cho mỗi giao thức.

### 6. TÍNH NĂNG CHÍNH ĐÃ CÓ

- Mô tả chi tiết từng tính năng: mục đích, thành phần tham gia, luồng dữ liệu, cấu hình.

### 7. THƯ VIỆN & DEPENDENCIES

- Tên, phiên bản, mục đích sử dụng.

### 8. KIẾN TRÚC DỮ LIỆU (DATA MODEL)

- Model, schema, store, state container, database tables, entities, StateFlow/SharedFlow…

### 9. CẤU HÌNH PHẦN CỨNG (nếu là dự án nhúng)

- Sơ đồ chân (pinout), cấu hình build (`platformio.ini`, `board_build.*`, `#define`).

### 10. HƯỚNG DẪN BUILD & UPLOAD / DEPLOY

- Lệnh CLI, biến môi trường, file cấu hình cần thiết.

## LƯU Ý

- **Không bỏ sót** bất kỳ file mã nguồn nào.
- **Không thêm** code mới hay đề xuất. **Chỉ MÔ TẢ** code hiện có.
- File quá dài → mô tả tổng quan, không liệt kê từng dòng.

## WORKFLOW (khi được gọi)

1. Đọc `platformio.ini`, `package.json`, `build.gradle`, `CMakeLists.txt`… để xác định loại dự án.
2. Duyệt toàn bộ cây thư mục, đọc nội dung file chính.
3. Phân tích và tạo `{TÊN_DỰ_ÁN}_OVERVIEW.md`.
4. Lưu file vào thư mục gốc.
