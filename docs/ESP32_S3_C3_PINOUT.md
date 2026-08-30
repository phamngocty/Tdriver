# ESP32-S3 & ESP32-C3 Pinout & Hardware Reference Guide

Tài liệu tra cứu chi tiết sơ đồ chân (Pinout), chức năng ngoại vi, chân nạp/boot (Strapping Pins) và **sơ đồ chân thực tế đang sử dụng trong dự án TYMAP (Tdriver)**.

---

## 📌 SƠ ĐỒ CHÂN ĐANG DÙNG TRONG DỰ ÁN TYMAP

### 1. ESP32-S3 (HUD Màn hình tròn GC9A01 240x240 - `TYMAP/firmware/esp32_s3_gc9a01`)

* **Board:** ESP32-S3-DevKitC-1 (hoặc S3 Zero / Mini)
* **Màn hình:** GC9A01 SPI tròn 1.28 inch (240x240)
* **Thư viện:** TFT_eSPI (Giao tiếp SPI tốc độ cao 80MHz)

| Chức năng | Chân trên Module / Màn hình | Chân GPIO ESP32-S3 | Cấu hình trong Code / `platformio.ini` |
| :--- | :--- | :--- | :--- |
| **SPI SCLK (Clock)** | `SCL` / `CLK` | **GPIO 13** | `-D TFT_SCLK=13` |
| **SPI MOSI (Data)** | `SDA` / `DIN` | **GPIO 12** | `-D TFT_MOSI=12` |
| **Data / Command** | `DC` | **GPIO 11** | `-D TFT_DC=11` |
| **Chip Select** | `CS` | **GPIO 10** | `-D TFT_CS=10` |
| **Reset** | `RST` / `RES` | **GPIO 9** | `-D TFT_RST=9` |
| **Backlight (Đèn nền)** | `BL` / `BLK` | **GPIO 14** | `-D TFT_BL=14` (Bật HIGH) |
| **Nút MODE (Menu/Chế độ)** | Nút bấm chính | **GPIO 1** | `#define MODE_BTN 1` (Kéo xuống GND) |
| **Nút ZOOM (Phóng to/Thu nhỏ)** | Nút bấm phụ | **GPIO 2** | `#define ZOOM_BTN 2` (Kéo xuống GND) |
| **Cảm biến điện áp bình Ắc quy** | Cầu phân áp pin | **GPIO 3** (ADC1_CH2) | `#define BAT_ADC 3` (Cầu trở R1=100k, R2=10k, tỷ lệ x11.0) |
| **Nạp code & Debug Serial** | Type-C Native USB | **GPIO 19** (D-), **GPIO 20** (D+) | `-D ARDUINO_USB_CDC_ON_BOOT=1` |

---

### 2. ESP32-C3 (HUD Màn hình OLED SSD1306 128x64 - `TYMAP/firmware/esp32_c3_oled`)

* **Board:** ESP32-C3-DevKitM-1 (hoặc ESP32-C3 SuperMini)
* **Màn hình:** OLED SSD1306 0.96 inch 128x64 (I2C)
* **Thư viện:** U8g2lib

| Chức năng | Chân trên Module / Màn hình | Chân GPIO ESP32-C3 | Cấu hình trong Code / `platformio.ini` |
| :--- | :--- | :--- | :--- |
| **I2C SDA (Data)** | `SDA` | **GPIO 6** | `-D OLED_SDA=6` |
| **I2C SCL (Clock)** | `SCL` | **GPIO 7** | `-D OLED_SCL=7` |
| **Nút MODE (Menu/Chế độ)** | Nút bấm chính | **GPIO 2** | `#define MODE_BTN 2` (Kéo xuống GND) |
| **Nút ZOOM (Phóng to/Thu nhỏ)** | Nút bấm phụ | **GPIO 3** | `#define ZOOM_BTN 3` (Kéo xuống GND) |
| **Cảm biến điện áp bình Ắc quy** | Cầu phân áp pin | **GPIO 0** (ADC1_CH0) | `#define BAT_ADC 0` (Cầu trở R1=100k, R2=10k, tỷ lệ x11.0) |
| **Nạp code & Debug Serial** | Type-C Native USB | **GPIO 18** (D-), **GPIO 19** (D+) | `-D ARDUINO_USB_CDC_ON_BOOT=1` |

---

## 1. ESP32-C3 (RISC-V Single-Core 160MHz)

### 1.1. Tổng quan GPIO ESP32-C3
ESP32-C3 có tổng cộng 22 chân GPIO vật lý (`GPIO0` đến `GPIO21`).

