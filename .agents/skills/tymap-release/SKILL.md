---
name: tymap-release
description: Build and release an end-to-end update for the TYMAP project. Use when the user asks to "tạo bản update cho tymap", "release tymap", "build release tymap", "phát hành bản mới cho tymap", "cập nhật tymap", or publish new versions of TYMAP APK and the 3 firmware binaries (GC9A01, OLED SSD1306, Sygic iOS) to github.com/phamngocty/tymapwebside.
---

# TYMAP Release Pipeline (Kỹ Năng Phát Hành Hệ Sinh Thái TYMAP)

Kỹ năng tự động hóa toàn bộ quy trình biên dịch, đóng gói và phát hành bản cập nhật cho hệ sinh thái **TYMAP (Tdriver)** gồm:
1. **Ứng dụng Android TYMAP** (`app-debug.apk`)
2. **Firmware Màn hình tròn GC9A01** (`firmware_gc9a01.bin`)
3. **Firmware Màn hình chữ nhật OLED SSD1306** (`firmware_oled.bin`)
4. **Firmware Sygic Dẫn Đường iOS** (`firmware_sygic_ios.bin`)
5. Phân phối lên GitHub Releases & cập nhật metadata `version.json` trên repo `https://github.com/phamngocty/tymapwebside`.

---

## 1. Bản Đồ Đường Dẫn Mã Nguồn & Artifacts

| Thành phần | Đường dẫn thư mục nguồn | File đích sau khi build |
|---|---|---|
| **Android App** | `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP` | `app-debug.apk` |
| **Firmware GC9A01** | `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\firmware\esp32_s3_gc9a01` | `firmware_gc9a01.bin` |
| **Firmware OLED SSD1306** | `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\firmware\esp32_c3_oled` | `firmware_oled.bin` |
| **Firmware Sygic iOS** | `C:\Users\phamn\Documents\PlatformIO\Tdriver\firmware_sygic_ble` | `firmware_sygic_ios.bin` |
| **Hub Phát Hành** | `https://github.com/phamngocty/tymapwebside` | `version.json` & GitHub Release Tag |

---

## 2. Quy Trình Thực Thi Chi Tiết (Step-by-Step Workflow)

Mỗi khi người dùng yêu cầu: *"Tạo bản update cho TYMAP"* hoặc *"Release TYMAP bản mới"*, AI tự động thực hiện tuần tự các bước sau mà không cần hỏi lại các thông tin hiển nhiên:

### Bước 1: Xác Định Số Phiên Bản Mới (Version Bump)
1. Đọc file `version.json` hiện tại từ:
   `https://raw.githubusercontent.com/phamngocty/tymapwebside/main/version.json`
2. Lấy `versionCode` hiện tại (ví dụ: `22`) và `versionName` (ví dụ: `"1.0.22"`).
3. Tăng tự động lên phiên bản tiếp theo:
   * `newVersionCode = versionCode + 1` (ví dụ: `23`).
   * `newVersionName = "1.0." + newVersionCode` (ví dụ: `"1.0.23"`), trừ khi người dùng chỉ định cụ thể một tên phiên bản khác.
   * `releaseTag = "v" + newVersionName` (ví dụ: `"v1.0.23"`).
4. Tóm tắt nhanh changelog các sửa đổi gần đây từ git log hoặc yêu cầu của người dùng.

### Bước 2: Biên Dịch 3 File Firmware PlatformIO (.bin)
Sử dụng công cụ dòng lệnh PlatformIO (`pio run`) hoặc biên dịch theo từng module:

1. **Build GC9A01 (Màn hình tròn):**
   ```powershell
   pio run -d "C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\firmware\esp32_s3_gc9a01"
   ```
   * File đầu ra: `.pio\build\...\firmware.bin`.
   * Copy và đổi tên thành: `firmware_gc9a01.bin`.

2. **Build OLED SSD1306 (Màn hình chữ nhật):**
   ```powershell
   pio run -d "C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\firmware\esp32_c3_oled"
   ```
   * File đầu ra: `.pio\build\...\firmware.bin`.
   * Copy và đổi tên thành: `firmware_oled.bin`.

3. **Build Sygic iOS (BLE Sygic HUD):**
   ```powershell
   pio run -d "C:\Users\phamn\Documents\PlatformIO\Tdriver\firmware_sygic_ble"
   ```
   * File đầu ra: `.pio\build\...\firmware.bin` (hoặc lấy từ `tymap-factory\binaries\ios.bin`).
   * Copy và đổi tên thành: `firmware_sygic_ios.bin`.

