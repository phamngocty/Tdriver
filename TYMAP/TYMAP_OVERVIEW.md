# TÀI LIỆU TỔNG QUAN HỆ THỐNG DỰ ÁN TYMAP (ANDROID & ESP32 FIRMWARE)

Ứng dụng **TYMAP** là một giải pháp tích hợp phần cứng và phần mềm (Android Client + ESP32 Firmware), được thiết kế để kết nối và truyền tải dữ liệu chỉ dẫn bản đồ, định vị, thông tin thời tiết, thời gian và thông báo hệ thống lên thiết bị hiển thị kính lái HUD (Head-Up Display) chạy chip ESP32 thông qua giao thức Bluetooth Low Energy (BLE).

Dự án hỗ trợ hai dòng phần cứng hiển thị:
1. **ESP32-S3** kết hợp màn hình màu tròn TFT GC9A01 240x240px (hỗ trợ hiển thị giao diện HUD đồ họa màu sắc và ảnh bản đồ JPEG truyền từ app).
2. **ESP32-C3** kết hợp màn hình OLED SSD1306 128x64px (hiển thị giao diện HUD đen trắng tối giản và ảnh bản đồ dạng XBM 1bpp).

Tài liệu này mô tả chi tiết cấu trúc thư mục, các thành phần mã nguồn, giao thức BLE, sơ đồ chân phần cứng và hướng dẫn xây dựng dự án.

---

## 1. CẤU TRÚC THƯ MỤC & FILE

Dưới đây là sơ đồ cấu trúc thư mục toàn bộ dự án TYMAP bao gồm ứng dụng Android và hai dự án firmware PlatformIO:

```
TYMAP/
├── app/                              # Thư mục mã nguồn ứng dụng Android (Kotlin/Java)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/tymap/
│   │   │   │   ├── MainActivity.kt               # Activity chính điều hướng ứng dụng (ViewPager2)
│   │   │   │   ├── ShareReceiverActivity.kt      # Xử lý nhận link chia sẻ vị trí từ Google Maps
│   │   │   │   ├── ble/
│   │   │   │   │   ├── BleConstants.kt           # Định nghĩa các hằng số UUID GATT BLE
│   │   │   │   │   └── BleManager.kt             # Quản lý kết nối BLE và truyền dữ liệu chunk
│   │   │   │   ├── model/
│   │   │   │   │   └── OledFilter.kt             # Data class định nghĩa bộ lọc màu sắc cho màn hình OLED
│   │   │   │   ├── repository/
│   │   │   │   │   └── NavigationRepository.kt   # Singleton quản lý dữ liệu toàn cục qua Flow
│   │   │   │   ├── service/
│   │   │   │   │   ├── CropOverlayService.kt     # Dịch vụ overlay WindowManager để kéo thả khung cắt ảnh
│   │   │   │   │   ├── GMapsNotificationListener.kt # Lắng nghe và trích xuất chỉ dẫn Google Maps
│   │   │   │   │   ├── GmapsNotificationService.kt # Dịch vụ phụ trợ hứng thông báo Google Maps
│   │   │   │   │   ├── GpsManager.kt             # Đăng ký và cập nhật tọa độ định vị GPS/Network
│   │   │   │   │   ├── MyNotificationListenerService.kt # Chuyển tiếp thông báo ứng dụng khác sang BLE
│   │   │   │   │   ├── NavigationService.kt      # Foreground Service chính điều phối hoạt động định vị/bản đồ
│   │   │   │   │   ├── RoutingEngine.kt          # Gọi API định tuyến (OSRM, ORS, GraphHopper, Valhalla, Mapbox)
│   │   │   │   │   └── TtsManager.kt             # Điều phối phát Text-To-Speech giọng nói tiếng Việt
│   │   │   │   └── ui/
│   │   │   │       ├── ApiServiceAdapter.kt      # RecyclerView adapter quản lý danh sách API keys
│   │   │   │       ├── BluetoothDeviceAdapter.kt # RecyclerView adapter quản lý danh sách thiết bị BLE quét được
│   │   │   │       ├── ConnectionFragment.kt     # Fragment tab Kết nối, quét BLE và điều khiển thiết bị
│   │   │   │       ├── CropConfigActivity.kt     # Activity phụ trợ lưu cấu hình vùng cắt màn hình
│   │   │   │       ├── LogAdapter.kt             # RecyclerView adapter hiển thị logs hệ thống
│   │   │   │       ├── MainPagerAdapter.kt       # Adapter quản lý 4 tab Fragment của ViewPager2
│   │   │   │       ├── MapFragment.kt            # Fragment tab Bản đồ, hiển thị bản đồ OSM và dẫn đường
│   │   │   │       ├── NotificationAppAdapter.kt # RecyclerView adapter danh sách app bật/tắt thông báo
│   │   │   │       ├── NotificationsFragment.kt  # Fragment tab Thông báo, quản lý cấu hình nhận thông báo
│   │   │   │       ├── OledColorFilterActivity.kt # Activity chọn màu và lọc ảnh hiển thị cho màn hình OLED
│   │   │   │       ├── OledFilterAdapter.kt      # RecyclerView adapter danh sách bộ lọc màu OLED
│   │   │   │       ├── RouteAlternativeAdapter.kt # RecyclerView adapter danh sách tuyến đường thay thế
│   │   │   │       ├── SettingsFragment.kt       # Fragment tab Cài đặt cấu hình hiển thị, bản đồ, định tuyến
│   │   │   │       ├── SuggestionAdapter.kt      # Adapter hiển thị gợi ý tìm kiếm địa chỉ
│   │   │   │       └── theme/                    # Cấu hình màu sắc, typography và theme Material 3
│   │   │   └── res/
│   │   │       ├── layout/                   # Các tệp thiết kế giao diện XML của Android
│   │   │       └── AndroidManifest.xml       # Khai báo quyền, activities, services và intent filters
│   │   └── build.gradle.kts                  # Cấu hình build và dependencies của app Android
│   └── build.gradle.kts                      # Cấu hình build mức dự án Android
│
├── firmware/                         # Thư mục mã nguồn firmware cho vi điều khiển (C++)
│   ├── esp32_s3_gc9a01/                  # Dự án Firmware cho board ESP32-S3 + Màn hình tròn GC9A01
│   │   ├── platformio.ini                # Cấu hình môi trường PlatformIO, build flags, thư viện phụ thuộc
│   │   └── src/
│   │       ├── main.cpp                  # Khởi tạo BLE server, RTC, button, đo pin và vòng lặp loop chính
│   │       ├── gui.cpp                   # Cài đặt logic vẽ các giao diện HUD, STATUS, MENU, INFO, NOTIF
│   │       ├── gui.h                     # Định nghĩa hằng số font chữ, enum chế độ, structs và hàm GUI
│   │       └── logo.h                    # Lưu trữ bitmap logo khởi động định dạng RGB565 150x150
│   │
│   └── esp32_c3_oled/                    # Dự án Firmware cho board ESP32-C3 + Màn hình OLED SSD1306
│       ├── platformio.ini                # Cấu hình PlatformIO cho ESP32-C3
│       └── src/
│           └── main.cpp                  # Kết hợp BLE server, điều khiển hiển thị OLED u8g2 và xử lý phím bấm
│
├── docs/                             # Tài liệu tham khảo dự án
│   └── tong-quan-app.md              # Tài liệu mô tả nhanh app Android cũ
│
├── GEMINI.md                         # Tệp cấu hình quy tắc của Gemini IDE
├── settings.gradle.kts               # Khai báo các mô-đun trong Gradle
└── TYMAP_OVERVIEW.md                 # Tệp tài liệu tổng quan hệ thống này (tại gốc dự án)
```

---

## 2. CÁC MÀN HÌNH, FRAGMENT & GIAO DIỆN (ANDROID APP)

Ứng dụng được xây dựng theo kiến trúc Single Activity kết hợp ViewPager2 chứa 4 Fragment chính. Giao diện sử dụng View Binding để tương tác với các component XML.

### 2.1. MainActivity (`MainActivity.kt`)
*   **Chức năng chính:** Điểm khởi đầu của ứng dụng. Quản lý việc hiển thị ViewPager2 với 4 tab và đồng bộ với thanh BottomNavigationView dưới cùng. Đồng thời, tự động khởi động `NavigationService` dưới dạng Foreground Service ngay khi ứng dụng được tạo.
*   **Các View/Component chính:**
    *   `ViewPager2` (`binding.viewPager`): Lưu trữ và hiển thị các fragment. Vuốt chuyển tab được tắt (`isUserInputEnabled = false`), giữ 4 tab luôn trong bộ nhớ (`offscreenPageLimit = 2`).
    *   `BottomNavigationView` (`binding.bottomNavigation`): Menu chuyển đổi nhanh giữa các tab.