| GPIO | Default / Function | ADC | Strapping / Lưu ý quan trọng | Khuyến nghị sử dụng |
| :--- | :--- | :--- | :--- | :--- |
| **GPIO0** | XTAL_32K_P | ADC1_CH0 | Chân thạch anh 32.768kHz (RTC) / ADC | Dùng tự do (trừ khi gắn thạch anh RTC) |
| **GPIO1** | XTAL_32K_N | ADC1_CH1 | Chân thạch anh 32.768kHz (RTC) / ADC | Dùng tự do (trừ khi gắn thạch anh RTC) |
| **GPIO2** | FSPIQ | ADC1_CH2 | **Strapping Pin** (Phải kéo HIGH khi khởi động SPI boot) | Dùng được, cẩn thận mạch ngoài không kéo LOW lúc boot |
| **GPIO3** | - | ADC1_CH3 | Chân ADC / GPIO đa dụng | **Rất tốt** (I2C, SPI, Button, Sensor) |
| **GPIO4** | MTMS | ADC1_CH4 | JTAG / ADC | **Rất tốt** |
| **GPIO5** | MTDI | ADC2_CH0 | JTAG / ADC2 (Không dùng ADC2 khi bật Wi-Fi) | **Rất tốt** (Digital I/O) |
| **GPIO6** | MTCK | - | JTAG / FSPICLK | **Rất tốt** |
| **GPIO7** | MTDO | - | JTAG / FSPID | **Rất tốt** |
| **GPIO8** | - | - | **Strapping Pin** (Kiểm soát bootlog và download mode) | Dùng được (Thường gắn LED onboard, có pull-up) |
| **GPIO9** | - | - | **Strapping Pin (BOOT Pin)**: Kéo LOW lúc reset để vào Download Mode | Thường gắn nút BOOT (Có pull-up nội 10k/45k) |
| **GPIO10**| FSPICS0 | - | Chân SPI CS mặc định | **Rất tốt** |
| **GPIO11**| VDD_SPI | - | Nguồn cấp Flash nội (trên chip/module) | **CẤM DÙNG** (Nội bộ module) |
| **GPIO12**| SPIHD | - | Flash nội (Quad SPI HD) | **CẤM DÙNG** |
| **GPIO13**| SPIWP | - | Flash nội (Quad SPI WP) | **CẤM DÙNG** |
| **GPIO14**| SPICS0 | - | Flash nội (Quad SPI CS) | **CẤM DÙNG** |
| **GPIO15**| SPICLK | - | Flash nội (Quad SPI CLK) | **CẤM DÙNG** |
| **GPIO16**| SPID | - | Flash nội (Quad SPI MOSI) | **CẤM DÙNG** |
| **GPIO17**| SPIQ | - | Flash nội (Quad SPI MISO) | **CẤM DÙNG** |
| **GPIO18**| USB_D- | - | **Native USB-JTAG D-** (Nạp code & Debug USB) | Không dùng làm GPIO nếu cần USB CDC/JTAG |
| **GPIO19**| USB_D+ | - | **Native USB-JTAG D+** (Nạp code & Debug USB) | Không dùng làm GPIO nếu cần USB CDC/JTAG |
| **GPIO20**| U0RXD | - | **UART0 RX** (Chân nạp qua chip nạp ngoài / Serial log) | Dùng cho Serial Monitor / Nạp UART |
| **GPIO21**| U0TXD | - | **UART0 TX** (Chân nạp qua chip nạp ngoài / Serial log) | Dùng cho Serial Monitor / Nạp UART |

---

### 1.2. ESP32-C3 Strapping Pins (Chân cấu hình khởi động)

| Pin | Mặc định nội | Boot SPI Flash (Chạy code bình thường) | Download Mode (Nạp UART/USB) |
| :--- | :--- | :--- | :--- |
| **GPIO2** | Pull-up | **1** (HIGH) | 1 (HIGH) |
| **GPIO8** | Pull-up | Không ảnh hưởng (Nên giữ HIGH) | **1** (HIGH) |
| **GPIO9** | Pull-up | **1** (HIGH) | **0** (LOW - Nhấn giữ nút BOOT) |

> [!WARNING]
> Không gắn mạch kéo LOW cố định trên **GPIO2**, **GPIO8**, hoặc **GPIO9**, nếu không chip sẽ không thể boot bình thường từ Flash hoặc không vào được chế độ nạp firmware!

---

### 1.3. Cấu hình ngoại vi đề xuất cho ESP32-C3

