# Kế Hoạch OTA (Over-The-Air Update) — TYMAP

## Tổng Quan

Xây dựng cơ chế cập nhật OTA hoàn chỉnh cho **cả hai thành phần**:
- **App Android (APK)** — tải và cài APK từ GitHub Releases qua Internet
- **Firmware ESP32-S3 HUD** — nạp firmware `.bin` không dây qua BLE từ Android

Cơ sở hạ tầng phân phối: **GitHub Releases (miễn phí, băng thông vô hạn)**

---

## Phân Tích Hiện Trạng

### ✅ Đã có (cần giữ lại & hoàn thiện)
| Thành phần | File | Trạng thái |
|---|---|---|
| `UpdateManager.kt` | `utils/UpdateManager.kt` | Đã có: checkUpdate, downloadAPK, downloadFirmwareBin |
| BLE OTA sender | `BleManager.kt` L402-435 | Đã có: gửi 0x30 Start + chunks 512B + 0x31 Finish |
| UI Cài đặt cập nhật | `SettingsFragment.kt` | Đã có nhưng cần review |
| `version.json` schema | `ota.md` | Đã thiết kế |

### ❌ Chưa có (cần xây dựng mới)
| Thiếu sót | Mức độ | Ảnh hưởng |
|---|---|---|
| **ESP32 không có handler nhận OTA qua BLE** | 🔴 Nghiêm trọng | Toàn bộ OTA firmware không hoạt động |
| `cmd == 0x30` trong REMOTE_CMD trả về "ping=ok", không phải OTA | 🔴 Nghiêm trọng | Lệnh khởi động OTA bị bỏ qua |
| Không có OTA characteristic riêng cho firmware bin | 🟡 Quan trọng | Cần UUID mới hoặc tái dùng MAP_IMAGE |
| `esp32_fw_version_code` không bao giờ được cập nhật sau OTA | 🟡 Quan trọng | App luôn thấy "có bản mới" |
| Không có `version.json` thực tế trên GitHub | 🟡 Quan trọng | checkUpdate luôn fail |
| Partition table: `default.csv` không hỗ trợ OTA | 🔴 Nghiêm trọng | ESP32 cần partition OTA để nạp firmware mới |

---

## User Review Required

> [!CAUTION]
> **Partition Table phải thay đổi** — Hiện tại `platformio.ini` dùng `board_build.partitions = default.csv`. Partition này **không có OTA partition**, ESP32 sẽ crash khi gọi `Update.begin()`. Cần đổi sang `min_spiffs.csv` (4MB Flash, ~1.8MB mỗi app slot). **Toàn bộ Flash phải nạp lại lần đầu qua USB.**

> [!WARNING]
> **Kích thước firmware hiện tại** — Cần verify firmware `.bin` < 1.8MB để fit vào OTA slot. Chạy `pio run` và kiểm tra output `.pio/build/.../firmware.bin`.

> [!IMPORTANT]
> **BLE throughput giới hạn** — OTA firmware ~1MB qua BLE 4.x với MTU 512B ~200 chunks = ~40–60 giây. Trong thời gian này, BLE connection phải giữ ổn định. Khuyến nghị: user đặt điện thoại gần HUD (<1m) khi OTA.

---

## Open Questions

> [!IMPORTANT]
> **Q1**: Có muốn OTA firmware qua BLE (`mapImageChar` tái sử dụng) hay tạo UUID BLE mới riêng cho OTA? Tái dùng đơn giản hơn nhưng dễ xung đột nếu đang stream map.

> [!IMPORTANT]
> **Q2**: Sau khi ESP32 reboot thành công OTA, có muốn app Android hiển thị thông báo xác nhận "OTA thành công"? (cần ESP32 gửi notification BLE sau boot)

> [!NOTE]
> **Q3**: Có muốn tính năng **kiểm tra update tự động khi mở app** (silent background check) hay chỉ khi user nhấn nút "Kiểm tra bản cập nhật"?

---

## Kế Hoạch Thực Hiện

### Giai đoạn 1 — Sửa nền tảng ESP32 (Bắt buộc, cần USB lần đầu)

#### [MODIFY] [platformio.ini](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/platformio.ini)
- Đổi `board_build.partitions = default.csv` → `min_spiffs.csv`
- Thêm `upload_protocol = esp-builtin` comment rõ ràng hơn

---

#### [MODIFY] [main.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp)

**Thêm OTA UUID và handler mới:**

```cpp
// UUID mới dành riêng cho OTA binary stream
const char *CHA_OTA_UUID = "f0a1b2c3-d4e5-4f60-a012-bcdef0123456";
```

**Thêm biến trạng thái OTA:**
```cpp
bool isOtaMode = false;
uint32_t otaExpectedSize = 0;
uint32_t otaWritten = 0;
```

