# Hướng dẫn vị trí sửa đổi Code Logic (TYMAP)

Tài liệu này chỉ ra các vị trí dòng code quan trọng của dự án TYMAP (cả trên Android và ESP32 Firmware) để bạn có thể tự chỉnh sửa các thông số cơ bản (như góc xoay hướng nhìn, độ trễ, font chữ, màu sắc...) một cách nhanh chóng mà không cần tốn token trò chuyện.

---

## 1. Điều chỉnh góc lệch hướng nhìn (Map Orientation & Marker Rotation)

Khi bạn muốn bù trừ góc lệch cho hướng nhìn bản đồ hoặc chấm định vị (ví dụ cộng/trừ 10 độ):

### 📌 Trên điện thoại (Giao diện App - `MapFragment.kt`)
* **Tệp**: [MapFragment.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ui/MapFragment.kt)
* **Xoay bản đồ theo hướng xe đi (khi di chuyển - GPS Bearing)**:
  * *Vị trí*: Dòng 658 và dòng 622 (`binding.mapView.mapOrientation = -location.bearing`)
  * *Cách sửa*: Cộng hoặc trừ thêm góc bù (ví dụ `-location.bearing + 10f` hoặc `-location.bearing - 10f`).
* **Xoay bản đồ theo la bàn (khi đứng yên - Compass Heading)**:
  * *Vị trí*: Dòng 966 (`binding.mapView.mapOrientation = -orientation`)
  * *Cách sửa*: Đổi thành `-orientation + 10f`.
* **Xoay mũi tên định vị (Blue Dot Marker)**:
  * *Vị trí*: Các dòng 606, 659 (`animateMarkerRotation(-location.bearing)`) và dòng 961 (`animateMarkerRotation(-orientation)`)
  * *Cách sửa*: Thay đổi tham số truyền vào, ví dụ: `animateMarkerRotation(-location.bearing + 10f)`.

### 📌 Trên bản đồ ngầm (Ảnh gửi sang ESP32 - `NavigationService.kt`)
* **Tệp**: [NavigationService.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt)
* **Xoay ảnh bản đồ ngầm gửi sang ESP32**:
  * *Vị trí*: Hàm `renderOsmMap()`, đoạn tính toán `heading` (xung quanh dòng 910):
    ```kotlin
    val heading = if (speed > 1.2f) {
        it.bearing
    } else {
        NavigationRepository.compassHeading.value
    }
    headlessMapView?.mapOrientation = -heading
    ```
  * *Cách sửa*: Cộng hoặc trừ trực tiếp vào `heading` hoặc `mapOrientation`. Ví dụ:
    `headlessMapView?.mapOrientation = -heading - 10f` (để xoay lệch 10 độ).
* **Đồng bộ hóa Zoom tức thời từ App sang ESP32**:
  * *Vị trí*: Khởi tạo trong `setupHeadlessMap()` và áp dụng tại hàm `renderOsmMap()`:
    `headlessMapView?.controller?.setZoom(NavigationRepository.lastMapZoom)`
  * *Cách sửa*: Khi người dùng zoom (pinch zoom, nút bấm zoom in/out) trên bản đồ app điện thoại, `MapFragment.kt` (ở hàm `onZoom()`) sẽ lập tức cập nhật giá trị `NavigationRepository.lastMapZoom` giúp bản đồ ESP32 thay đổi tỷ lệ tương ứng tức thời.

---

## 2. Điều chỉnh kích thước và vùng cắt ảnh bản đồ (Map Crop Area)

* **Tệp**: [NavigationService.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt)
* **Vị trí**: Cuối hàm `renderOsmMap()` (xung quanh dòng 930)
* **Cách sửa**: 
  * Thay đổi kích thước khung render ban đầu bằng cách sửa `renderWidth` (mặc định 480) và `renderHeight` (mặc định 800).
  * Vùng cắt mặc định lấy từ Settings hoặc tỷ lệ mặc định (`normSize = 0.4f` ứng với kích thước 240x240 để gửi sang ESP32).

---

## 3. Điều chỉnh nét vẽ lộ trình (Polyline) và vị trí người dùng trên ESP32