- **I2C mặc định (khuyên dùng):**
  - `SDA`: **GPIO8** hoặc **GPIO4**
  - `SCL`: **GPIO9** hoặc **GPIO5** (Nếu dùng GPIO9 làm I2C, cẩn thận tín hiệu lúc reset)
  - Hoặc cấu hình tự do qua GPIO Matrix: `Wire.begin(SDA_PIN, SCL_PIN);`
- **SPI (FSPI):**
  - `SCK`: **GPIO6**
  - `MISO`: **GPIO2**
  - `MOSI`: **GPIO7**
  - `CS`: **GPIO10** (hoặc GPIO3)
- **ADC khả dụng:**
  - ADC1 (Dùng an toàn cùng Wi-Fi): `GPIO0`, `GPIO1`, `GPIO2`, `GPIO3`, `GPIO4`
  - ADC2 (Bị ngắt khi Wi-Fi hoạt động): `GPIO5`

---

## 2. ESP32-S3 (Xtensa LX7 Dual-Core 240MHz, Hỗ trợ Vector Instructions / AI)

### 2.1. Tổng quan GPIO ESP32-S3
ESP32-S3 hỗ trợ tối đa 45 chân GPIO (`GPIO0` - `GPIO21`, `GPIO26` - `GPIO48`).

| GPIO | Touch | ADC | Chức năng mặc định | Strapping / Lưu ý quan trọng | Khuyến nghị sử dụng |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **GPIO0** | - | - | **BOOT Pin** | **Strapping Pin** (Kéo LOW để vào Download Boot) | Nút BOOT / GPIO đa dụng |
| **GPIO1** | TOUCH1 | ADC1_CH0 | - | - | **Rất tốt** (ADC / Touch / General) |
| **GPIO2** | TOUCH2 | ADC1_CH1 | - | - | **Rất tốt** (ADC / Touch / General) |
| **GPIO3** | TOUCH3 | ADC1_CH2 | - | **Strapping Pin** (JTAG config) | Dùng tốt (Tránh kéo LOW cố định) |
| **GPIO4** | TOUCH4 | ADC1_CH3 | - | - | **Rất tốt** |
| **GPIO5** | TOUCH5 | ADC1_CH4 | - | - | **Rất tốt** |
| **GPIO6** | TOUCH6 | ADC1_CH5 | - | - | **Rất tốt** |
| **GPIO7** | TOUCH7 | ADC1_CH6 | - | - | **Rất tốt** |
| **GPIO8** | TOUCH8 | ADC1_CH7 | I2C SDA (DevKit) | - | **Rất tốt** (Mặc định I2C SDA) |
| **GPIO9** | TOUCH9 | ADC1_CH8 | I2C SCL (DevKit) | - | **Rất tốt** (Mặc định I2C SCL) |
| **GPIO10**| TOUCH10| ADC1_CH9 | - | - | **Rất tốt** |
| **GPIO11**| TOUCH11| ADC2_CH0 | FSPI / Octal D4 | Bị ảnh hưởng khi bật Wi-Fi (nếu dùng ADC) | **Rất tốt** (Digital) |
| **GPIO12**| TOUCH12| ADC2_CH1 | FSPI / Octal D5 | Bị ảnh hưởng khi bật Wi-Fi (nếu dùng ADC) | **Rất tốt** (Digital) |
| **GPIO13**| TOUCH13| ADC2_CH2 | FSPI / Octal D6 | Bị ảnh hưởng khi bật Wi-Fi (nếu dùng ADC) | **Rất tốt** (Digital) |
| **GPIO14**| TOUCH14| ADC2_CH3 | FSPI / Octal D7 | Bị ảnh hưởng khi bật Wi-Fi (nếu dùng ADC) | **Rất tốt** (Digital) |
| **GPIO15**| - | ADC2_CH4 | XTAL_32K_P | Chân thạch anh RTC | Dùng tự do (nếu không gắn 32k) |
| **GPIO16**| - | ADC2_CH5 | XTAL_32K_N | Chân thạch anh RTC | Dùng tự do (nếu không gắn 32k) |
| **GPIO17**| - | ADC2_CH6 | UART1 TX (tùy board)| - | **Rất tốt** |
| **GPIO18**| - | ADC2_CH7 | UART1 RX (tùy board)| - | **Rất tốt** |
| **GPIO19**| - | ADC2_CH8 | **USB_D-** | **Native USB PHY D-** | Chân USB OTG / Nạp trực tiếp |
| **GPIO20**| - | ADC2_CH9 | **USB_D+** | **Native USB PHY D+** | Chân USB OTG / Nạp trực tiếp |
| **GPIO21**| - | - | - | - | **Rất tốt** |
| **GPIO26**| - | - | SPI Flash CS | Dùng cho Flash nội | **CẤM DÙNG** (Module WROOM) |
| **GPIO27**| - | - | SPI Flash CLK | Dùng cho Flash nội | **CẤM DÙNG** (Module WROOM) |
| **GPIO28**| - | - | SPI Flash MOSI | Dùng cho Flash nội | **CẤM DÙNG** (Module WROOM) |
| **GPIO29**| - | - | SPI Flash MISO | Dùng cho Flash nội | **CẤM DÙNG** (Module WROOM) |
| **GPIO30**| - | - | SPI Flash WP | Dùng cho Flash nội | **CẤM DÙNG** (Module WROOM) |
| **GPIO31**| - | - | SPI Flash HOLD| Dùng cho Flash nội | **CẤM DÙNG** (Module WROOM) |
| **GPIO32**| - | - | SPI Flash/PSRAM | Dùng cho Flash/PSRAM | **CẤM DÙNG** (Module WROOM) |
| **GPIO33**| - | - | **Octal Flash/PSRAM** | Dùng cho Octal SPI (S3-WROOM-2 / N8R8 / N16R8) | **CẤM DÙNG nếu có Octal PSRAM** |
| **GPIO34**| - | - | **Octal Flash/PSRAM** | Dùng cho Octal SPI (S3-WROOM-2 / N8R8 / N16R8) | **CẤM DÙNG nếu có Octal PSRAM** |
| **GPIO35**| - | - | **Octal Flash/PSRAM** | Dùng cho Octal SPI (S3-WROOM-2 / N8R8 / N16R8) | **CẤM DÙNG nếu có Octal PSRAM** |
| **GPIO36**| - | - | **Octal Flash/PSRAM** | Dùng cho Octal SPI (S3-WROOM-2 / N8R8 / N16R8) | **CẤM DÙNG nếu có Octal PSRAM** |
| **GPIO37**| - | - | **Octal Flash/PSRAM** | Dùng cho Octal SPI (S3-WROOM-2 / N8R8 / N16R8) | **CẤM DÙNG nếu có Octal PSRAM** |
| **GPIO38**| - | - | FSPI / RGB LED | Thường nối WS2812 RGB LED trên DevKit | **Rất tốt** |
| **GPIO39**| - | - | MTCK | JTAG / GPIO | **Rất tốt** |
| **GPIO40**| - | - | MTDO | JTAG / GPIO | **Rất tốt** |
| **GPIO41**| - | - | MTDI | JTAG / GPIO | **Rất tốt** |
| **GPIO42**| - | - | MTMS | JTAG / GPIO | **Rất tốt** |
| **GPIO43**| - | - | **U0TXD** | **UART0 TX** (Serial Log / Nạp ngoài) | UART Debug / Nạp |
| **GPIO44**| - | - | **U0RXD** | **UART0 RX** (Serial Log / Nạp ngoài) | UART Debug / Nạp |
| **GPIO45**| - | - | **VDD_SPI config**| **Strapping Pin** (Điện áp VDD_SPI) | Cẩn thận không kéo sai mức điện áp |
| **GPIO46**| - | - | **Boot control** | **Strapping Pin** (ROM messages) | Chỉ Input, không pull-up nội |
| **GPIO47**| - | - | SPICLK_P / GPIO | - | **Rất tốt** |
| **GPIO48**| - | - | SPICLK_N / RGB LED | Thường nối RGB LED trên nhiều bản S3 Mini | **Rất tốt** |

