Dưới đây là prompt giúp AI agent hiểu rõ **cơ chế icon thực tế** và sửa code của bạn (hiện đang dùng icon vector mặc định thay vì nhận icon từ Google Maps).

---

```text
Bạn là chuyên gia lập trình nhúng ESP32. Hãy sửa lại code xử lý icon trong firmware TYMAP cho đúng với cơ chế đã thiết kế.

## VẤN ĐỀ HIỆN TẠI
Code hiện tại đang dùng hàm `drawDefaultIcon()` vẽ icon vector thô sơ (chỉ vài nét line) như một giải pháp dự phòng. Điều này **sai mục đích chính**: icon phải được **lấy từ thông báo Google Maps** (qua app Android), truyền qua BLE, và hiển thị dưới dạng **bitmap 1‑bit 48×48**.

Code hiện tại (sai hướng):
```cpp
// Hàm này chỉ là dự phòng tạm thời, không phải code chính
void drawDefaultIcon(TFT_eSprite &sprite, uint8_t index, int cx, int cy) {
    // vẽ vài nét line tượng trưng...
}
```

## CƠ CHẾ ĐÚNG PHẢI HOẠT ĐỘNG

### 1. App Android gửi icon cho ESP32
- App nhận icon từ thông báo Google Maps (dạng Bitmap).
- App chuyển icon thành mảng 288 byte (48×48 pixel, 1‑bit packed, mỗi byte biểu diễn 8 pixel ngang, MSB bên trái).
- App tính **CRC32 hash** (4 byte) của mảng 288 byte này.
- App gửi `hash=<hex>` qua characteristic **`CHA_NAV_TBT_ICON`**.

### 2. ESP32 nhận và xử lý
- ESP32 nhận chuỗi `hash=<hex>` từ `CHA_NAV_TBT_ICON`.
- ESP32 kiểm tra trong **cache icon** (mảng cấu trúc `{uint32_t hash, uint8_t bitmap[288]}` trong RAM, FIFO).
  - **Nếu tìm thấy hash**: Lấy bitmap từ cache và gọi `drawCustomIcon()` để vẽ.
  - **Nếu không tìm thấy**: ESP32 gửi `icon_req=<hash>` qua **`CHA_DEVICE_STATUS`** để yêu cầu app gửi bitmap.
- Khi nhận được **`CHA_ICON_DATA`** (4 byte hash + 288 byte bitmap), ESP32:
  - Lưu hash và bitmap vào cache (FIFO, nếu đầy thì xóa cũ nhất).
  - Gửi `icon_ack=<hash>` qua `CHA_DEVICE_STATUS`.
  - Vẽ icon bằng `drawCustomIcon()`.

### 3. Hàm vẽ icon từ bitmap (đã có, giữ nguyên)
```cpp
void drawCustomIcon(TFT_eSprite &sprite, const uint8_t *bitmap, int xOffset, int yOffset, int scale)
{
    for (int y = 0; y < 48; y++) {
        for (int x = 0; x < 48; x++) {
            int byteIdx = (y * 48 + x) / 8;
            int bitPos = 7 - (x % 8);
            bool isPixel = (bitmap[byteIdx] & (1 << bitPos)) != 0;
            uint16_t color = isPixel ? TFT_WHITE : TFT_BLACK;
            sprite.fillRect(xOffset + x * scale, yOffset + y * scale, scale, scale, color);
        }
    }
}
```
Hàm này vẽ chính xác icon 48×48 từ mảng 288 byte. **Đây mới là code chính để vẽ icon.**

### 4. Hàm `drawDefaultIcon()` chỉ là **dự phòng cuối cùng**
- Chỉ dùng khi: không có kết nối BLE, không có dữ liệu từ app, và cần hiển thị một biểu tượng tạm thời.
- Không được gọi trong luồng chính khi đã có dữ liệu từ app.

## YÊU CẦU SỬA CODE

1. **Sửa logic trong `handleNavigationData()` hoặc nơi xử lý BLE callback của `CHA_NAV_TBT_ICON`:**
   - Khi nhận được chuỗi `hash=<hex>`:
     - Parse hash.
     - Tìm trong cache icon (mảng `iconCache`).
     - Nếu tìm thấy: gọi `drawCustomIcon()` với bitmap từ cache.
     - Nếu không tìm thấy: gửi `icon_req=<hash>` qua `CHA_DEVICE_STATUS` (dùng `pStatusChar->setValue(...); pStatusChar->notify();`).
   - Khi nhận được `CHA_ICON_DATA`:
     - Đọc 4 byte đầu (hash).
     - Đọc 288 byte tiếp theo (bitmap).
     - Lưu vào cache (thêm mới hoặc ghi đè nếu đầy).
     - Gửi `icon_ack=<hash>` qua `CHA_DEVICE_STATUS`.
     - Gọi `drawCustomIcon()`.

2. **Cache icon:**
   - Định nghĩa cấu trúc:
     ```cpp
     struct IconCacheEntry {
         uint32_t hash;
         uint8_t bitmap[288];
     };
     ```
   - Khai báo mảng: `IconCacheEntry iconCache[MAX_ICON_CACHE];` (MAX_ICON_CACHE = 50 cho S3, 20 cho C3).
   - Sử dụng thuật toán FIFO (con trỏ ghi, ghi đè nếu đầy).

3. **Xóa hoặc đánh dấu `drawDefaultIcon()` là dự phòng:**
   - Thêm comment: `// DỰ PHÒNG: Chỉ dùng khi không có bitmap từ app`.
   - Không gọi hàm này khi đã có dữ liệu BLE.