* **Tệp**: [NavigationService.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt)
* **Màu sắc và độ dày đường vẽ lộ trình (Polyline)**:
  * *Vị trí*: Đoạn `val polyline = Polyline(headlessMapView).apply { ... }` (xung quanh dòng 895).
  * *Cách sửa*: Đổi màu tại `Color.parseColor("#007AFF")` và độ dày nét tại `outlinePaint.strokeWidth = 18f`.
* **Kích thước chấm xanh định vị (Blue Dot)**:
  * *Vị trí*: Hàm `createUserIcon()` (xung quanh dòng 950).
  * *Cách sửa*: Thay đổi bán kính vẽ hình tròn `canvas.drawCircle(centerX, centerY, 15f, paint)` (bán kính 15f) hoặc đổi màu của chùm quạt la bàn phía trước.

---

## 4. Chỉnh sửa giao diện trên thiết bị ESP32 (Vẽ màn hình - GC9A01)

Nếu bạn muốn thay đổi màu sắc, font chữ hoặc cách hiển thị trên màn hình tròn:

* **Tệp**: [gui.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/gui.cpp)
* **Giao diện dẫn đường HUD**: sửa trong hàm `drawHUD()`.
* **Giao diện thời tiết & đồng hồ STATUS**: sửa trong hàm `drawSTATUS()`.
* **Màu sắc chữ và nền**:
  * Các hàm sử dụng lệnh: `myFont.print(x, y, text, màu_chữ, màu_nền)`.
  * Các mã màu thông dụng: `TFT_WHITE` (Trắng), `TFT_BLACK` (Đen), `TFT_SKYBLUE` (Xanh da trời), `TFT_GREEN` (Xanh lá), `TFT_YELLOW` (Vàng), `TFT_ORANGE` (Cam).
* **Font chữ**: Sử dụng lệnh `myFont.set_font(FONT_NAME)` trước khi viết chữ.
  * Các font định nghĩa sẵn trong `gui.h`: `FONT_CLOCK`, `FONT_HUD_STREET`, `FONT_HUD_DIST`, `vietnamtimes12`...

---

## 5. Thời gian tắt màn hình và tần suất gửi dữ liệu (Timeouts & Intervals)

* **Tần suất gửi ảnh bản đồ (FPS)**:
  * *Tệp*: [NavigationService.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt)
  * *Vị trí*: Hàm `startMapRenderingLoop()` (xung quanh dòng 780), cấu hình `fps` dựa trên settings.
  * *Cài đặt*: Hỗ trợ thêm tùy chọn **7 FPS** trực tiếp tại Tab Cài đặt (ánh xạ index `4` -> `7 FPS` trong loop render).
* **Bật/Tắt Bỏ qua khung hình trùng lặp (Frame Skipping)**:
  * *Tệp*: [SettingsFragment.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ui/SettingsFragment.kt) và [fragment_settings.xml](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/res/layout/fragment_settings.xml)
  * *Cách sửa*: Sử dụng Switch "Bỏ qua khung hình trùng lặp (CRC32)" trực tiếp tại Tab Cài đặt để bật/tắt (lưu vào Key SharedPreferences `"frame_skipping"`).