*   **Các phương thức quan trọng:**
    *   `onCreate()`: Áp dụng theme sáng/tối từ preference, khởi tạo view binding, cài đặt ViewPager2, BottomNavigationView, khởi động `NavigationService` và xử lý Intent.
    *   `applyTheme()`: Đọc cấu hình theme hệ thống và thiết lập `AppCompatDelegate.setDefaultNightMode`.
    *   `handleIntent()`: Nhận dữ liệu toạ độ/địa điểm được chuyển tiếp sang từ `ShareReceiverActivity` để tự động chuyển sang tab Bản đồ (vị trí 1).
    *   `onNewIntent()`: Cập nhật Intent mới khi activity đang chạy và tiếp tục gọi `handleIntent()`.
*   **Giao tiếp:** Khởi động và giao tiếp gián tiếp với `NavigationService` qua Intent.

### 2.2. ConnectionFragment (`ui/ConnectionFragment.kt`)
*   **Chức năng chính:** Quản lý kết nối Bluetooth Low Energy (BLE) với ESP32, cho phép cấu hình và kiểm tra API Key của các dịch vụ định tuyến, hiển thị bảng điều khiển thiết bị (chế độ, độ sáng, ping, restart) và xem trước dữ liệu HUD.
*   **Các View/Component chính:**
    *   `rvDevices` (RecyclerView): Hiển thị danh sách thiết bị BLE quét được (sử dụng `BluetoothDeviceAdapter`).
    *   `spinnerHistory` (Spinner): Hiển thị và chọn nhanh thiết bị BLE đã kết nối trong lịch sử.
    *   `rvApiServices` (RecyclerView): Danh sách các API dịch vụ định tuyến (sử dụng `ApiServiceAdapter`).
    *   `cardHudPreview` (CardView): Xem trước dữ liệu HUD đang truyền sang ESP32.
    *   `layoutDeviceInfo` (ViewGroup): Hiển thị RSSI, dung lượng pin và chế độ hiện tại của ESP32.
    *   Các nút điều khiển: `btnScan` (quét thiết bị), `btnDisconnect` (ngắt kết nối), `toggleEspMode` (chọn chế độ HUD/MAP/STATUS), `sliderBrightness` (độ sáng), `btnPing`, `btnRestartEsp`, `btnRemoteZoomIn`/`btnRemoteZoomOut`.
*   **Các phương thức quan trọng:**
    *   `onViewCreated()`: Cài đặt RecyclerViews, Spinner lịch sử, đăng ký click listeners cho các nút điều khiển và bắt đầu theo dõi Flows.
    *   `checkPermissionsAndScan()` / `startScanning()`: Yêu cầu quyền Bluetooth & GPS và bắt đầu quét BLE qua `BluetoothLeScanner` trong 10 giây.
    *   `connectToDevice()`: Lưu MAC thiết bị, cập nhật Spinner lịch sử ghép nối, khởi động `NavigationService` và gọi `connect()` qua BleManager.
    *   `testApiService()`: Gọi các hàm test HTTP (`testOrsKey`, `testGhKey`, `testMapboxKey`) để kiểm tra API key do người dùng nhập và cập nhật trạng thái tương ứng.
    *   `observeNavigationData()`: Theo dõi `hudPreviewData` và `deviceStatus` từ `NavigationRepository` để hiển thị bản xem trước chỉ dẫn cũng như cập nhật RSSI/pin thiết bị lên UI.
*   **Giao tiếp:** Gọi trực tiếp `NavigationService.bleManager` để gửi lệnh điều khiển. Cập nhật và nhận trạng thái thông qua `NavigationRepository`.

### 2.3. MapFragment (`ui/MapFragment.kt`)
*   **Chức năng chính:** Hiển thị bản đồ OpenStreetMap bằng thư viện osmdroid, quản lý vị trí người dùng, tìm kiếm địa chỉ, vẽ các tuyến đường định tuyến (bao gồm cả tuyến thay thế) và hiển thị chỉ dẫn khi dẫn đường chủ động.
*   **Các View/Component chính:**
    *   `mapView` (osmdroid MapView): Hiển thị bản đồ.
    *   `bottomSheet` (`bottom_sheet_navigation.xml`): Bảng thông tin nằm dưới, tự động trượt lên/xuống tương ứng với 3 trạng thái: Thông tin địa điểm (`layoutPlaceInfo`), Xem trước tuyến đường (`layoutRoutePreview`), Dẫn đường chủ động (`layoutNavigation`).
    *   `etSearch` (EditText) & `rvSuggestions` (RecyclerView): Nhập và hiển thị gợi ý tìm địa điểm.
    *   `userMarker` (Marker): Đánh dấu vị trí người dùng, tích hợp quạt chỉ hướng nhìn (Orientation Fan).
    *   Các nút: `fabLocation` (bám vị trí/bật Track-up), `fabLayers` (chọn nguồn bản đồ/bật capture), `fabZoomIn`/`fabZoomOut`, `btnCompass` (reset hướng bản đồ), `btnRecenter` (về giữa vị trí GPS khi dẫn đường).
*   **Các phương thức quan trọng:**
    *   `setupMap()`: Cấu hình MapView, khôi phục camera đã lưu, đăng ký cử chỉ và `MapEventsReceiver` (tap để ghim, long press để reverse geocode bằng Nominatim).
    *   `createUserIcon()`: Vẽ marker vị trí người dùng dạng chấm xanh và quạt gradient bán trong suốt thể hiện hướng la bàn của thiết bị.
    *   `performSearch()`: Gửi từ khóa tìm kiếm đến API Photon (chính) hoặc Nominatim (dự phòng) để lấy gợi ý địa điểm.
    *   `onPlaceSelected()`: Ghim điểm đến, hiển thị Bottom Sheet ở chế độ thông tin địa điểm (Place Info).
    *   `showRoutePreview()`: Lập lộ trình từ vị trí hiện tại đến đích (gọi `RoutingEngine` có fallback), hiển thị danh sách các tuyến đường thay thế (`RouteAlternativeAdapter`) và vẽ polylines tương ứng lên bản đồ.
    *   `drawRoutes()`: Vẽ các polyline lên bản đồ (tuyến được chọn màu xanh, các tuyến thay thế màu xám).
    *   `startNavigation()`: Khởi động `NavigationService` kèm tọa độ đích.
    *   `onOrientationChanged()`: Khi thiết bị thay đổi hướng và vận tốc GPS <= 1.2 m/s, xoay quạt marker và xoay bản đồ theo hướng la bàn (nếu đang ở chế độ Track-up).
    *   `observeNavigationData()` / `observeRemoteCommands()`: Theo dõi `gpsLocation` (cập nhật chấm xanh, bearing bản đồ, cảnh báo quá tốc độ), `routes` (vẽ polyline), `navigationState` (đổi trạng thái Bottom Sheet), `hudPreviewData` (hiển thị hướng rẽ/ETA) và `remoteZoomCommand` (phóng to/thu nhỏ bản đồ từ xa khi nhận lệnh từ ESP32).
*   **Giao tiếp:** Quan sát dữ liệu và điều phối trạng thái dẫn đường thông qua `NavigationRepository`.

### 2.4. SettingsFragment (`ui/SettingsFragment.kt`)
*   **Chức năng chính:** Cấu hình toàn bộ thiết lập trong ứng dụng (Hiển thị, Bản đồ, Định tuyến, Giọng nói, Gửi dữ liệu).
*   **Các View/Component chính:** Các Spinners (Theme, định dạng giờ, đơn vị, nguồn bản đồ, định tuyến engine, FPS, idle timeout), Sliders (zoom mặc định, khoảng cách lệch tuyến, âm lượng TTS, chất lượng JPEG, ngưỡng di chuyển, thời gian popup), Switches (auto zoom, cảnh báo tốc độ, giọng nói, chỉ gửi khi di chuyển, turn screenshot), nút `btnConfigCrop` (mở màn hình cấu hình crop).
*   **Các phương thức quan trọng:**
    *   `setupUI()`: Đọc các giá trị cài đặt cũ từ SharedPreferences qua PrefsHelper để gán trạng thái cho các View, đồng thời đăng ký lắng nghe thay đổi để lưu cài đặt mới.
    *   `spinnerMapCaptureMode` selection: Nếu chọn chụp màn hình Google Maps (chế độ 1), gọi `screenCaptureLauncher.launch()` để yêu cầu quyền `MediaProjection`.
    *   `showPriorityDialog()`: Hiển thị dialog cho phép tích chọn và thay đổi thứ tự ưu tiên fallback của các routing engine (`routing_priority`).
    *   `sliderPopupDuration` change: Khi kéo thời gian popup, tự động ghi thiết lập `"popupDuration=value"` sang ESP32 qua BLE ngay lập tức.
