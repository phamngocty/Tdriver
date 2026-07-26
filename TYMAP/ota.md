Viewed AndroidManifest.xml:1-100
Listed directory xml
Edited file_paths.xml
Edited AndroidManifest.xml
Edited UpdateManager.kt
Viewed BleManager.kt:320-370
Viewed BleManager.kt:360-389
Edited BleManager.kt
Viewed fragment_settings.xml:340-390
Viewed fragment_settings.xml:440-477
Edited fragment_settings.xml
Viewed SettingsFragment.kt:440-500
Viewed SettingsFragment.kt:420-455
Edited SettingsFragment.kt
Ran command: `./gradlew assembleDebug --no-daemon`

Đang tiến hành biên dịch ứng dụng với tính năng Cập nhật tự động App APK & Firmware ESP32 qua GitHub Releases...

Viewed fragment_settings.xml:480-500
Edited fragment_settings.xml
Ran command: `./gradlew assembleDebug --no-daemon`

Đang thực hiện biên dịch lại với `./gradlew assembleDebug`...

Viewed fragment_settings.xml:530-550
Edited fragment_settings.xml
Ran command: `./gradlew assembleDebug --no-daemon`

Đang biên dịch lại ứng dụng...

Searched for "bleManager"
Viewed build.gradle.kts:1-40
Edited BleManager.kt
Viewed UpdateManager.kt:1-75
Edited UpdateManager.kt
Edited UpdateManager.kt
Edited UpdateManager.kt
Viewed SettingsFragment.kt:490-520
Edited SettingsFragment.kt
Ran command: `./gradlew assembleDebug --no-daemon`

Đang thực hiện biên dịch lại ứng dụng...

Viewed UpdateManager.kt:1-15
Edited UpdateManager.kt
Ran command: `./gradlew assembleDebug --no-daemon`

Đang kiểm tra và biên dịch lại ứng dụng...

Đã tích hợp thành công tính năng **Tự động kiểm tra bản cập nhật App APK** và **Nâng cấp Firmware ESP32 HUD qua BLE OTA** sử dụng **GitHub Releases (Miễn phí 100% & Băng thông vô hạn)**.

---

### 📦 1. Mã nguồn & Giao diện vừa được tích hợp

1. **Thẻ Cấu hình tại [SettingsFragment] (Mục 6 trong Cài đặt):**
   - **Ô nhập URL:** Nhập link file JSON trên GitHub (Mặc định: `https://raw.githubusercontent.com/phamn/TYMAP/main/version.json`).
   - **Nút [Kiểm tra bản cập nhật]:** Tự động gửi request kiểm tra phiên bản mới từ GitHub.
   - **Nút [Tải & Nâng cấp App Android (APK)]:** Tự động tải file `.apk` mới về máy và mở giao diện cài đặt chỉ với **1 cú click**.
   - **Nút [Nâng cấp Firmware HUD (BLE OTA)]:** Tự động tải file `firmware.bin` từ GitHub và **truyền dữ liệu nạp không dây qua BLE sang ESP32-S3** mà không cần cắm cáp USB.

2. **Cấu hình Quyền & FileProvider (`AndroidManifest.xml` & `file_paths.xml`):**
   - Đã khai báo quyền `REQUEST_INSTALL_PACKAGES` và `FileProvider` an toàn theo chuẩn Android 14+.

3. **Bộ nạp Firmware qua BLE (`BleManager.kt` & `UpdateManager.kt`):**
   - Tải file `.bin` và chia nhỏ thành các gói dữ liệu MTU (512 bytes) gửi liên tục qua sóng BLE với thanh tiến trình % hiển thị trực quan.
   - Gửi lệnh `0x30` (Start OTA) và `0x31` (Finish & Reboot ESP32).

---

### 📝 2. Hướng dẫn cách tạo Release & đăng tải file lên GitHub

Mỗi khi bạn có phiên bản App mới hoặc Firmware ESP32 mới, bạn thực hiện 3 bước đơn giản:

#### Bước 1: Đăng tải File `.apk` và `.bin` lên GitHub Releases
1. Vào Repository project TYMAP trên GitHub.
2. Bấm vào **Releases** ➡️ **Draft a new release**.
3. Đặt Tag name (ví dụ: `v1.0.2`), chọn **Publish release**.
4. Kéo thả file `TYMAP_v1.0.2.apk` và file `firmware_v1.0.2.bin` vào mục **Assets**.

#### Bước 2: Cập nhật file `version.json` trên nhánh `main`
Đăng tải hoặc chỉnh sửa file `version.json` ở thư mục gốc repo GitHub của bạn với nội dung:

```json
{
  "app": {
    "versionCode": 102,
    "versionName": "1.0.2",
    "apkUrl": "https://github.com/phamn/TYMAP/releases/download/v1.0.2/TYMAP_v1.0.2.apk",
    "changelog": "• Thêm giao diện OLED mịn màng.\n• Sửa lỗi lưu API Key.\n• Tối ưu dẫn đường đa máy chủ OSRM/Valhalla."
  },
  "firmware": {
    "versionCode": 12,
    "versionName": "1.2.0",
    "binUrl": "https://github.com/phamn/TYMAP/releases/download/v1.0.2/firmware_v1.0.2.bin",
    "changelog": "• Cập nhật thuật toán hiển thị màn hình GC9A01.\n• Tăng tốc độ nhận gói tin BLE."
  }
}
```

---

### ⚙️ Kết quả Biên dịch (Verification)
- Command: `./gradlew assembleDebug`
- Status: **BUILD SUCCESSFUL** (1m 48s, 39 actionable tasks: 6 executed, 33 up-to-date).