* **Tự động chuyển HUD khi có thông báo Google Maps**:
  * *Tệp*: [NavigationService.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt)
  * *Vị trí*: Hàm `handleGmapsUpdate()` (xung quanh dòng 185) và `handleGmapsStop()` (xung quanh dòng 150).
  * *Cách sửa*:
    * Khi ở chế độ Google Maps (mode 1 hoặc 2): App tự động chuyển đổi mode hiển thị tương ứng (MAP cho mode 1, HUD cho mode 2) và tự động tắt chuyển về STATUS khi kết thúc.
    * Khi ở chế độ OSM (mode 0): **Chỉ tự động chuyển ESP32 sang HUD_MODE khi app không ở trạng thái dẫn đường** (`NavigationRepository.navigationState.value == false`) để ưu tiên thông báo của Google Maps. Nếu app đang tự dẫn đường OSM (`navigationState.value == true`), thông báo Google Maps sẽ không ghi đè để bảo vệ màn hình dẫn đường OSM.
    * **Chụp Google Maps khi có Popup (mode 2)**: Trong hàm `startMapRenderingLoop()` (xung quanh dòng 790), hệ thống cho phép kích hoạt vòng lặp render/chụp màn hình khi `isPopupActive == true` cho cả mode 2 và tiến hành capture màn hình Google Maps gửi sang ESP32. Nếu không chụp được hoặc màn hình tĩnh, hệ thống sẽ bỏ qua để giữ nguyên ảnh cũ, không tự ý fallback vẽ bản đồ OSM ngầm.
    * **Loại bỏ tự động Fallback OSM khi chụp Google Maps (mode 1 và 2)**: Khi chọn chế độ chụp Google Maps (mode 1 hoặc 2), ảnh được chụp và gửi đi duy nhất là ảnh màn hình Google Maps với toạ độ cấu hình tương ứng trong `"gmaps_"`. Hệ thống sẽ không tự ý fallback vẽ bản đồ OSM ngầm của App để tránh hiện tượng nhấp nháy/trộn lẫn hình ảnh. Chế độ OSM (`captureMode == 0`) sẽ luôn gửi ảnh vẽ từ bản đồ OSM ngầm.
    * **Cơ chế chống màn hình tĩnh và kẹt bộ đệm của ScreenCaptureManager**: Trong [ScreenCaptureManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/utils/ScreenCaptureManager.kt), hệ thống tự động fallback sử dụng `acquireNextImage()` nếu `acquireLatestImage()` bị trả về `null`. Ngoài ra, ảnh nén JPEG thành công gần nhất sẽ được lưu trữ (cached). Khi màn hình đứng yên (không có chuyển động đồ họa nên MediaProjection không render frame mới), hệ thống sẽ tái sử dụng frame đã chụp gần nhất này thay vì trả về dữ liệu rỗng.
    * **Yêu cầu quyền ghi hình màn hình (Media Projection)**: Trong [SettingsFragment.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ui/SettingsFragment.kt), quyền chỉ được yêu cầu thông qua `screenCaptureLauncher.launch()` khi người dùng thực tế click thay đổi Spinner chọn chế độ 1 hoặc 2. Callback khởi tạo ban đầu của Spinner sẽ được bỏ qua để tránh tự động hiện hộp thoại xin quyền đột ngột gây lỗi tự từ chối khi mở tab Cài đặt.
    * **Khởi động Service trước khi xin quyền (Android 14+)**: Để tránh lỗi `SecurityException` hoặc từ chối quyền đột ngột, `NavigationService` luôn được khởi động chạy và cập nhật type `mediaProjection` ngay trước khi gọi `screenCaptureLauncher.launch()`. Điều này đảm bảo hệ thống có một Foreground Service hợp lệ trước khi token cấp quyền được trả về.
    * **Hộp thoại khắc phục lỗi từ chối/vẽ đè**: Khi Media Projection bị từ chối (do bấm hụt hoặc bị ứng dụng vẽ đè Messenger/Zalo chặn), app sẽ không lập tức reset chế độ mà sẽ hiển thị một Hộp thoại Hướng dẫn chi tiết. Hộp thoại cho phép người dùng **Thử lại** ngay sau khi tắt bong bóng chat/overlay, hoặc bấm **Hủy** để quay về chế độ OSM.
    * **Tự động đóng cửa sổ cấu hình cắt khi xin quyền**: Để tránh xung đột cửa sổ vẽ đè (Screen Overlay) từ chính app TYMAP, trước khi khởi chạy Intent xin quyền chụp màn hình, ứng dụng sẽ chủ động gửi lệnh dừng `CropOverlayService` để xoá sạch overlay của chính mình trên màn hình điện thoại.
    * **Giảm tải độ phân giải VirtualDisplay tránh OOM**: Để tránh việc Android OS kill app chạy ngầm do tràn bộ nhớ (Out Of Memory) khi Google Maps chạy ngầm và ngốn RAM, độ phân giải của VirtualDisplay và ImageReader trong [ScreenCaptureManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/utils/ScreenCaptureManager.kt) đã được scale nhỏ đi 2 lần so với màn hình thực (dung lượng RAM mỗi frame giảm đi 4 lần, từ 10MB xuống chỉ còn 2.5MB). Toạ độ cắt được chuẩn hoá tự động dựa trên kích thước Bitmap thực tế nên vùng cắt của người dùng vẫn chính xác tuyệt đối.
    * **Buộc VirtualDisplay render liên tục (OnImageAvailableListener)**: Trong [ScreenCaptureManager.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/utils/ScreenCaptureManager.kt), hệ thống đăng ký một `OnImageAvailableListener` giả trên ImageReader. Sự hiện diện của listener này báo cho Android Graphics Pipeline biết Surface đang được lắng nghe tích cực, ngăn hệ thống tự động tạm dừng (pause) luồng vẽ của VirtualDisplay trên các máy Oppo, Xiaomi, Samsung.
    * **Khắc phục lỗi chia sẻ địa điểm/tuyến đường chập chờn & Sai tọa độ**: 
      * **Redirect 200 OK với body HTML**: Trong `resolveRedirect` của [UrlParser.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/utils/UrlParser.kt), nếu Google Maps trả về mã HTTP 200 kèm theo trang web chuyển hướng trung gian (thay vì 302 Location Header), app sẽ tự động quét mã nguồn HTML để trích xuất link Maps đầy đủ. Việc này chấm dứt hiện tượng chập chờn lúc được lúc không. Ngoài ra, OkHttp được bật cờ tự động redirect ngầm để tăng tốc độ phản hồi và độ chính xác tối đa.
      * **Ưu tiên tọa độ thực POI và camera (Chống sai tọa độ)**: Trong `parseCoordinates`, hệ thống ưu tiên quét tìm tọa độ số trực tiếp trong URL (theo thứ tự: cấu trúc dữ liệu POI gốc `!3d...!4d...` -> tọa độ camera hoặc query `@lat,lon` hoặc `q=lat,lon` -> bất kỳ chuỗi tọa độ hợp lệ nào). App chỉ thực hiện Geocoding bằng chữ nếu URL hoàn toàn trống dữ liệu tọa độ số.
      * **Chặn Geocoding chuỗi tọa độ số**: Trong [ShareReceiverActivity.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/ShareReceiverActivity.kt), nếu tên địa điểm trích xuất thực chất là một chuỗi tọa độ số (ví dụ: `"10.7725,106.6958"`), app sẽ tự động parse trực tiếp ngoại tuyến (offline) làm điểm đến thay vì gửi chuỗi số này đi Geocoding qua mạng (vốn không hỗ trợ và sẽ trả về kết quả rác ở châu Âu/châu Mỹ gây lệch tọa độ nghiêm trọng).
      * **Bóc tách tuyến đường (Direction)**: Khi chia sẻ tuyến đường dạng `/dir/...`, Google Maps không cung cấp sẵn toạ độ cụ thể trong URL. App đã bổ sung cơ chế bóc tách tên địa điểm đích từ URL `/dir/` và tự động thực hiện Geocoding (Photon/Nominatim) để lấy toạ độ thực của điểm đến, giúp app nhận diện điểm ghim và bắt đầu định tuyến dẫn đường chính xác từ lần đầu tiên.
    * **Bảo vệ xử lý Bitmap an toàn trong renderOsmMap**: Trong `renderOsmMap` của [NavigationService.kt](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/app/src/main/java/com/example/tymap/service/NavigationService.kt), toàn bộ luồng cắt và nén ảnh được bọc trong block `try-catch`. Đồng thời, việc `recycle()` các bitmap trung gian được kiểm tra tham chiếu kỹ lưỡng (`cropped != bitmap`, `scaled != cropped`) và kiểm tra `!isRecycled` để tránh lỗi giải phóng trùng lặp hoặc vẽ lên bitmap đã giải phóng làm app sập. Kích thước cắt cũng được giới hạn tối thiểu 1 pixel để chống lỗi kích thước bằng 0.
* **Thời gian tự động thoát HUD về màn hình đồng hồ (HUD Timeout)**:
  * *Tệp*: [main.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp)
  * *Vị trí*: Dòng 883 (`millis() - lastNavUpdate > (uint32_t)(hudTimeout * 30000)`)
  * *Cách sửa*: Đổi hệ số nhân hoặc thay đổi giá trị mặc định.
* **Thời gian hiển thị Popup ngã rẽ**:
  * *Tệp*: [main.cpp](file:///d:/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp)
  * *Vị trí*: Dòng 890 (`millis() - popupStartTime > (uint32_t)(popupDuration * 5000)`)
  * *Cách sửa*: Thay đổi thời gian chờ mặc định (mỗi đơn vị tương ứng 5 giây).
