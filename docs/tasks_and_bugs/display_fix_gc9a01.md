# Fix GC9A01 — Hướng dẫn cấu hình ESP32-S3 + TFT_eSPI

## ✅ Môi trường đã test thành công

| Thành phần        | Giá trị                          |
| ----------------- | -------------------------------- |
| Board             | ESP32-S3 DevKitC-1               |
| Platform          | `platformio/espressif32 @ 6.6.0` |
| Framework         | Arduino                          |
| Flash             | 4MB (QIO mode)                   |
| PSRAM             | Có (bật `BOARD_HAS_PSRAM`)       |
| Màn hình          | GC9A01 240×240, SPI              |
| Thư viện          | TFT_eSPI 2.5.43                  |

---

## ⚠️ Vấn đề gặp phải

**Triệu chứng:** Board boot loop liên tục, không có output Serial từ code, chỉ thấy boot loader log lặp lại.

```
rst:0x3 (RTC_SW_SYS_RST),boot:0x2b (SPI_FAST_FLASH_BOOT)
Saved PC:0x403cdb0a
...
entry 0x403c98d0
```

**Nguyên nhân gốc rễ (đã tìm ra qua debug):**

1. **Platform version quá mới** — `espressif32 @ 6.12.0` / `7.0.1` dùng Arduino core 3.0.7 có bug trên ESP32-S3 khiến board crash ngay khi khởi tạo. **Fix: pin về `@ 6.6.0`** (Arduino core 3.0.3 ổn định).

2. **Thiếu PSRAM config** — ESP32-S3 DevKitC-1 có PSRAM, nếu không bật (`BOARD_HAS_PSRAM`) thì Arduino core crash khi init memory. **Fix: thêm `-D BOARD_HAS_PSRAM`**.

3. **Thiếu partition table** — Nếu không chỉ định, có thể dùng sai layout dẫn đến crash. **Fix: thêm `board_build.partitions = default.csv`**.

4. **Thiếu core affinity** — Cần pin Arduino tasks vào core 1 để tránh xung đột. **Fix: thêm `ARDUINO_RUNNING_CORE=1` và `ARDUINO_EVENT_RUNNING_CORE=1`**.

---

## 🔧 platformio.ini — Cấu hình chuẩn

```ini
[env:esp32-s3-devkitc-1]
platform = platformio/espressif32 @ 6.6.0
board = esp32-s3-devkitc-1
framework = arduino
monitor_speed = 115200

board_build.flash_mode = qio
board_upload.flash_size = 4MB
board_build.partitions = default.csv

build_flags =
	-D BOARD_HAS_PSRAM
	-D ARDUINO_USB_CDC_ON_BOOT=1
	-D ARDUINO_RUNNING_CORE=1
	-D ARDUINO_EVENT_RUNNING_CORE=1
	-D USER_SETUP_LOADED=1
	-D GC9A01_DRIVER
	-D TFT_WIDTH=240
	-D TFT_HEIGHT=240
	-D TFT_MISO=-1
	-D TFT_MOSI=11
	-D TFT_SCLK=12
	-D TFT_CS=13
	-D TFT_DC=9
	-D TFT_RST=10
	-D SPI_FREQUENCY=40000000
	-D USE_HSPI_PORT=1
	-D LOAD_GLCD=1
	-D LOAD_FONT2=1
	-D LOAD_FONT4=1

lib_deps =
	bodmer/TFT_eSPI @ ^2.5.43
```

---

## 🔌 Wiring GC9A01 — ESP32-S3

| GC9A01 | ESP32-S3 | Ghi chú          |
| ------ | -------- | ---------------- |
| VCC    | 3.3V     |                  |
| GND    | GND      |                  |
| SCL    | GPIO 12  | SPI Clock        |
| SDA    | GPIO 11  | SPI MOSI         |
| RST    | GPIO 10  | Reset            |
| DC     | GPIO 9   | Data/Command     |
| CS     | GPIO 13  | Chip Select      |
| BL     | 3.3V     | Backlight (PWM)  |

> **Lưu ý:** `TFT_MISO=-1` vì GC9A01 chỉ có 1 data line (MOSI), không có MISO.

---

## 🚀 Các bước khi tạo project mới

1. Copy `platformio.ini` mẫu ở trên
2. Kiểm tra flash size thật của board (`board_upload.flash_size`)
3. Nếu board có PSRAM → thêm `BOARD_HAS_PSRAM`
4. Nếu board KHÔNG có PSRAM → bỏ dòng `BOARD_HAS_PSRAM`
5. Chạy build + upload:

```bash
pio run --target fullclean && pio run --target upload
```

6. Mở Serial Monitor để kiểm tra log

---

## 🐛 Checklist khi gặp boot loop

| Bước | Kiểm tra                                  | Fix                                      |
| ---- | ----------------------------------------- | ---------------------------------------- |
| 1    | Flash size đúng?                          | Sửa `board_upload.flash_size`            |
| 2    | Board có PSRAM không?                     | Thêm/bỏ `BOARD_HAS_PSRAM`                |
| 3    | Platform version quá mới?                 | Pin về `@ 6.6.0`                         |
| 4    | `rst:0x3` + `Saved PC` lặp lại?          | Crash trong init → check PSRAM + core    |
| 5    | `Detected size(X) smaller than binary(Y)` | Sửa `board_upload.flash_size` cho khớp   |
| 6    | `Core dump flash config is corrupted`     | Chạy `pio run --target erase` trước khi upload |

---

## 📌 Lưu ý quan trọng

- **Platform version:** `6.6.0` đã test ổn định. Các version `6.12.0` / `7.0.1` có thể gây boot loop trên ESP32-S3.
- **USB CDC:** Nên để `ARDUINO_USB_CDC_ON_BOOT=1` với ESP32-S3 native USB, hoặc tắt nếu dùng UART bridge.
- **SPI Frequency:** GC9A01 chạy ổn ở 40MHz. Nếu có nhiễu thì hạ xuống 27MHz.
- **invertDisplay:** Một số màn GC9A01 cần `tft.invertDisplay(true)` để hiển thị đúng màu.
- **`pio run --target erase`:** Luôn chạy erase khi đổi platform version để tránh core dump cũ gây conflict.
