# Danh sách cấu trúc các mục cài đặt trong SettingsFragment (TYMAP)

Tài liệu này phân loại chi tiết toàn bộ các mục cài đặt hiện có trong giao diện `SettingsFragment` và tệp layout `fragment_settings.xml` của ứng dụng TYMAP, được nhóm theo từng CardView chủ đề và sắp xếp theo đúng thứ tự hiển thị từ trên xuống dưới.

---

## 1. Nhóm: Giao diện & Hiển thị (CardDisplay)
*   **Thứ tự ưu tiên:** 1 (Hiển thị đầu tiên)
*   **Bộ lọc danh mục:** Chung (General)
*   **Các mục cài đặt:**
    *   **Định dạng thời gian** (`spinnerTimeFormat`): Spinner (Lựa chọn 12h / 24h)
    *   **Đơn vị khoảng cách** (`spinnerUnits`): Spinner (Lựa chọn Hệ mét km/m / Hệ Anh mi/ft)

---

## 2. Nhóm: Cài đặt Bản đồ (CardMap)
*   **Thứ tự ưu tiên:** 2
*   **Bộ lọc danh mục:** Bản đồ (Map)
*   **Các mục cài đặt:**
    *   **Nguồn bản đồ nền** (`spinnerTileSource`): Spinner (CartoDB Positron, OSM Mapnik, CartoDB Dark Matter, CartoDB Voyager, Vệ tinh, Tùy chỉnh)
    *   **URL Bản đồ tùy chỉnh** (`etCustomTileUrl` trong `tilCustomTileUrl`): EditText (Chỉ hiển thị khi chọn nguồn "Tùy chỉnh")
    *   **Độ thu phóng mặc định** (`sliderDefaultZoom` hiển thị qua `tvValueDefaultZoom`): Slider (Phạm vi từ 10x đến 18x)
    *   **Tự động zoom khi đến ngã rẽ** (`switchAutoZoom`): Switch
    *   **Hướng xoay bản đồ** (`spinnerMapOrientation`): Spinner (Hướng Bắc / Hướng đi)
    *   **Ưu tiên bản đồ offline** (`switchOfflinePriority`): Switch
    *   **Quản lý bản đồ ngoại tuyến** (`btnManageOfflineMaps`): Button (Mở màn hình `OfflineMapActivity`)
    *   **Truyền tải cuốn chiếu (Tile Streaming)** (`switchTileStreaming`): Switch (Tự động ẩn/tắt khi kết nối màn hình OLED/không PSRAM)

---

## 3. Nhóm: Dẫn đường & Định tuyến (CardRouting)
*   **Thứ tự ưu tiên:** 3
*   **Bộ lọc danh mục:** Định tuyến (Routing)
*   **Các mục cài đặt:**
    *   **Máy chủ tính toán lộ trình** (`spinnerRoutingEngine`): Spinner (OSRM Demo, OpenRouteService, GraphHopper, Valhalla, Mapbox)
    *   **Phương tiện di chuyển** (`spinnerVehicleType`): Spinner (Ô tô / Xe máy)
    *   **Danh sách thứ tự fallback máy chủ** (`tvRoutingPriority`): TextView hiển thị mức độ ưu tiên fallback (Ví dụ: `Mapbox > GraphHopper > Valhalla > OSRM`)
    *   **Sắp xếp ưu tiên máy chủ** (`btnConfigurePriority`): Button (Mở hộp thoại Multi-choice sắp xếp thứ tự ưu tiên)
    *   **Khoảng cách báo chệch hướng** (`sliderOffRouteDist` hiển thị qua `tvValueOffRouteDist`): Slider (Từ 10m đến 50m)
    *   **Cảnh báo quá tốc độ** (`switchSpeedWarning`): Switch
    *   **Giới hạn tốc độ** (`etSpeedThreshold` trong `tilSpeedThreshold`): EditText số (Chỉ hiển thị khi bật "Cảnh báo quá tốc độ")

---

## 4. Nhóm: Âm thanh & Giọng nói (CardVoice)
*   **Thứ tự ưu tiên:** 4
*   **Bộ lọc danh mục:** Giọng nói (Voice)
*   **Các mục cài đặt:**
    *   **Bật chỉ dẫn giọng nói (TTS)** (`switchVoiceGuidance`): Switch
    *   **Âm lượng thông báo** (`sliderVoiceVolume` hiển thị qua `tvValueVoiceVolume`): Slider (Từ 0% đến 100%)
    *   **Kiểu chỉ dẫn** (`spinnerVoiceStyle`): Spinner (Chi tiết / Ngắn gọn / Rút gọn)
    *   **Cảnh báo chệch hướng** (`switchOffRouteAlert`): Switch (Thông báo giọng nói khi đi lạc)
    *   **Nhắc nhở tốc độ** (`switchSpeedWarningVoice`): Switch (Thông báo giọng nói khi quá tốc độ giới hạn)
    *   **Ngôn ngữ giọng nói** (`spinnerLanguage`): Spinner (Tiếng Việt / Tiếng Anh)

