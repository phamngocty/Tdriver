# TYMAP Dual-Boot System (ESP32-C3)
### Hệ Thống Chuyển Đổi Hệ Điều Hành Thông Minh: Android (TYMAP BLE) & iOS (Sygic BLE)

---

## 1. Cấu Trúc Dự Án

```
tymap-factory/
├── platformio.ini         # Cấu hình biên dịch PlatformIO cho Factory Web Portal
├── partitions.csv         # Bảng phân vùng Flash 4MB (Dual-boot 2 phân vùng OTA)
├── src/
│   └── main.cpp           # Mã nguồn Web Portal (Wi-Fi AP, DNS Captive Portal, U8g2)
├── binaries/              # Thư mục chứa toàn bộ firmware hoàn chỉnh
│   ├── bootloader.bin     # Bootloader ESP32-C3 (Offset 0x0)
│   ├── partitions.bin     # Bảng phân vùng nhị phân (Offset 0x8000)
│   ├── factory.bin        # Web Portal cấu hình (Offset 0x10000 - Phân vùng factory)
│   ├── android.bin        # Firmware Android TYMAP BLE (Offset 0xF0000 - Phân vùng ota_0)
│   └── ios.bin            # Firmware iOS Sygic BLE (Offset 0x270000 - Phân vùng app_ios)
└── tools/                 # Bộ công cụ nạp tự động (Interactive menu, auto COM, Erase Flash)
    ├── flasher.py         # Script Python điều khiển esptool thông minh
    ├── flash_menu.bat     # [KHUYÊN DÙNG] Menu chọn cổng COM & tùy chọn Erase Flash
    ├── flash_all.bat      # Nạp trọn gói cả 2 hệ điều hành chỉ với 1 click
    ├── flash_android.bat  # Nạp nhanh firmware Android vào ota_0
    ├── flash_ios.bat      # Nạp nhanh firmware Sygic vào app_ios
    └── flash_factory.bat  # Nạp nhanh Web Portal vào factory
```

---

## 2. Bảng Phân Vùng Flash 4MB (Chuẩn Công Nghiệp Dual-Boot + Safe A/B OTA)

| Phân vùng | Phân loại | Offset | Kích thước | Chức năng |
|---|---|---|---|---|
| `nvs` | data / nvs | `0x9000` | 20 KB (`0x5000`) | Lưu cấu hình hệ thống & bộ đếm Power-cycle |
| `otadata` | data / ota | `0xE000` | 8 KB (`0x2000`) | Quản lý phân vùng boot hiện tại |
| `factory` | app / factory | `0x10000` | 896 KB (`0xE0000`) | Web Captive Portal cấu hình chuyển OS |
| `ota_0` | app / ota_0 | `0xF0000` | 768 KB (`0xC0000`) | Android Slot A (TYMAP BLE) |
| `ota_1` | app / ota_1 | `0x1B0000` | 768 KB (`0xC0000`) | Android Slot B (Hoán đổi BLE OTA an toàn) |
| `app_ios` | app / test | `0x270000` | 768 KB (`0xC0000`) | Firmware iOS (Sygic BLE HUD) độc lập |

---

## 3. Cách Sử Dụng & Kích Hoạt Chuyển Đổi OS

### Cách 1: Tắt / Bật khóa xe 3 lần liên tiếp (Power-Cycle 3 lần)
- Dành cho trường hợp xe đã lắp đồng hồ kín, không bấm được nút cứng.
- **Thao tác:** Bật khóa xe -> Tắt -> Bật -> Tắt -> Bật (trong vòng 3 giây).
- **Kết quả:** ESP32 nhận diện chuỗi khởi động nhanh 3 lần, tự động kích hoạt chuyển boot về phân vùng `factory`.
- Màn hình OLED sẽ phát Wi-Fi:
  - Tên mạng Wi-Fi: **`TYMAP Factory Portal`**
  - Mật khẩu: **`12345678`**
  - IP: **`192.168.4.1`**
- Dùng điện thoại kết nối vào Wi-Fi trên, giao diện Web Portal sẽ tự động bật lên:
  - Chọn **🤖 Android (TYMAP BLE)** hoặc **🍎 iOS (Sygic BLE)**
  - Nhấn **LƯU & KHỞI ĐỘNG LẠI**.

### Cách 2: Nhấn giữ nút BOOT (GPIO9) trong 3 giây
- Trong lúc thiết bị đang chạy (bất kể đang ở Android hay iOS), nhấn giữ nút **BOOT (GPIO9)** trong **3 giây**.
- Màn hình hiển thị: `"NUT BOOT GIU 3S -> CHUYEN FACTORY..."` và tự khởi động lại vào Web Portal.

---

## 4. Hướng Dẫn Kết Nối Với App Sygic Trên iPhone (iOS)

1. Đảm bảo ESP32 đang chạy chế độ **iOS (Sygic BLE)** (nếu chưa, dùng Web Portal chuyển sang iOS).
2. Trên iPhone: Mở ứng dụng **Sygic GPS Navigation & Maps**.
3. Vào **Menu** (góc trên bên trái) -> **Cài đặt (Settings)** -> **Thông tin (Info)** -> **Giới thiệu (About)**.
4. **Chạm nhanh 3 lần** vào bất kỳ dòng chữ nào trên màn hình Giới thiệu.
5. Một dòng menu ẩn mới **"About"** sẽ xuất hiện ở trên cùng -> Bấm vào **About**.
6. Chọn mục **BLE HUD** -> Nhấn nút **Start**.
7. App Sygic trên iPhone sẽ tự động quét Bluetooth, kết nối với ESP32 (`ESP32 HUD`) và đẩy mũi tên rẽ, cự ly, tốc độ giới hạn sang màn hình OLED!

---

## 5. Hướng Dẫn Nạp Firmware Mới Bằng Tool

- Mở thư mục `tymap-factory/tools/`:
  - **Khuyên dùng nhất:** Nhấp đúp chuột vào file **`flash_menu.bat`**
    - Tự động quét và hiển thị toàn bộ cổng COM kết nối trên máy.
    - Cho phép chọn cổng COM mong muốn hoặc gõ phím `R` để quét lại.
    - Có tùy chọn **Xóa toàn bộ Flash (Erase Flash)** trước khi nạp (`y/N`) giúp làm sạch triệt để lỗi bootloop hoặc NVS cũ.
    - Tùy chọn nạp: Trọn gói Dual-Boot hoặc nạp lẻ từng firmware (Factory / Android / iOS).
  - Hoặc nạp nhanh trực tiếp (tự động nhận diện COM):
    - Nạp toàn bộ: Nhấp đúp vào **`flash_all.bat`**.
    - Cập nhật riêng Android: Nhấp đúp vào **`flash_android.bat`**.
    - Cập nhật riêng iOS Sygic: Nhấp đúp vào **`flash_ios.bat`**.
    - Cập nhật riêng Web Portal: Nhấp đúp vào **`flash_factory.bat`**.
*(Công cụ sử dụng nạp tốc độ cao 921600 baud).*