**Handler trong BLE callback:**
```cpp
else if (uuid == CHA_OTA_UUID) {
    if (!isOtaMode && val.length() == 5 && val[0] == 0x30) {
        // Bắt đầu OTA: byte[0]=0x30, byte[1..4]=size LE
        memcpy(&otaExpectedSize, val.data() + 1, 4);
        if (Update.begin(otaExpectedSize, U_FLASH)) {
            isOtaMode = true;
            otaWritten = 0;
            Serial.printf("OTA: Start, expected %d bytes\n", otaExpectedSize);
        }
    } else if (isOtaMode && val[0] != 0x31) {
        // Data chunk
        Update.write((uint8_t*)val.data(), val.length());
        otaWritten += val.length();
    } else if (isOtaMode && val.length() == 1 && val[0] == 0x31) {
        // Finish
        if (Update.end(true)) {
            Serial.println("OTA: Success! Rebooting...");
            delay(500);
            ESP.restart();
        }
    }
}
```

**Đăng ký characteristic:**
```cpp
pOtaChar = pService->createCharacteristic(
    CHA_OTA_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
pOtaChar->setCallbacks(sCallbacks);
```

---

### Giai đoạn 2 — Cập nhật Android App

#### [MODIFY] [BleManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ble/BleManager.kt)
- Đổi `writeMapCtrl(0x30...)` → `writeOtaChar(...)` dùng UUID mới `CHA_OTA_UUID`
- Thêm `otaChar` NimBLE characteristic reference
- Cập nhật `writeEsp32FirmwareOta()` để gửi đúng UUID

#### [MODIFY] [UpdateManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/utils/UpdateManager.kt)
- Sau khi OTA thành công, lưu `esp32_fw_version_code` = version mới vào SharedPrefs
- Thêm auto-check khi app khởi động (coroutine trong `onResume`)

#### [MODIFY] [SettingsFragment.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ui/SettingsFragment.kt)
- Review và đảm bảo UI section OTA hiển thị đúng progress bar
- Thêm progress dialog khi đang tải firmware/APK

---

### Giai đoạn 3 — Version Control & CI/CD

#### [NEW] `version.json` (đặt tại root repository GitHub)
```json
{
  "app": {
    "versionCode": 1,
    "versionName": "1.0.0",
    "apkUrl": "https://github.com/USERNAME/TYMAP/releases/download/v1.0.0/TYMAP.apk",
    "changelog": "Phiên bản đầu tiên."
  },
  "firmware": {
    "versionCode": 1,
    "versionName": "1.0.0",
    "binUrl": "https://github.com/USERNAME/TYMAP/releases/download/v1.0.0/firmware.bin",
    "changelog": "Phiên bản đầu tiên."
  }
}
```

#### [NEW] `.github/workflows/release.yml` (GitHub Actions tự động build & release)
- Trigger: push tag `v*`
- Build APK bằng Gradle
- Build firmware bằng PlatformIO CLI
- Upload cả hai file lên GitHub Release
- Cập nhật `version.json` tự động

---

## Luồng Hoạt Động Sau Khi Hoàn Thiện

```
User mở app → UpdateManager kiểm tra version.json
    ├─ App update → Tải APK → PackageInstaller cài đặt
    └─ Firmware update → Tải firmware.bin → BleManager stream qua BLE
                              → ESP32 nhận, nạp vào OTA partition
                              → ESP32 reboot tự động
                              → App nhận BLE notification "OTA OK"
```

---

## Kế Hoạch Xác Minh

### Giai đoạn 1 — ESP32
1. `pio run` — compile thành công, firmware.bin < 1.8MB
2. Flash qua USB lần đầu với partition mới
3. Serial Monitor: verify `Update.begin()` không crash

### Giai đoạn 2 — App Android
1. `./gradlew assembleDebug` — build thành công
2. Test checkUpdate trả về đúng kết quả
3. Test OTA firmware: kết nối BLE → nhấn nút → xem Serial Monitor → ESP32 reboot

### Giai đoạn 3 — End-to-End
1. Tạo GitHub Release test với version code cao hơn
2. App phát hiện bản mới, tải, cài
3. OTA ESP32 thành công, version code được lưu đúng

---

## Ưu Tiên Thực Hiện

| Bước | Mô tả | Effort | Rủi ro |
|:---:|---|:---:|:---:|
| **1** | Sửa partition table + implement OTA handler ESP32 | Cao | Cao (cần USB) |
| **2** | Sửa BleManager UUID OTA | Thấp | Thấp |
| **3** | Cập nhật version code sau OTA | Thấp | Thấp |
| **4** | Tạo `version.json` thực tế | Thấp | Thấp |
| **5** | GitHub Actions CI/CD | Trung bình | Trung bình |