### Bước 3: Biên Dịch Ứng Dụng Android TYMAP (.apk)
1. Cập nhật `versionCode` và `versionName` trong `TYMAP/app/build.gradle.kts` (nếu cần đồng bộ).
2. Thiết lập môi trường JDK 17 và biên dịch APK:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   Set-Location "C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP"
   .\gradlew.bat :app:assembleDebug --no-daemon
   ```
3. Lấy file APK hoàn chỉnh tại:
   `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\app\build\outputs\apk\debug\app-debug.apk`.

### Bước 4: Gom File Vào Thư Mục Staging & Kiểm Tra Tính Toàn Vẹn
1. Tạo thư mục tạm `C:\Users\phamn\Documents\PlatformIO\Tdriver\release_staging\v<versionName>`.
2. Sao chép đủ 4 file:
   * `app-debug.apk`
   * `firmware_gc9a01.bin`
   * `firmware_oled.bin`
   * `firmware_sygic_ios.bin`
3. Kiểm tra kích thước file: Bắt buộc tất cả file phải có `Length > 0`.

### Bước 5: Tạo GitHub Release Trên `phamngocty/tymapwebside`
Sử dụng GitHub REST API (với Personal Access Token lưu trong cấu hình git của máy) hoặc script PowerShell tự động:
1. Tạo Release Draft/Published với tag `v<versionName>` qua endpoint:
   `POST https://api.github.com/repos/phamngocty/tymapwebside/releases`
2. Upload 4 file nhị phân vào Release Assets:
   `POST https://uploads.github.com/repos/phamngocty/tymapwebside/releases/{id}/assets?name={fileName}`

### Bước 6: Cập Nhật Metadata `version.json` Lên Nhánh `main`
Cấu trúc chuẩn của `version.json`:
```json
{
  "app": {
    "versionCode": 23,
    "versionName": "1.0.23",
    "apkUrl": "https://github.com/phamngocty/tymapwebside/releases/download/v1.0.23/app-debug.apk",
    "apkName": "app-debug.apk",
    "changelog": "• Cập nhật bản vá tối ưu hiển thị Turn-by-Turn."
  },
  "firmware_gc9a01": {
    "name": "Màn hình tròn màu GC9A01",
    "versionCode": 23,
    "versionName": "1.0.23",
    "binUrl": "https://github.com/phamngocty/tymapwebside/releases/download/v1.0.23/firmware_gc9a01.bin",
    "binName": "firmware_gc9a01.bin",
    "changelog": "• Tối ưu giao diện đồng hồ tròn và icon rẽ."
  },
  "firmware_oled": {
    "name": "Màn hình chữ nhật OLED SSD1306",
    "versionCode": 23,
    "versionName": "1.0.23",
    "binUrl": "https://github.com/phamngocty/tymapwebside/releases/download/v1.0.23/firmware_oled.bin",
    "binName": "firmware_oled.bin",
    "changelog": "• Tối ưu đồ họa 1bpp và bộ nhớ RAM."
  },
  "firmware_sygic_ios": {
    "name": "Firmware Sygic Dẫn Đường iOS",
    "versionCode": 23,
    "versionName": "1.0.23",
    "binUrl": "https://github.com/phamngocty/tymapwebside/releases/download/v1.0.23/firmware_sygic_ios.bin",
    "binName": "firmware_sygic_ios.bin",
    "changelog": "• Tương thích BLE Sygic Navigation trên iOS."
  },
  "firmware": {
    "versionCode": 23,
    "versionName": "1.0.23",
    "binUrl": "https://github.com/phamngocty/tymapwebside/releases/download/v1.0.23/firmware_gc9a01.bin",
    "binName": "firmware_gc9a01.bin",
    "secondaryBinUrl": "https://github.com/phamngocty/tymapwebside/releases/download/v1.0.23/firmware_oled.bin",
    "oledBinUrl": "https://github.com/phamngocty/tymapwebside/releases/download/v1.0.23/firmware_oled.bin",
    "iosBinUrl": "https://github.com/phamngocty/tymapwebside/releases/download/v1.0.23/firmware_sygic_ios.bin",
    "changelog": "• Cập nhật firmware phiên bản mới nhất."
  }
}
```
Đẩy nội dung mới của `version.json` lên nhánh `main` của repo `phamngocty/tymapwebside`.

### Bước 7: Nghiệm Thu Hoàn Tất (Verification)
1. Kiểm tra mã phản hồi HTTP `200 OK` khi truy vấn URL `version.json` trên GitHub.
2. Kiểm tra link tải của các file assets trong Release.
3. Báo cáo bảng tóm tắt chi tiết cho người dùng gồm: Mã phiên bản, danh sách file, kích thước, và đường link tải trực tiếp.