*   **Giao tiếp:** Đọc/ghi các giá trị cấu hình vào SharedPreferences.

### 2.5. NotificationsFragment (`ui/NotificationsFragment.kt`)
*   **Chức năng chính:** Cấu hình danh sách ứng dụng cài trên máy được phép chuyển tiếp thông báo đến thiết bị ESP32 qua BLE. Cung cấp nút gửi thử nghiệm thông báo.
*   **Các View/Component chính:** RecyclerView `rvNotificationApps` hiển thị danh sách ứng dụng và Switch tương ứng, nút `btnTestNotification`.
*   **Các phương thức quan trọng:**
    *   `setupRecyclerView()`: Đọc danh sách ứng dụng đã kích hoạt trong SharedPreferences (`enabled_notifications`), duyệt danh sách các app phổ biến cài trên máy (Điện thoại, SMS, Zalo, Facebook, Messenger, Viber, WhatsApp, Gmail) và hiển thị lên RecyclerView (sử dụng `NotificationAppAdapter`). Cập nhật StringSet cấu hình khi Switch thay đổi.
    *   `sendTestNotification()`: Đóng gói JSON thông báo thử nghiệm và gọi `bleManager.writeNotification()` để gửi sang ESP32.
*   **Giao tiếp:** Lưu cài đặt vào SharedPreferences cho `MyNotificationListenerService` đọc. Giao tiếp trực tiếp với `NavigationService.bleManager`.

### 2.6. CropConfigActivity (`ui/CropConfigActivity.kt`)
*   **Chức năng chính:** Cho phép người dùng cấu hình tọa độ vùng crop màn hình (vùng hiển thị Google Maps) dạng đơn giản. Tuy nhiên, trong phiên bản mới, tính năng kéo thả trực quan và thay đổi kích thước bằng hai ngón tay đã được chuyển sang `CropOverlayService`.
*   **Giao tiếp:** Đọc/ghi cấu hình crop vào SharedPreferences.

### 2.7. OledColorFilterActivity (`ui/OledColorFilterActivity.kt`)
*   **Chức năng chính:** Cung cấp giao diện cho phép người dùng chọn màu sắc từ ảnh chụp mẫu và cấu hình bộ lọc màu sắc (color filter) dùng để lọc bớt các chi tiết màu sắc không mong muốn khi convert ảnh chụp sang ảnh monochrome 1bpp hiển thị trên màn hình OLED.
*   **Các View/Component chính:**
    *   `ivSampleImage` (ImageView): Hiển thị ảnh chụp màn hình bản đồ mẫu (`sample_crop.png`).
    *   `vPickerPointer` (View): Con trỏ chọn màu kéo thả trên ảnh.
    *   `vCurrentColor` (View): Ô hiển thị màu đang chọn dưới dạng background màu đặc.
    *   `sliderTolerance` & `sliderDither` (Sliders): Điều chỉnh độ rộng màu lọc (dung sai) và độ dither ảnh.
    *   `rvFilters` (RecyclerView): Hiển thị danh sách các bộ lọc màu đã thiết lập (sử dụng `OledFilterAdapter`).
*   **Các phương thức quan trọng:**
    *   `onCreate()`: Tải các bộ lọc đã lưu từ SharedPreferences, nạp ảnh mẫu `sample_crop.png` từ bộ nhớ máy.
    *   `ivSampleImage.setOnTouchListener()`: Nhận sự kiện chạm của người dùng, lấy pixel màu tại tọa độ tương ứng trên bitmap mẫu để cập nhật `selectedColor` và di chuyển vòng tròn pointer.
    *   `btnAddFilter` click: Đóng gói thông tin màu sắc, dung sai (`tolerance`), độ dither (`dither`) vào đối tượng `OledFilter`, thêm vào danh sách và lưu lại.
*   **Giao tiếp:** Đọc/ghi cấu hình bộ lọc dạng JSON vào SharedPreferences qua `PrefsHelper`.

---

## 3. DỊCH VỤ NỀN & BACKGROUND PROCESSES (ANDROID APP)

Ứng dụng sử dụng các dịch vụ chạy nền để duy trì kết nối BLE, định vị GPS liên tục, chụp màn hình Google Maps và lắng nghe thông báo hệ thống ngay cả khi điện thoại tắt màn hình.

### 3.1. NavigationService (Foreground Service)
*   **Loại dịch vụ:** Foreground Service (`startForegroundService`). Được khai báo loại (foregroundServiceType) là `location | connectedDevice | mediaProjection` trong Manifest để tuân thủ chính sách Android 14+.
*   **Chức năng chính:** Trung tâm điều phối chính chạy nền của ứng dụng. Thực hiện nhận toạ độ GPS, tính toán lộ trình, theo dõi chệch hướng, cảnh báo quá tốc độ, đồng bộ thời tiết, thời gian và chạy vòng lặp dựng bản đồ/chụp màn hình để truyền sang ESP32 qua BLE.
*   **Các thành phần điều phối:**
    *   `MyBleManager` (`bleManager`): Giao tiếp BLE với ESP32.
    *   `GpsManager` (`gpsManager`): Đăng ký nhận toạ độ GPS.
    *   `TtsManager` (`ttsManager`): Phát âm thanh cảnh báo bằng tiếng Việt.
    *   `ScreenCaptureManager` (`screenCaptureManager`): Chụp màn hình khi ở chế độ chụp Google Maps.
    *   `headlessMapView` (MapView): MapView ẩn kích thước 240x240px dùng để dựng bản đồ OSM chạy nền.
*   **Vòng đời:**
    *   `onCreate()`: Khởi tạo `bleManager`, `gpsManager`, `ttsManager`. Cài đặt `headlessMapView` ẩn với nguồn tile Mapnik và đăng ký `gmapsHudReceiver` nhận broadcast dữ liệu điều hướng từ Google Maps.
    *   `onStartCommand()`: Chạy `startForeground` hiển thị notification duy trì dịch vụ. Kích hoạt cập nhật định vị GPS, weather/time sync, bắt đầu vòng lặp dựng bản đồ nền (`startMapRenderingLoop`), chạy logic cảnh báo tốc độ/lệch tuyến (`startNavigationLogic`), lắng nghe yêu cầu tải icon từ ESP32 (`observeIconRequests`), lắng nghe map mode (`observeMapMode`). Khởi tạo chụp màn hình nếu nhận được MediaProjection intent qua action `ACTION_START_CAPTURE`. Nếu nhận toạ độ đích từ Intent, tự động tính toán lộ trình đầu tiên (`fetchInitialRoute`). Trả về `START_STICKY`.
    *   `onDestroy()`: Hủy đăng ký broadcast receiver, hủy `serviceScope` (dừng các coroutine đồng bộ), dừng cập nhật GPS, ngắt kết nối BLE, tắt TTS, đặt cờ dẫn đường về false.

### 3.2. CropOverlayService (Bound/Started Service)
*   **Loại dịch vụ:** Started Service. Yêu cầu quyền `SYSTEM_ALERT_WINDOW` (Hiển thị trên các ứng dụng khác).
*   **Chức năng chính:** Tạo một khung vẽ (overlay view) đè lên toàn bộ màn hình hệ thống (bao gồm cả khi người dùng mở ứng dụng Google Maps). Người dùng có thể kéo di chuyển vị trí khung và dùng 2 ngón tay thu phóng kích thước khung để khớp chính xác với vùng hiển thị bản đồ trên Google Maps. Khi nhấn Lưu, tọa độ và tỉ lệ chuẩn hóa sẽ được tính toán để lưu lại cho `ScreenCaptureManager` thực hiện chụp màn hình chính xác.
*   **Các phương thức quan trọng:**
    *   `onCreate()`: Lấy `WindowManager`, inflate layout `activity_crop_config.xml` và thêm trực tiếp vào màn hình với layout params `TYPE_APPLICATION_OVERLAY`.
    *   `setupDragAndScaleLogic()`: Gán `ScaleGestureDetector` để phát hiện thao tác thu phóng bằng hai ngón tay, cập nhật trực tiếp `layoutParams.width` và `.height` của khung nét đứt. Đồng thời đăng ký `OnTouchListener` để di chuyển khung theo ngón tay (`cropFrame.x` và `cropFrame.y`).
    *   `saveConfiguration()`: Lưu tọa độ tuyệt đối và tọa độ chuẩn hóa chia cho chiều rộng/cao màn hình vào SharedPreferences. Đồng thời ra lệnh chụp thử một ảnh mẫu để lưu vào bộ nhớ máy làm ảnh nền cấu hình bộ lọc màu OLED.