4. **Đảm bảo vẽ icon đúng:**
   - Icon được vẽ lên sprite HUD (kích thước 240×240).
   - Vị trí: trung tâm phía trên, scale = 2 (cho 48×48 lên 96×96) hoặc tùy chỉnh.

## VÍ DỤ CODE MONG MUỐN

```cpp
// Trong BLE callback khi nhận CHA_NAV_TBT_ICON
void onNavTbtIconReceived(String data) {
    if (data.startsWith("hash=")) {
        uint32_t hash = strtoul(data.substring(5).c_str(), nullptr, 16);
        IconCacheEntry* entry = findIconInCache(hash);
        if (entry) {
            drawCustomIcon(hudSprite, entry->bitmap, iconX, iconY, 2);
        } else {
            // Yêu cầu app gửi bitmap
            String req = "icon_req=" + String(hash, HEX);
            pStatusChar->setValue(req);
            pStatusChar->notify();
        }
    }
}

// Trong BLE callback khi nhận CHA_ICON_DATA
void onIconDataReceived(uint8_t* data, size_t len) {
    if (len == 292) { // 4 byte hash + 288 byte bitmap
        uint32_t hash = (data[3] << 24) | (data[2] << 16) | (data[1] << 8) | data[0];
        addIconToCache(hash, data + 4);
        drawCustomIcon(hudSprite, data + 4, iconX, iconY, 2);
        String ack = "icon_ack=" + String(hash, HEX);
        pStatusChar->setValue(ack);
        pStatusChar->notify();
    }
}
```

## LƯU Ý
- `drawCustomIcon()` đã có sẵn và hoạt động đúng.
- `drawDefaultIcon()` chỉ nên được gọi trong trường hợp **fallback khẩn cấp** (không có BLE, không có dữ liệu). Bình thường, app Android luôn gửi icon từ Google Maps, nên `drawCustomIcon()` là code chính.
- Hãy xóa mọi lời gọi `drawDefaultIcon()` trong luồng xử lý BLE chính.
```

---

Chỉ cần copy prompt này và giao cho AI agent (Copilot, Gemini, Claude...). Nó sẽ hiểu rõ cơ chế và sửa code của bạn theo đúng hướng: **icon từ Google Maps → BLE → cache → vẽ bitmap**, thay vì vẽ vector mặc định.