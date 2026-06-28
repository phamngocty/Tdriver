---
name: scan-projet
description: Describe what this skill does and when to use it. Include keywords that help agents identify relevant tasks.
---

Bạn là một chuyên gia phân tích mã nguồn đa nền tảng. Hãy quét TOÀN BỘ dự án [LOẠI_DỰ_ÁN] có tên là **[TÊN_DỰ_ÁN]** và tạo một file `[TÊN_DỰ_ÁN]_OVERVIEW.md` mô tả chi tiết mọi thành phần của dự án. File này sẽ giúp người khác hiểu rõ dự án mà không cần đọc toàn bộ code.

## THÔNG TIN DỰ ÁN

- **Loại dự án:** [LOẠI_DỰ_ÁN] (VD: Firmware ESP32 Arduino/PlatformIO, Android Kotlin app, React web app…)
- **Tên dự án:** [TÊN_DỰ_ÁN]
- **Ngôn ngữ chính:** [NGÔN_NGỮ] (VD: C++ với Arduino framework, Kotlin, TypeScript…)
- **Nền tảng / Board / Môi trường:** [NỀN_TẢNG] (VD: ESP32-S3 DevKitC-1, Android 14, Vercel, Docker…)
- **Framework / SDK:** [FRAMEWORK] (VD: Arduino, ESP-IDF, PlatformIO, React, Spring Boot…)
- **Các thư viện chính:** [THƯ_VIỆN] (VD: TFT_eSPI, NimBLE-Arduino, JPEGDEC, osmdroid, OkHttp…)

## YÊU CẦU NỘI DUNG FILE `[TÊN_DỰ_ÁN]_OVERVIEW.md`

### 1. CẤU TRÚC THƯ MỤC & FILE

- Liệt kê đầy đủ cây thư mục (folder structure) với tất cả các file mã nguồn (.ino, .cpp, .h, .kt, .swift, .py, .jsx, .tsx, .yaml, .json, .xml, .gradle, CMakeLists.txt, .toml, .lock…).
- Mỗi file ghi rõ **đường dẫn đầy đủ** và **mục đích ngắn gọn** (1 câu).

### 2. CÁC THÀNH PHẦN CHÍNH (MODULES / CLASSES / FUNCTIONS / COMPONENTS)

- Với mỗi module, class, hàm hoặc component chính:
  - **Tên và vị trí** (file nào).
  - **Chức năng chính** (1-2 câu).
  - **Các thành phần con** (hàm, biến toàn cục, callback, UI components…).
  - **Cách nó tương tác** với các thành phần khác (gọi hàm, chia sẻ dữ liệu, interrupt, queue, state management, API calls…).
  - **Phần cứng liên quan** (nếu là dự án nhúng): chân GPIO, giao tiếp SPI/I2C/UART, cảm biến, actuator…

### 3. DỊCH VỤ NỀN / BACKGROUND PROCESSES (nếu có)

- Với mỗi service, worker, cron job, interrupt handler, timer task…:
  - Tên, loại (Foreground Service, Background Thread, ISR…).
  - Chức năng chính.
  - Các manager/engine mà nó điều phối.
  - Vòng đời hoặc trigger (onStartCommand, onDestroy, interval, event-driven…).

### 4. VÒNG ĐỜI & LUỒNG HOẠT ĐỘNG CHÍNH (MAIN LOOP / APP STATES)

- **Đối với firmware:** Mô tả `setup()` và `loop()`, các trạng thái (state machine), ngắt (interrupt), timer, task…
- **Đối với app:** Mô tả vòng đời Activity/Service/App, navigation graph, background tasks…
- Vẽ sơ đồ text mô tả luồng hoạt động chính.

### 5. GIAO TIẾP & API (COMMUNICATION / PROTOCOLS)

- Liệt kê tất cả các giao thức, interface, endpoint đang dùng:
  - **BLE:** Service UUID, Characteristic UUID, hướng, định dạng dữ liệu, MTU…
  - **WiFi / HTTP / REST / GraphQL / MQTT:** Endpoint, method, payload, headers, authentication…
  - **UART / SPI / I2C / CAN:** Chân kết nối, tốc độ, thiết bị đầu cuối, protocol riêng…
  - **File system / Database:** LittleFS, SPIFFS, Room, SQLite, Firestore, PostgreSQL…
- Với mỗi giao thức, mô tả cách khởi tạo, gửi, nhận dữ liệu.

### 6. TÍNH NĂNG CHÍNH ĐÃ CÓ

- Mô tả chi tiết từng tính năng (feature) của dự án:
  - Mục đích.
  - Các thành phần tham gia (file, class, hàm).
  - Luồng dữ liệu / tín hiệu.
  - Cấu hình liên quan (nếu có).

### 7. THƯ VIỆN & DEPENDENCIES

- Liệt kê tất cả thư viện / package / framework:
  - Tên, phiên bản (nếu có).
  - Mục đích sử dụng trong dự án.

### 8. KIẾN TRÚC DỮ LIỆU (DATA MODEL)

- Mô tả các model, schema, store, state container quan trọng (database tables, Room entities, SharedPreferences keys, StateFlow, Redux store, context variables…).
- Các class/struct quan trọng và mối quan hệ.

### 9. CẤU HÌNH PHẦN CỨNG (nếu là dự án nhúng)

- Sơ đồ chân (pinout) chi tiết: GPIO, chức năng, thiết bị ngoại vi.
- Cấu hình build: PlatformIO (`platformio.ini`), Arduino (`board_build.*`), các `#define` quan trọng.
- Yêu cầu về nguồn, điện áp, xung clock, điện trở kéo, tụ lọc…

### 10. HƯỚNG DẪN BUILD & UPLOAD / DEPLOY (nếu có)

- Các bước để build, upload, deploy, debug.
- Các lệnh CLI (VD: `pio run --target upload`, `npm run build`, `docker compose up`…).
- Các biến môi trường hoặc file cấu hình cần thiết.

## ĐỊNH DẠNG FILE

- Viết bằng Markdown (.md).
- Ngôn ngữ: [TIẾNG_VIỆT hoặc ENGLISH] (tùy bạn chọn).
- Có thể chèn code block ngắn để minh họa nếu cần.
- Đặt tên file: `[TÊN_DỰ_ÁN]_OVERVIEW.md`, lưu vào thư mục gốc của dự án.

## LƯU Ý

- **Không bỏ sót** bất kỳ file mã nguồn nào.
- **Không thêm** bất kỳ code mới hay đề xuất gì. **Chỉ MÔ TẢ** code hiện có.
- Nếu gặp file quá dài, chỉ mô tả tổng quan, không cần liệt kê từng dòng.
<!-- Tip: Use /create-skill in chat to generate content with agent assistance -->

Define the functionality provided by this skill, including detailed instructions and examples