### 3.3. GMapsNotificationListener (NotificationListenerService)
*   **Loại dịch vụ:** Bound Service, đăng ký permission `BIND_NOTIFICATION_LISTENER_SERVICE` trong Manifest.
*   **Chức năng chính:** Lắng nghe thông báo từ Google Maps điều hướng và chuyển tiếp các thông báo thông thường khác từ các ứng dụng được phép sang thiết bị ESP32 qua BLE.
*   **Các phương thức quan trọng:**
    *   `onNotificationPosted()`: Nhận sự kiện khi có thông báo mới xuất hiện.
        *   Nếu là thông báo của Google Maps (`com.google.android.apps.maps`): Gọi `handleGoogleMapsNotification` để lấy title/text của chỉ dẫn rẽ (nhưng logic này hiện rỗng).
        *   Nếu là thông báo của ứng dụng khác: Nếu cấu hình `forward_notifications` bật, kiểm tra ứng dụng có thuộc danh sách `allowed_notification_apps` (hoặc danh sách trống là cho phép tất cả). Nếu được phép, gọi `forwardNotification()` để lấy nhãn app, title, text, chuyển đổi sang JSON và gửi qua BLE tới ESP32 bằng `NavigationService.bleManager?.writeNotification()`.

### 3.4. MyNotificationListenerService (NotificationListenerService)
*   **Loại dịch vụ:** Bound Service, đăng ký permission `BIND_NOTIFICATION_LISTENER_SERVICE`.
*   **Chức năng chính:** Đọc thông báo từ điện thoại và chuyển tiếp thông báo của các ứng dụng được người dùng chọn sang thiết bị ESP32 qua BLE dưới dạng JSON.
*   **Các phương thức quan trọng:**
    *   `onNotificationPosted()`: Nhận sự kiện có thông báo. Lấy danh sách ứng dụng đã bật trong SharedPreferences (`enabled_notifications`). Nếu package trùng khớp, lấy nhãn app, tiêu đề (`android.title`), nội dung (`android.text`), đóng gói thành map JSON `{"app":..., "title":..., "message":...}` và gọi `bleManager?.writeNotification` gửi sang ESP32.

---

## 4. THÀNH PHẦN FIRMWARE ESP32

Dự án firmware hỗ trợ hai biến thể phần cứng hiển thị khác nhau.

### 4.1. Firmware ESP32-S3 với màn hình màu TFT GC9A01 (`firmware/esp32_s3_gc9a01`)
Sử dụng màn hình LCD tròn IPS 240x240px điều khiển qua chip GC9A01 bằng giao tiếp SPI tốc độ cao (40 MHz). Hỗ trợ dựng đồ họa màu sắc và giải mã hiển thị ảnh JPEG truyền qua BLE.

*   **`main.cpp`**
    *   *Chức năng chính:* Điểm khởi nhập hệ thống. Khởi tạo ngoại vi (Serial, PSRAM, LCD, phím bấm, ADC đo pin), thiết lập BLE GATT Server với 14 đặc tính, chạy màn hình loading logo khởi động, và điều phối các lệnh hệ thống.
    *   *Các phương thức quan trọng:*
        *   `setup()`: Khởi tạo phần cứng, cấu hình PWM điều khiển đèn nền (Backlight), cấp phát 32KB buffer JPEG trong PSRAM, chạy Intro logo 2.5 giây, khởi tạo NimBLE, gán các hàm callback cho sự kiện ghi (Write) các đặc tính BLE và cấu hình ngắt phím bấm.
        *   `loop()`: Quét trạng thái phím bấm qua `btnMode.tick()` và `btnZoom.tick()`, cập nhật điện áp pin, theo dõi timeout của dẫn đường HUD để tự động chuyển về màn hình STATUS, đóng menu nếu quá 5 giây không thao tác, và vẽ lại giao diện tương ứng với `currentMode` hiện tại.
        *   `ServerCallbacks::onWrite()`: Tiếp nhận dữ liệu ghi vào đặc tính BLE từ điện thoại:
            *   `CHA_NAV_UUID`: Phân tích chuỗi chỉ dẫn đường (khoảng cách, ETA, tên đường) dòng-dòng. Tự chuyển chế độ sang `HUD_MODE` nếu có chỉ dẫn mới.
            *   `CHA_NAV_TBT_ICON_UUID`: Nhận hash hex của icon. Nếu khớp cache thì nạp vẽ ngay, nếu không thì gửi thông báo yêu cầu điện thoại gửi bitmap của icon qua `CHA_DEVICE_STATUS_UUID`.
            *   `CHA_ICON_DATA_UUID`: Nhận và lưu bitmap icon custom 288 bytes mới tải vào cache FIFO (tối đa 50 icon).
            *   `CHA_MAP_IMAGE_UUID`: Nhận kích thước JPEG, sau đó gom các chunk ảnh truyền tiếp theo vào buffer. Khi nhận đủ, gọi `renderJpegImage()` để giải mã và hiển thị.
            *   `CHA_REMOTE_CMD_UUID`: Nhận các lệnh hệ thống (Chuyển chế độ, ping, restart).
            *   `CHA_NOTIFICATION_UUID`: Nhận thông báo JSON, đẩy vào mảng FIFO 3 thông báo, kích hoạt chế độ `NOTIF_MODE` hiển thị popup trong 5 giây.
            *   `CHA_PHONE_BATTERY_UUID`: Nhận dung lượng pin điện thoại và trạng thái sạc.
        *   `renderJpegImage()`: Sử dụng thư viện `JPEGDEC` giải mã mảng JPEG trực tiếp lên màn hình, vẽ đè các thông tin `MapOverlay` (ETA, la bàn) lên trên ảnh bản đồ.
        *   `sendDeviceStatus()`: Gửi chuỗi trạng thái (`mode=...`, `voltage=...`, `rssi=...`, `timeSynced=...`) sang app điện thoại.
*   **`gui.cpp` / `gui.h`**
    *   *Chức năng chính:* Quản lý toàn bộ đồ họa vẽ màn hình.
    *   *Các giao diện hiển thị:*
        *   `drawHUD()`: Vẽ khoảng cách rẽ lớn ở dưới, icon hướng rẽ 96x96px ở tâm, tên đường ở giữa, và thông tin thời gian/ETA ở đỉnh màn hình.
        *   `drawSTATUS()`: Hiển thị đồng hồ số lớn (Giờ:Phút:Giây), ngày tháng tiếng Việt, nhiệt độ/thời tiết hiện tại, dung lượng pin điện thoại và điện áp ắc quy.
        *   `drawMenuOverlay()`: Giao diện chọn chế độ dạng tròn (radial menu) 5 phân đoạn tương ứng 5 hướng. Phân đoạn được chọn sẽ có cung tròn bao ngoài màu xanh lá rực rỡ và biểu tượng icon chuyển sang màu xanh lá.
        *   `drawINFO()`: Hiển thị chi tiết trạng thái kết nối BLE, đồng bộ RTC, mức pin điện thoại (kèm biểu tượng pin sạc ⚡ vẽ thủ công), dung lượng cache icon hiện tại và thông số phiên bản.
        *   `drawNOTIF()`: Hiển thị trang chứa danh sách 3 thông báo, hỗ trợ tự động xuống dòng chữ thông minh ôm theo khung tròn màn hình nhờ hàm `printWrappedText()`.
        *   `drawLogoWithLoadingBar()`: Vẽ ảnh logo RGB565 150x150px kèm thanh tiến trình màu xanh lá chạy dưới.
*   **`logo.h`**
    *   *Chức năng chính:* Mảng byte tĩnh chứa dữ liệu điểm ảnh RGB565 của logo TYMAP 150x150px (45,000 bytes).