---

## 5. Nhóm: Kết nối & Dữ liệu ESP32 (CardEsp32Data)
*   **Thứ tự ưu tiên:** 5
*   **Bộ lọc danh mục:** Dữ liệu (Data)
*   **Các mục cài đặt:**
    *   **Chạy dịch vụ ngầm (TYMAP)** (`switchServiceStatus`): Switch (Kích hoạt/Dừng Foreground Service đồng bộ dữ liệu)
    *   **Chế độ truyền ảnh bản đồ** (`spinnerMapCaptureMode`): Spinner (Truyền ảnh OSM thô / Chụp màn hình Google Maps / Cắt tab Bản đồ)
    *   **Cấu hình cắt Google Maps** (`btnConfigCropGmaps` hiển thị qua `tvCropSummaryGmaps`): Button (Thiết lập bounding box crop Google Maps HUD)
    *   **Cấu hình cắt Tab Map** (`btnConfigCropMapTab` hiển thị qua `tvCropSummaryMapTab`): Button (Thiết lập bounding box crop màn hình Tab Map)
    *   **Tốc độ khung hình (FPS)** (`spinnerMapFps`): Spinner (Lựa chọn 1 FPS, 2 FPS, 5 FPS, 10 FPS - nằm trong `layoutJpegOptions`)
    *   **Chất lượng ảnh JPEG (%)** (`sliderJpegQuality` hiển thị qua `tvValueJpegQuality`): Slider (Từ 20% đến 100% - nằm trong `layoutJpegOptions`)
    *   **Bỏ qua khung hình trùng lặp (CRC32)** (`switchFrameSkipping`): Switch (Chỉ gửi frame khi có sự thay đổi pixel - nằm trong `layoutJpegOptions`)
    *   **Dừng truyền dữ liệu sau** (`spinnerStopAfterIdle` đi kèm `tvStopAfterIdle`): Spinner (Ẩn theo mặc định)
    *   **Chụp Maps HUD (Popup)** (`switchGmapsScreenshot`): Switch (Bật popup bản đồ đè lên HUD của xe)
    *   **K.cách báo rẽ lần 1** (`sliderPopupTrigger1` hiển thị qua `tvValuePopupTrigger1`): Slider (Từ 300m đến 1000m)
    *   **K.cách báo rẽ lần 2** (`sliderPopupTrigger2` hiển thị qua `tvValuePopupTrigger2`): Slider (Từ 50m đến 300m)
    *   **Thời gian hiện Popup** (`sliderPopupDuration` hiển thị qua `tvValuePopupDuration`): Slider (Từ 3 giây đến 10 giây)
    *   **Thời gian tự đóng HUD** (`sliderHudTimeout` hiển thị qua `tvValueHudTimeout`): Slider (Từ 3 giây đến 30 giây)

---

## 6. Nhóm: Màn hình OLED (C3) (CardOled)
*   **Thứ tự ưu tiên:** 6
*   **Bộ lọc danh mục:** Dữ liệu (Data)
*   **Các mục cài đặt:**
    *   **Ngưỡng đen trắng** (`sliderOledThreshold` hiển thị qua `tvValueOledThreshold`): Slider (Từ 0 đến 255)
    *   **Chế độ Dithering (Mịn ảnh)** (`switchOledDithering`): Switch (Thuật toán Floyd-Steinberg mịn hóa ảnh đen trắng)
    *   **Đảo ngược màu** (`switchOledInvert`): Switch (Đảo màu đen thành trắng và ngược lại)
    *   **Bộ lọc màu thông minh** (`switchOledColorFilter`): Switch
    *   **Mã màu HEX mục tiêu** (`etOledTargetColor` trong `tilOledTargetColor`): EditText (Chỉ hiển thị khi bật "Bộ lọc màu thông minh")
    *   **Nút chọn màu thông minh** (`btnOledColorPicker`): Button (Mở màn hình `OledColorFilterActivity`)
    *   **Dung sai màu** (`sliderOledTolerance` hiển thị qua `tvValueOledTolerance`): Slider (Từ 0% đến 100%)
    *   **Đảo kết quả lọc** (`switchOledFilterInvert`): Switch

---

## 7. Nhóm: Hệ thống (CardSystem)
*   **Thứ tự ưu tiên:** 7 (Hiển thị dưới cùng)
*   **Bộ lọc danh mục:** Chung (General)
*   **Các mục cài đặt:**
    *   **Cập nhật phần mềm (OTA)** (`btnOta`): Button
    *   **Hiển thị phiên bản ứng dụng** (`tvVersion`): TextView hiển thị phiên bản hiện hành của app (ví dụ: `Phiên bản: 1.0.0`)
