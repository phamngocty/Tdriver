[manguon.md](file;file:///d%3A/Documents/PlatformIO/Tdriver/sygic/manguon.md) dây là mã nguồn repo duungf app sigic để kết nnoois esp hiển thị chỉ đường giôngws dự án của tôi  trên ios[sygic128x64.ino](file;file:///d%3A/Documents/PlatformIO/Tdriver/sygic/sygic128x64.ino)  và[tsmart-factory](file;file:///d%3A/Documents/PlatformIO/Tdriver/sygic/tsmart-factory)  là rêpo cho phép esp32 c3 ( dự án màn hình chỉ đường ban đầu của tôi )  chạy vừa fw trên app android và fw chạy app sygic  
chạy trên chùng 1 thiết bị esp32c3 thông            qua phương pháp chọn phân vùng bằng web [factory_app.cpp](file;file:///d%3A/Documents/PlatformIO/Tdriver/sygic/tsmart-factory/src/factory_app.cpp) fw được lưu ở [binaries](file;file:///d%3A/Documents/PlatformIO/Tdriver/sygic/tsmart-factory/binaries)   nạp thẳng vào esp 


bạn hãy đọc hiểu ngữ cảnh  hãy hỏi tôi nếu chưa  rỏ    

muc tiêu 
tôi muốnbuild  lại fw mới  dùng app sygic  cho esp323 c3 và  thích hợp cơ chế chuyển đổi linh hoạt giữa android và ios lên esp32 c3 
hãy làm rõ trước khi bắt đầu 






[sygic](file;file:///d%3A/Documents/PlatformIO/Tdriver/sygic) tạo một folde mới trong [sygic](file;file:///d%3A/Documents/PlatformIO/Tdriver/sygic)    cấu trúc như [tsmart-factory](file;file:///d%3A/Documents/PlatformIO/Tdriver/sygic/tsmart-factory)   mang tên tymap factory Kiến trúc Dual-Boot:
Phân vùng ota_0: Firmware Android (TYMAP BLE).
Phân vùng ota_1: Firmware iOS mới (Sygic Wi-Fi WebSocket).
Phân vùng factory: Web Portal cho phép người dùng chọn đổi hệ điều hành qua giao diện Web. tiến hành viết mã nguồn Firmware Sygic mới theo  Phương thức Bluetooth Low Energy - BLE  và sữa [esp32_c3_oled](file;file:///d%3A/Documents/PlatformIO/Tdriver/TYMAP/firmware/esp32_c3_oled)  dể có cơ chế và  tắt mở nhan 3 lần vào Phân vùng factory: Web Portal cho phép người dùng chọn đổi hệ điều hành qua giao diện Web.