### 4.2. Firmware ESP32-C3 với màn hình OLED SSD1306 (`firmware/esp32_c3_oled`)
Sử dụng màn hình OLED SSD1306 128x64px đơn sắc giao tiếp qua I2C. Firmware được tối giản hóa tối đa, không hỗ trợ bộ nhớ ngoài PSRAM, không giải mã JPEG mà nhận trực tiếp ảnh monochrome packed 1bpp (1024 bytes) từ app điện thoại để vẽ nhanh lên màn hình bằng thư viện `U8g2`.

*   **`main.cpp`**
    *   *Chức năng chính:* Điểm khởi nhập và điều khiển toàn bộ thiết bị OLED.
    *   *Các phương thức quan trọng:*
        *   `setup()`: Khởi tạo I2C trên hai chân (SDA=8, SCL=9), khởi tạo OLED u8g2, cấu hình BLE GATT Server với 13 đặc tính giao tiếp, và gán ngắt phím bấm.
        *   `loop()`: Nhận và lưu dữ liệu chỉ dẫn, vẽ lại màn hình theo chế độ, tự động quay lại STATUS khi mất dẫn đường quá 3 giây.
        *   `ServerCallbacks::onWrite()`: Nhận dữ liệu BLE tương tự như bản S3. Đặc biệt:
            *   `CHA_OLED_IMAGE_UUID`: Nhận kích thước ảnh (2 bytes), sau đó gom các chunk dữ liệu ảnh 1bpp (kích thước 1024 bytes) gửi từ điện thoại. Khi nhận đủ, đặt cờ vẽ ảnh lên màn hình.
        *   `drawHUD()`: Vẽ icon rẽ nhỏ (48x48px custom bitmap hoặc vẽ thủ công bằng vector hình tròn/mũi tên rẽ trái, rẽ phải nếu không có custom bitmap), in khoảng cách, tên đường (tối đa 11 ký tự), tốc độ GPS và ETA.
        *   `drawSTATUS()`: Hiển thị đồng hồ lớn, dòng nhiệt độ thời tiết và điện áp pin xe. Nếu có thông báo mới trong vòng 8 giây, vẽ khung popup hiển thị tiêu đề và nội dung thông báo.
        *   `drawMenuOverlay()`: Vẽ menu lựa chọn chế độ gồm 3 dòng text hiển thị, dòng đang chọn sẽ vẽ một hộp chữ nhật màu trắng bao quanh và đổi chữ thành màu đen tương phản.

---

## 5. VÒNG ĐỜI & LUỒNG HOẠT ĐỘNG CHÍNH

### 5.1. Vòng đời hoạt động của ứng dụng Android
```
[App Khởi động] ──> Khởi chạy MainActivity ──> Khởi động NavigationService (Foreground)
                                                        │
         ┌──────────────────────────────────────────────┴──────────────────────────────┐
         ▼ (Quan sát trạng thái BLE)                                                   ▼ (Quan sát trạng thái dẫn đường)
[Quét & Kết nối BLE] ──> Đồng bộ giờ/thời tiết                         [Nhập điểm đến & Định tuyến]
         │                                                                             │
         ▼                                                                             ▼
[Gửi lệnh RemoteCmd] ──> ESP32 chuyển chế độ                                  [Bắt đầu dẫn đường chủ động]
                                                                                       │
 ┌─────────────────────────────────────────────────────────────────────────────────────┤
 │                                                                                     │
 ▼ (Chế độ HUD)                                                                        ▼ (Chế độ MAP)
Dữ liệu chỉ dẫn (NextStep) gửi liên tục sang BLE                             Khởi động Map Rendering Loop nền
                                                                                       │
                                                                                       ▼
                                                                             Headless MapView dựng ảnh 240x240px
                                                                                       │
                                                                                       ▼
                                                                             Chuyển sang mảng byte JPEG
                                                                                       │
                                                                                       ▼
                                                                             Gửi chunk sang đặc tính BLE MAP_IMAGE
```

### 5.2. Vòng đời hoạt động của Firmware ESP32
```
[Cấp điện] ──> setup() ──> Intro Logo & Loading Bar (2.5s) ──> Khởi tạo NimBLE & Button ──> STATUS_MODE (Chế độ mặc định)
                                                                                               │
   ┌───────────────────────┬───────────────────────────────┬───────────────────────────┤ (Vòng lặp loop() chính quét phím)
   ▼                       ▼                               ▼                           ▼
[Nhấn Single click]     [Nhấn Long Press]            [Nhận dữ liệu NAV]          [Nhận dữ liệu NOTIF]
   │                       │                               │                           │
   ▼                       ▼                               ▼                           ▼
Mở Menu chọn chế độ    Xác nhận chọn chế độ            Tự chuyển sang HUD_MODE    Tự chuyển sang NOTIF_MODE (5s)
(Hoặc chuyển mục)      (HUD/MAP/STATUS...)             (Cập nhật thông tin rẽ)     (Hiển thị popup rồi tự tắt)
```

---

## 6. GIAO TIẾP & BLE GATT PROFILE

Hệ thống sử dụng dịch vụ GATT BLE duy nhất với UUID: `0000feed-0000-1000-8000-00805f9b34fb`.

### 6.1. Chi tiết các GATT Characteristics

| Tên Đặc Tính | UUID | Hướng Truyền | Định Dạng Dữ Liệu | Mục Đích Sử Dụng |
| :--- | :--- | :--- | :--- | :--- |
| **CHA_NAV** | `0b11deef-1563-447f-aece-d3dfeb1c1f20` | Phone $\rightarrow$ ESP32 | Chuỗi text: `dist=...\ninst=...\nroad=...\neta=...\nete=...` hoặc `active=1\nnav=1\ndist=...\ntitle=...\ndir=...\neta=...` | Gửi dữ liệu dẫn đường (quãng đường đến chỗ rẽ, chỉ dẫn lượt rẽ, tên đường, ETA, thời gian còn lại). |
| **CHA_NAV_TBT_ICON** | `d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad` | Phone $\rightarrow$ ESP32 | Byte (chỉ số icon) hoặc ByteArray (288 bytes) hoặc chuỗi `hash=[CRC32_Hex]` | Gửi biểu tượng lượt rẽ. Hỗ trợ truyền byte chỉ số icon rẽ chuẩn hóa (0-20), truyền chuỗi băm hex để thiết bị đối chiếu cache, hoặc truyền dữ liệu bitmap icon 1bpp trực tiếp. |
| **CHA_GPS_SPEED** | `98b6073a-5cf3-4e73-b6d3-f8e05fa018a9` | Phone $\rightarrow$ ESP32 | Chuỗi số nguyên (ví dụ: `"50"`) | Đồng bộ tốc độ GPS thời gian thực (km/h) từ điện thoại sang màn hình HUD. |
| **CHA_SETTINGS** | `9d37a346-63d3-4df6-8eee-f0242949f59f` | Phone $\rightarrow$ ESP32 | Chuỗi thiết lập: `brightness=[0-100]` hoặc `popupDuration=[value]` hoặc `hudTimeout=[value]` | Gửi các thiết lập cấu hình độ sáng màn hình hoặc thời gian hiển thị popup sang thiết bị. |
| **CHA_TIME** | `a1b2c3d4-e5f6-4789-a012-3456789abcde` | Phone $\rightarrow$ ESP32 | 4 bytes (Little Endian, Unix timestamp dạng giây) | Đồng bộ đồng hồ thời gian của điện thoại sang ESP32. |
| **CHA_WEATHER** | `b2c3d4e5-f6a7-4890-b123-456789abcdef` | Phone $\rightarrow$ ESP32 | Chuỗi JSON: `{"t": [temperature], "i": "[icon_code]"}` | Truyền dữ liệu thời tiết (nhiệt độ, icon đại diện) lấy từ API Open-Meteo. |
| **CHA_MAP_IMAGE** | `c3d4e5f6-a7b8-4901-c234-567890abcdef` | Phone $\rightarrow$ ESP32 | 4 bytes size (Little Endian) + Các chunks ảnh JPEG kế tiếp | Truyền ảnh bản đồ dạng JPEG sang thiết bị ESP32-S3. |
| **CHA_DEVICE_CTRL** | `d4e5f6a7-b8c9-4012-d345-678901bcdef0` | Hai chiều (Phone Write / ESP32 Notify) | Ghi: 1 byte (1: zoom in, 2: zoom out). Nhận: 1 byte bitfield | Zoom thủ công từ app hoặc nhận lệnh tương tác từ ESP32 (bit 0=zoomIn, bit 1=zoomOut, bit 2=mapMode, bit 3=exitPopup/refreshMap). |
| **CHA_REMOTE_CMD** | `f1a2b3c4-d5e6-4789-a012-3456789abcde` | Phone $\rightarrow$ ESP32 | 1 byte: `0x10`(HUD), `0x11`(MAP), `0x12`(STATUS), `0x13`(INFO), `0x14`(NOTIF), `0x20`(Refresh/ReqStatus), `0x30`(Ping), `0xFF`(Restart) | Gửi các lệnh điều khiển hệ thống trực tiếp từ xa sang ESP32. |
| **CHA_DEVICE_STATUS** | `a1b2c3d4-e5f6-4789-b012-3456789abcde` | ESP32 $\rightarrow$ Phone (Notify) | Chuỗi văn bản dạng `key=value\n` | Nhận thông báo trạng thái của ESP32 (chế độ, điện áp pin, RSSI, kết quả ping, yêu cầu tải icon `icon_req=CRC32_Hex`). |
| **CHA_ICON_DATA** | `e2f3a4b5-c6d7-4890-e123-456789abcdef` | Phone $\rightarrow$ ESP32 | 4 bytes hash (Little Endian) + ByteArray dữ liệu bitmap icon 1bpp | Truyền dữ liệu bitmap của biểu tượng chỉ dẫn khi ESP32 yêu cầu tải qua `icon_req`. |
| **CHA_OLED_IMAGE** | `e1f2a3b4-c5d6-4789-a012-3456789abcdef` | Phone $\rightarrow$ ESP32 | 2 bytes size (Short, Little Endian) + Các chunks bitmap 1bpp (1024 bytes) | Truyền tải ảnh hiển thị cho màn hình OLED ESP32-C3. |
| **CHA_NOTIFICATION** | `c1d2e3f4-a5b6-4789-c012-3456789abcdef` | Phone $\rightarrow$ ESP32 | Chuỗi JSON: `{"app":..., "title":..., "message":...}` | Chuyển tiếp các thông báo điện thoại sang thiết bị để hiển thị lên màn hình HUD. |
| **CHA_PHONE_BATTERY** | `e5f6a7b8-c9d0-4123-e456-789012cdef01` | Phone $\rightarrow$ ESP32 | Chuỗi JSON: `{"level":..., "charging":...}` hoặc Text: `[level],[charging]` | Đồng bộ dung lượng pin điện thoại và trạng thái sạc lên thiết bị ESP32-S3. |