---

### 2.2. ESP32-S3 Strapping Pins (Chân cấu hình khởi động)

| Pin | Mặc định | Chức năng | Khuyến nghị thiết kế phần cứng |
| :--- | :--- | :--- | :--- |
| **GPIO0** | Pull-up | **0: Download Mode**, **1: SPI Boot (Bình thường)** | Nối nút bấm xuống GND qua tụ 100nF để chống rung |
| **GPIO3** | Pull-up | JTAG Signal routing | Để hở hoặc kéo HIGH |
| **GPIO45**| Pull-down| Điện áp VDD_SPI (0: 3.3V, 1: 1.8V) | **Giữ mức 0 (LOW)** để cấp nguồn 3.3V cho Flash chuẩn |
| **GPIO46**| Pull-down| Kiểm soát Boot log ROM | Để hở hoặc mức 0 khi khởi động |

> [!CAUTION]
> **LƯU Ý ĐẶC BIỆT VỀ PSRAM OCTAL (OPI PSRAM):**
> Các module phổ biến như `ESP32-S3-WROOM-1-N8R8`, `N16R8`, `ESP32-S3-WROOM-2` sử dụng **Octal SPI** tốc độ cao. Các chân **`GPIO33` đến `GPIO37`** được kết nối trực tiếp với chip PSRAM/Flash bên trong vỏ module.
> **TUYỆT ĐỐI KHÔNG** nối dây ngoại vi vào `GPIO33, 34, 35, 36, 37` trên các bản dùng Octal PSRAM vì sẽ gây crash/treo CPU ngay lập tức!

