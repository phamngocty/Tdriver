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
│   ├── android.bin        # Firmware Android TYMAP BLE (Offset 0x110000 - Phân vùng ota_0)
│   └── ios.bin            # Firmware iOS Sygic BLE (Offset 0x280000 - Phân vùng ota_1)
└── tools/                 # Bộ công cụ nạp tự động (Auto COM port detection)
    ├── flasher.py         # Script Python điều khiển esptool thông minh
    ├── flash_all.bat      # Nạp trọn gói cả 2 hệ điều hành chỉ với 1 click
    ├── flash_android.bat  # Nạp nhanh firmware Android vào ota_0
    ├── flash_ios.bat      # Nạp nhanh firmware Sygic vào ota_1
    └── flash_factory.bat  # Nạp nhanh Web Portal vào factory
```

---

## 2. Bảng Phân Vùng Flash 4MB

| Phân vùng | Phân loại | Offset | Kích thước | Chức năng |
|---|---|---|---|---|
| `nvs` | data / nvs | `0x9000` | 20 KB (`0x5000`) | Lưu cấu hình hệ thống & bộ đếm Power-cycle |
| `otadata` | data / ota | `0xE000` | 8 KB (`0x2000`) | Quản lý phân vùng boot hiện tại |
| `factory` | app / factory | `0x10000` | 1 MB (`0x100000`) | Web Captive Portal cấu hình chuyển OS |
| `ota_0` | app / ota_0 | `0x110000` | ~1.43 MB (`0x170000`) | Firmware Android (TYMAP BLE) |
| `ota_1` | app / ota_1 | `0x280000` | ~1.43 MB (`0x170000`) | Firmware iOS (Sygic BLE HUD) |

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
  - Để nạp toàn bộ lần đầu: Nhấp đúp chuột vào file **`flash_all.bat`**.
  - Để cập nhật riêng firmware Android: Nhấp đúp vào file **`flash_android.bat`**.
  - Để cập nhật riêng firmware iOS Sygic: Nhấp đúp vào file **`flash_ios.bat`**.
  - Để cập nhật riêng Web Portal: Nhấp đúp vào file **`flash_factory.bat`**.
*(Công cụ sẽ tự động dò tìm cổng COM của mạch ESP32 và nạp với tốc độ cao 921600 baud).*