### 6.2. Cơ chế truyền ảnh dạng Chunks qua BLE
Để gửi các ảnh dung lượng lớn (JPEG bản đồ hoặc bitmap OLED) mà không bị giới hạn bởi kích thước MTU tối đa của BLE:
1.  **Bước 1 (Gửi kích thước):** Điện thoại thực hiện ghi kích thước ảnh dạng số nguyên (4 bytes đối với JPEG trên `CHA_MAP_IMAGE` hoặc 2 bytes đối với OLED trên `CHA_OLED_IMAGE`).
2.  **Bước 2 (Thiết lập buffer):** ESP32 nhận được kích thước, tiến hành đặt số lượng byte mong đợi `jpegSize` / `oledImageSize`, reset biến đếm nhận `jpegWritten` / `oledImageWritten`, và thiết lập cờ đang nhận ảnh.
3.  **Bước 3 (Gửi chunk dữ liệu):** Điện thoại chia nhỏ dữ liệu ảnh thành các gói nhỏ (kích thước tối đa bằng `MTU - 3` bytes, mặc định MTU yêu cầu là 512 bytes nên kích thước chunk thường là 509 bytes) và ghi liên tiếp không cần phản hồi (`WRITE_TYPE_NO_RESPONSE`).
4.  **Bước 4 (Giải mã và dựng):** Khi ESP32 đếm đủ số lượng byte nhận được khớp với kích thước ban đầu, cờ nhận ảnh được hạ xuống, và thiết bị tiến hành giải mã dữ liệu hiển thị lên màn hình.

### 6.3. Cơ chế Cache Icon chỉ dẫn (Custom Turn-by-Turn Icons)
Nhằm tối ưu hóa băng thông BLE, tránh truyền lại bitmap icon chỉ dẫn (288 bytes) của các khúc cua quen thuộc:
1.  **Tính toán Hash:** Điện thoại tính toán mã băm CRC32 của icon chỉ dẫn dưới dạng chuỗi Hex 8 ký tự (ví dụ: `AABBCCDD`).
2.  **Truy vấn cache:** Điện thoại ghi chuỗi băm hex qua đặc tính `CHA_NAV_TBT_ICON` dạng `"hash=AABBCCDD"`.
3.  **Yêu cầu dữ liệu (nếu thiếu):** ESP32 kiểm tra mảng cache lưu trữ trong RAM. Nếu không tìm thấy, ESP32 sẽ ghi thông báo `"icon_req=AABBCCDD"` vào đặc tính `CHA_DEVICE_STATUS` và phát tín hiệu Notify về điện thoại.
4.  **Gửi dữ liệu bitmap:** Điện thoại bắt được yêu cầu tải icon, tiến hành đóng gói 4 bytes mã băm (dạng nhị phân) + 288 bytes dữ liệu bitmap 1bpp của icon rẽ (kích thước 48x48px) gửi qua đặc tính `CHA_ICON_DATA`.
5.  **Cập nhật cache FIFO:** ESP32 nhận được dữ liệu, ghi đè vào bộ đệm cache theo cơ chế FIFO (dung lượng tối đa 50 icon trên bản S3, 20 icon trên bản C3) và thực hiện vẽ icon lên màn hình. ESP32 cũng gửi lại thông báo `"icon_ack=AABBCCDD"` xác nhận thành công.

---

## 7. CÁC TÍNH NĂNG CHÍNH ĐÃ CÓ

### 7.1. Bản đồ & Định vị (Map & GPS)
*   **Hiển thị bản đồ:** Sử dụng thư viện nguồn mở **osmdroid** hiển thị bản đồ trên `MapView` của tab Bản đồ, loại bỏ sự phụ thuộc vào Google Maps SDK trả phí.
*   **Nguồn bản đồ đa dạng:** Hỗ trợ 6 loại nguồn bản đồ (CartoDB Positron, OSM Mapnik, CartoDB Dark Matter, CartoDB Voyager, ESRI Satellite và Tùy chỉnh URL tile).
*   **Định vị thông minh:** Tích hợp la bàn điện thoại để xoay bản đồ theo hướng di chuyển thực tế (Track-up mode) và hiển thị hình quạt định hướng nhìn của người dùng.
*   **Tìm kiếm địa điểm (Geocoding):** Gửi yêu cầu tìm kiếm đến API Photon (komoot) và tự động chuyển sang Nominatim làm dự phòng nếu Photon lỗi.

### 7.2. Định tuyến & Dẫn đường (Routing & Navigation)
*   **Hỗ trợ 5 Engine định tuyến:** Tích hợp OSRM, OpenRouteService (ORS), GraphHopper, Valhalla và Mapbox.
*   **Cơ chế Fallback thông minh:** Khi tính toán tuyến đường, nếu công cụ định tuyến được chọn gặp lỗi kết nối hoặc giới hạn lượt gọi, hệ thống sẽ tự động thử lần lượt các công cụ tiếp theo trong danh sách ưu tiên cấu hình.
*   **Cảnh báo lệch tuyến (Off-route):** Tính toán khoảng cách vuông góc từ vị trí GPS đến tuyến đường đang đi. Nếu vượt ngưỡng chệch hướng, app sẽ phát cảnh báo giọng nói tiếng Việt bằng TTS và tự động định tuyến lại lộ trình mới.
*   **Cảnh báo quá tốc độ:** Lấy vận tốc GPS hiện tại so sánh với giới hạn tốc độ thiết lập. Nếu chạy quá tốc độ, biểu tượng cảnh báo sẽ nhấp nháy trên màn hình và TTS phát cảnh báo bằng giọng nói.
*   **Chụp màn hình Google Maps:** Khi dẫn đường bằng Google Maps và sắp đến khúc cua (cách 500m hoặc 200m), app sẽ chụp màn hình Google Maps đang chạy nổi, cắt lấy vùng bản đồ và nén JPEG gửi sang ESP32.