---

### 2.3. Cấu hình ngoại vi đề xuất cho ESP32-S3

- **Giao tiếp I2C:**
  - `SDA`: **GPIO8** (hoặc GPIO1, GPIO4, GPIO17)
  - `SCL`: **GPIO9** (hoặc GPIO2, GPIO5, GPIO18)
- **Giao tiếp SPI (SPI2 / FSPI cho màn hình LCD / ST7789 / ILI9341 / GC9A01):**
  - `MOSI`: **GPIO11** (hoặc GPIO35 trên bản Quad-SPI, hoặc GPIO13/15)
  - `MISO`: **GPIO13**
  - `SCK`: **GPIO12**
  - `CS`: **GPIO10**
  - `DC`: **GPIO4** (hoặc chân GPIO rảnh bất kỳ)
  - `RST`: **GPIO5** (hoặc chân GPIO rảnh bất kỳ)
- **USB Native CDC / JTAG (Nạp trực tiếp qua cổng Type-C không cần chip CH340/CP2102):**
  - `D-`: **GPIO19**
  - `D+`: **GPIO20**
- **UART0 (Debug / Serial Monitor):**
  - `TX`: **GPIO43**
  - `RX`: **GPIO44**
- **Touch Inputs:**
  - 14 kênh Touch: `GPIO1` đến `GPIO14` (Hỗ trợ đánh thức từ Deep Sleep)

---

## 3. Bảng so sánh nhanh ESP32-C3 vs ESP32-S3

| Tiêu chí | ESP32-C3 | ESP32-S3 |
| :--- | :--- | :--- |
| **Kiến trúc CPU** | 32-bit RISC-V Single-Core @ 160MHz | 32-bit Xtensa Dual-Core @ 240MHz (Có Vector AI) |
| **Số chân GPIO khả dụng** | ~15 chân (trong tổng 22 GPIO) | ~36 chân (Quad Flash) / ~31 chân (Octal PSRAM) |
| **Kênh ADC** | ADC1 (5 kênh), ADC2 (1 kênh) | ADC1 (10 kênh), ADC2 (10 kênh) |
| **Cảm ứng chạm (Touch)** | ❌ Không có |  14 kênh (GPIO1 - GPIO14) |
| **Native USB / OTG** | USB Serial/JTAG (CDC + JTAG nạp code) | USB Serial/JTAG + USB 1.1 OTG (Host/Device) |
| **Giao tiếp Camera (DVP)** | ❌ Không hỗ trợ |  Hỗ trợ chuẩn 8/16-bit DVP (OV2640, OV5640) |
| **Giao tiếp Màn hình LCD** | SPI, I2C | SPI, I2C, RGB song song, 8/16-bit 8080 LCD |
| **PSRAM** | ❌ Không hỗ trợ |  Hỗ trợ lên tới 8MB/16MB (Quad hoặc Octal) |
| **Ứng dụng tiêu biểu** | Cảm biến IoT, Công tắc thông minh, BLE Gateway | Thiết bị HUD/Màn hình lớn, Xử lý ảnh AI, Nhận diện giọng nói |

---

## 4. Nguyên tắc thiết kế mạch & Sử dụng chân an toàn

1. **Khử nhiễu ADC:** Các chân ADC1 (`GPIO0-4` trên C3, `GPIO1-10` trên S3) luôn hoạt động ổn định khi bật Wi-Fi. Tránh dùng ADC2 khi Wi-Fi đang truyền/nhận dữ liệu.
2. **Kéo trở Pull-up / Pull-down:**
   - Tránh gắn trở kéo ngoài trên các chân Strapping nếu không hiểu rõ mức logic boot.
   - Các bus I2C luôn cần trở kéo ngoài (4.7kΩ hoặc 10kΩ) lên 3.3V để đạt tốc độ cao và ổn định.
3. **Nguồn cấp 3.3V:** ESP32-S3 và C3 tiêu thụ dòng tức thời lên tới 500mA khi phát sóng Wi-Fi. Luôn bố trí tụ lọc nguồn **10µF + 100nF** sát chân VDD của chip/module.
4. **Nút Reset (EN):** Luôn gắn mạch RC tiêu chuẩn: trở 10kΩ kéo lên 3.3V và tụ 1µF / 100nF nối đất để tránh hiện tượng sụt nguồn làm chip reset bất thường.