### 7.3. Đồng bộ Trạng thái & Điều khiển thiết bị (Device Control & Sync)
*   **Đồng bộ RTC:** Đồng bộ đồng hồ thời gian thực Unix timestamp của điện thoại sang ESP32 mỗi khi kết nối thành công.
*   **Đồng bộ thời tiết:** Sử dụng tọa độ GPS hiện tại để gọi API thời tiết Open-Meteo và đóng gói gửi nhiệt độ cùng mã biểu tượng thời tiết sang thiết bị hiển thị.
*   **Đồng bộ dung lượng pin:** Tự động gửi trạng thái pin của điện thoại và trạng thái cắm sạc sang ESP32-S3 để cập nhật lên giao diện STATUS và INFO.
*   **Đo điện áp pin xe:** ESP32 đo điện áp ắc quy/pin nguồn thông qua chân ADC nối cầu phân áp để hiển thị và gửi thông báo điện áp về app điện thoại.
*   **Điều chỉnh độ sáng:** Người dùng kéo thanh trượt trên app để thay đổi độ sáng màn hình ESP32 qua lệnh PWM (0-100%).
*   **Tương tác từ xa (Remote Zoom):** Khi ở chế độ hiển thị bản đồ, người dùng nhấn phím Zoom trên thiết bị ESP32, lệnh điều khiển sẽ được truyền qua BLE tới app điện thoại để phóng to hoặc thu nhỏ mức zoom của bản đồ theo thời gian thực.

### 7.4. Chuyển tiếp Thông báo & Chia sẻ vị trí (Notifications & Share)
*   **Đọc thông báo hệ thống:** `MyNotificationListenerService` bắt lấy các thông báo mới của các ứng dụng được cấp phép (Zalo, Messenger, SMS...) và chuyển tiếp nội dung JSON qua BLE để hiển thị lên màn hình HUD.
*   **Hứng chia sẻ vị trí:** Khi người dùng nhấn chia sẻ một địa điểm từ ứng dụng Google Maps sang TYMAP, `ShareReceiverActivity` tiếp nhận liên kết, giải mã đường dẫn rút gọn để lấy tọa độ điểm đến chính xác và chuyển sang tab Bản đồ của TYMAP để tự động vẽ lộ trình dẫn đường.

### 7.5. Bộ lọc màu sắc cho OLED (OLED Color Filter)
*   **Giải pháp cho màn hình đơn sắc:** Khi chuyển ảnh bản đồ sang màn hình OLED 128x64px đơn sắc, các chi tiết màu sắc khác nhau (như nền bản đồ, đường đi, sông ngòi, công viên) thường bị trộn lẫn hoặc biến mất khi chuyển nhị phân.
*   **Cơ chế hoạt động:** Cho phép người dùng ghim các điểm màu đặc trưng trên ảnh chụp bản đồ mẫu (ví dụ: ghim màu nền xám, màu sông xanh dương), thiết lập dung sai màu và độ dither tương ứng. Bộ lọc sẽ chuyển đổi tất cả các pixel nằm trong vùng màu này thành màu đen (hoặc trắng) đồng nhất, giúp hiển thị rõ ràng đường đi màu tương phản trên màn hình OLED.

---

## 8. THƯ VIỆN & DEPENDENCIES

### 8.1. Thư viện phía ứng dụng Android (`app/build.gradle.kts`)
*   `org.osmdroid:osmdroid-android:6.1.20`: Thư viện hiển thị bản đồ OpenStreetMap và xử lý lớp phủ vẽ polyline.
*   `no.nordicsemi.android:ble-ktx:2.11.0`: SDK giao tiếp BLE chuyên nghiệp của Nordic, cung cấp hàng đợi gửi dữ liệu tin cậy.
*   `com.squareup.okhttp3:okhttp:5.0.0-alpha.14`: Thư viện kết nối HTTP client hiệu năng cao để gọi các API định tuyến, geocoding và thời tiết.
*   `com.google.code.gson:gson:2.14.0`: Hỗ trợ đóng gói và phân tích chuỗi JSON.
*   `androidx.security:security-crypto:1.1.0`: Cung cấp `EncryptedSharedPreferences` mã hóa khóa API an toàn bằng AES256-GCM.
*   `androidx.viewpager2:viewpager2:1.1.0`: Quản lý trượt chuyển đổi giữa các tab giao diện.
*   `androidx.compose.bom:2025.12.00` & Jetpack Compose: Dựng giao diện Material 3.

### 8.2. Thư viện phía Firmware ESP32-S3 (`firmware/esp32_s3_gc9a01/platformio.ini`)
*   `bodmer/TFT_eSPI @ ^2.5.43`: Thư viện vẽ đồ họa hiệu năng cao cho màn hình màu TFT GC9A01.
*   `h2zero/NimBLE-Arduino @ ^1.4.1`: Thư viện BLE tối ưu hóa bộ nhớ RAM và hiệu năng cho ESP32.
*   `bitbank2/JPEGDEC @ ^1.5.0`: Thư viện giải mã nhanh ảnh JPEG trực tiếp từ bộ đệm RAM/PSRAM.
*   `fbiego/ESP32Time @ ^2.0.4`: Quản lý thời gian thực RTC nội bộ của ESP32.
*   `mathertel/OneButton @ ^2.5.0`: Xử lý các sự kiện click, double-click và nhấn giữ phím bấm cơ học.
*   `bblanchon/ArduinoJson @ ^7.0.4`: Parse và trích xuất dữ liệu thời tiết, thông báo định dạng JSON.

### 8.3. Thư viện phía Firmware ESP32-C3 (`firmware/esp32_c3_oled/platformio.ini`)
*   `olikraus/U8g2 @ ^2.35.19`: Thư viện đồ họa đơn sắc hỗ trợ rất nhiều loại màn hình OLED SSD1306.
*   `h2zero/NimBLE-Arduino @ ^1.4.1`: NimBLE điều khiển kết nối BLE.
*   `fbiego/ESP32Time @ ^2.0.4`: RTC thời gian.
*   `mathertel/OneButton @ ^2.5.0` & `bblanchon/ArduinoJson @ ^7.0.4`: Xử lý sự kiện nút bấm và JSON.

---

## 9. KIẾN TRÚC DỮ LIỆU & DATA MODELS

### 9.1. NavigationRepository (Singleton State Holder trên Android)
Lưu trữ toàn bộ trạng thái dữ liệu hoạt động dùng chung của ứng dụng dưới dạng các dòng chảy dữ liệu bất đồng bộ Kotlin Flows:
*   `gpsLocation: StateFlow<Location?>`: Lưu trữ tọa độ định vị GPS hiện tại của người dùng.
*   `routes: StateFlow<List<RouteInfo>>`: Danh sách các lộ trình định tuyến tìm được từ API.
*   `currentStep: SharedFlow<StepInfo>`: Sự kiện chỉ dẫn bước rẽ hiện tại tiếp theo.
*   `hudPreviewData: StateFlow<HudData?>`: Dữ liệu hiển thị HUD hiện tại (ETA, khoảng cách rẽ, tên đường...).
*   `navigationState: StateFlow<Boolean>`: Cờ xác định ứng dụng có đang ở chế độ dẫn đường chủ động hay không.
*   `mapModeState: StateFlow<Boolean>`: Cờ trạng thái màn hình bản đồ trên ESP32 có đang mở hay không.
*   `logs: StateFlow<List<String>>`: Nhật ký hoạt động tối đa 100 dòng mới nhất của hệ thống BLE/GPS.
*   `deviceStatus: StateFlow<Map<String, String>>`: Trạng thái thiết bị ESP32 (RSSI, điện áp pin xe, tên màn hình...).
*   `remoteZoomCommand: SharedFlow<Boolean>`: Sự kiện nhận tín hiệu phóng to/thu nhỏ bản đồ từ xa gửi từ nút bấm ESP32.
*   `iconRequest: SharedFlow<String>`: Tín hiệu yêu cầu gửi bitmap của icon từ ESP32 dựa trên mã băm Hex.

### 9.2. Các Data Class quan trọng

```kotlin
// Thông tin chi tiết một tuyến đường
data class RouteInfo(
    val polyline: List<Pair<Double, Double>>, // Danh sách tọa độ vẽ tuyến đường
    val distance: Double,                     // Tổng chiều dài tuyến đường (mét)
    val duration: Double,                     // Tổng thời gian đi dự kiến (giây)
    val steps: List<StepInfo>,                // Danh sách các bước chỉ dẫn rẽ chi tiết
    var isSelected: Boolean = false           // Cờ xác định tuyến đường đang được chọn để đi
)

// Thông tin chi tiết một bước rẽ chỉ dẫn
data class StepInfo(
    val instruction: String,           // Hướng dẫn chữ (ví dụ: "Rẽ trái vào đường Nguyễn Huệ")
    val distance: Double,              // Khoảng cách từ điểm rẽ trước đến điểm rẽ này (mét)
    val duration: Double,              // Thời gian di chuyển của bước (giây)
    val maneuverIcon: Int,             // Chỉ số icon chỉ dẫn chuẩn hóa (0-20)
    val roadName: String,              // Tên đường rẽ tiếp theo
    val location: Pair<Double, Double> // Tọa độ điểm rẽ tiếp theo
)

// Dữ liệu đóng gói gửi sang HUD hiển thị
data class HudData(
    val active: Boolean,             // Có đang truyền dữ liệu HUD
    val isNavigation: Boolean,       // Có đang chạy dẫn đường chủ động
    val hasIcon: Boolean,            // Có chứa dữ liệu biểu tượng chỉ dẫn
    val distance: String,            // Khoảng cách đến điểm rẽ tiếp theo (ví dụ: "150 m")
    val duration: String,            // Thời gian đi còn lại (ví dụ: "5 phút")
    val eta: String,                 // Giờ đến đích dự kiến (ví dụ: "10:45")
    val title: String,               // Tên đường đi
    val directions: String,          // Hướng dẫn lượt rẽ tiếp theo
    val speed: String,               // Vận tốc di chuyển hiện tại
    val icon1bpp: ByteArray? = null, // Bitmap icon 1bpp 48x48px (288 bytes) nếu có
    val bitmapIcon: Bitmap? = null   // Ảnh Bitmap hiển thị trên màn hình xem trước của app
)

// Cấu hình bộ lọc màu cho màn hình OLED đơn sắc
data class OledFilter(
    val color: Int,              // Màu sắc RGB dạng Int thu được khi chấm chọn trên ảnh
    val tolerance: Int,          // Dung sai khoảng màu lọc (0-100)
    val dither: Int,             // Mức độ dither hạt của ảnh monochrome (0-100)
    var isActive: Boolean = true // Trạng thái bộ lọc có đang hoạt động hay không
)
```

---

## 10. CẤU HÌNH PHẦN CỨNG & PINOUT

### 10.1. Cấu hình phần cứng ESP32-S3 + Màn hình TFT GC9A01

Môi trường phát triển đã kiểm thử ổn định:
*   **Board:** ESP32-S3 DevKitC-1 (yêu cầu bật PSRAM để cấp phát buffer JPEG).
*   **Màn hình:** Màn hình tròn màu TFT GC9A01 đường kính 1.28 inch, giao tiếp SPI.

#### Sơ đồ kết nối chân (Pinout GC9A01 - ESP32-S3):

| Tên chân GC9A01 | Chân kết nối ESP32-S3 | Chức năng hệ thống |
| :--- | :--- | :--- |
| **VCC** | 3.3V / 5V | Nguồn cấp cho màn hình |
| **GND** | GND | Chân đất chung |
| **SCL** | GPIO 12 | SPI Clock (SCLK) |
| **SDA** | GPIO 11 | SPI MOSI (SDA) |
| **RST** | GPIO 10 | Reset màn hình |
| **DC** | GPIO 9 | Data/Command Select |
| **CS** | GPIO 13 | Chip Select |
| **BL** | GPIO 14 | Điều khiển đèn nền (PWM) |

#### Cấu hình các phím bấm & cảm biến ngoại vi khác trên ESP32-S3:
*   `MODE_BTN`: Nối vào chân **GPIO 0** (Nút BOOT tích hợp trên board, nhấn để mở menu/chuyển chế độ, nhấn giữ để chọn).
*   `ZOOM_BTN`: Nối vào chân **GPIO 1** (Phím phụ bấm điều khiển zoom bản đồ từ xa).
*   `BAT_ADC`: Nối vào chân **GPIO 3** (Đọc ADC đo điện áp ắc quy xe qua cầu phân áp R1=100k, R2=27k).

---

### 10.2. Cấu hình phần cứng ESP32-C3 + Màn hình OLED SSD1306

*   **Board:** ESP32-C3 DevKitM-1 (hoặc dòng ESP32-C3 SuperMini tối giản).
*   **Màn hình:** OLED SSD1306 128x64px, giao tiếp I2C.

#### Sơ đồ kết nối chân (OLED - ESP32-C3):

| Tên chân OLED | Chân kết nối ESP32-C3 | Chức năng hệ thống |
| :--- | :--- | :--- |
| **VCC** | 3.3V | Nguồn cấp màn hình |
| **GND** | GND | Chân đất chung |
| **SDA** | GPIO 8 | I2C Data (SDA) |
| **SCL** | GPIO 9 | I2C Clock (SCL) |

#### Cấu hình các phím bấm & cảm biến ngoại vi khác trên ESP32-C3:
*   `MODE_BTN`: Nối vào chân **GPIO 2** (Phím chuyển chế độ hiển thị).
*   `ZOOM_BTN`: Nối vào chân **GPIO 3** (Phím điều khiển zoom bản đồ).
*   `BAT_ADC`: Nối vào chân **GPIO 0** (Đọc ADC đo pin xe).

---

## 11. HƯỚNG DẪN BUILD & UPLOAD

### 11.1. Xây dựng và cài đặt Ứng dụng Android
*   **Yêu cầu:** Android Studio Koala trở lên, Java JDK 17+.
*   **Các bước thực hiện:**
    1.  Mở thư mục gốc của dự án trong Android Studio.
    2.  Đồng bộ Gradle dự án (`Gradle Sync`).
    3.  Cắm cáp kết nối điện thoại Android (đã bật chế độ gỡ lỗi USB Debugging).
    4.  Nhấn nút `Run` (hoặc phím tắt `Shift + F10`) để build và cài đặt ứng dụng trực tiếp lên điện thoại.
    5.  *Lưu ý quyền:* Cần cấp quyền vị trí (Location), Bluetooth, hiển thị trên các ứng dụng khác (Overlay) và quyền truy cập thông báo (Notification Listener) trên điện thoại để ứng dụng chạy đầy đủ tính năng.

### 11.2. Nạp chương trình Firmware cho ESP32 (GC9A01 hoặc OLED)
*   **Yêu cầu:** Cài đặt VS Code và tiện ích mở rộng **PlatformIO IDE**.
*   **Lưu ý sửa lỗi Boot Loop trên ESP32-S3:**
    > [!IMPORTANT]
    > Phiên bản Platform espressif32 mới của PlatformIO (Arduino core 3.0) thường bị lỗi crash bộ nhớ PSRAM gây vòng lặp tự khởi động (boot loop) liên tục trên dòng chip ESP32-S3 DevKitC-1.
    >
    > Để khắc phục triệt để, file `platformio.ini` đã được cấu hình cố định (pin) về phiên bản ổn định: `platform = platformio/espressif32 @ 6.6.0` (sử dụng Arduino core 3.0.3) và thêm cấu hình phân vùng `board_build.partitions = default.csv`.

*   **Các bước thực hiện nạp chương trình:**
    1.  Mở thư mục dự án tương ứng (ví dụ: `firmware/esp32_s3_gc9a01`) bằng VS Code.
    2.  Chờ PlatformIO tự động tải môi trường build và các thư viện phụ thuộc (`TFT_eSPI`, `NimBLE-Arduino`...).
    3.  Kết nối board ESP32 vào máy tính qua cổng USB. Xác định cổng COM nhận được (ví dụ: `COM12`) và sửa lại trường `upload_port` trong tệp `platformio.ini` nếu cần.
    4.  Nhấn nút **Build** (biểu tượng dấu tích $\checkmark$ ở thanh trạng thái dưới cùng VS Code) hoặc chạy lệnh CLI:
        ```bash
        pio run
        ```
    5.  Nhấn nút **Upload** (biểu tượng mũi tên sang phải $\rightarrow$) hoặc chạy lệnh CLI để nạp firmware:
        ```bash
        pio run --target upload
        ```
    6.  *Mẹo nhỏ:* Nếu gặp lỗi xung đột phân vùng nhớ cũ gây crash, hãy chạy lệnh xóa sạch flash chip trước khi nạp:
        ```bash
        pio run --target erase
        ```
    7.  Mở cổng Serial Monitor (tốc độ `115200` baud) để theo dõi các log kết nối BLE và trạng thái hệ thống.

---
*Tài liệu tổng quan hệ thống TYMAP được cập nhật đầy đủ dựa trên phân tích mã nguồn Android Client Kotlin và cả hai phiên bản Firmware ESP32 C++ hiện tại của dự án.*
