title=Đi về hướng Đông
dir=Đến nơi lúc 23:21
title=700 m · Chếch sang phải vào Đường Lê Văn Vịnh
dir=Đường Lê Văn Vịnh




dir luc là eta lúc là dir 

copy cơ chế 

// Trích xuất khoảng cách từ đầu chuỗi (vd: "100m rẽ trái" → "100m", "100 m đi..." → "100 m")
// Hỗ trợ cả "100m" và "100 m" (có space giữa số và đơn vị)
String extractLeadingDistance(String &str)
{
    int i = 0;
    while (i < str.length() && (isDigit(str[i]) || str[i] == ','))
        i++;
    if (i == 0)
        return "";
    // Skip optional space giữa số và đơn vị (vd "100 m")
    int spacePos = i;
    while (spacePos < str.length() && str[spacePos] == ' ')
        spacePos++;
    int j = spacePos;
    while (j < str.length() && isAlpha(str[j]))
        j++;
    String unit = str.substring(spacePos, j);
    if (unit == "m" || unit == "km")
    {
        String distance = str.substring(0, j); // giữ nguyên space nếu có
        int k = j;
        while (k < str.length() && str[k] == ' ')
            k++;
        str = str.substring(k);
        return distance;
    }
    return "";
}




//
 myfont.set_font(vietnamtimes8x2);
        myfont.print(0, 25, ": " + app.navTitle, SH110X_WHITE, SH110X_BLACK); //tile khoảng cách rẽ tiếp theo 
        myfont.print(0, 40, "KC: " + app.navDistance, SH110X_WHITE, SH110X_BLACK); //khoảng cách 
        myfont.print(0, 53, "TG: " + app.navETA, SH110X_WHITE, SH110X_BLACK);   //thời gian 
        myfont.set_font(vietnamtimes7x2r);
        myfont.print(0, 0, app.navDirectionLines[0], SH110X_WHITE, SH110X_BLACK); //hướng
        

 hiển thị full map không hieenjj thị khu vục cắt , sữ nút bản dồ hiện tại  ở tap notifile  thành ảnh vằ cắt ở tap map
 hiển thị full map không hieenjj thị khu vục cắt , sữ nút bản dồ hiện tại  ở tap notifile  thành ảnh vằ cắt ở tap map 
thêm co chế  chup popup osm    giống goooogle mấp   và cải thieenj  share giông polylinr google map 


TÔI MUỐN CÓ THÊM TAP  RENDER   NƠI NÀY HIỂN THỊ TẤT CẢ THÔNG TIN  VÀ DỮ LIỆU  CỦA APP SẼ GỮI ĐẾN ESP32  VÀ TẤT CẢ DỮ LIỆU ĐÃ GỮI ĐẾN ESP32 
CÓ TÍNH NĂNG TƯƠNG TỰ NHƯ MENU NOTIFILE   

HIỂN THỊ ẢNH  CHUẨN BỊ GỬI   ICON  
ĐỒNG BỘ MƯC ZOOM CỦA APP VỚI ESP32 Ở TAP RENDER CUUNGX GIÃ LẬP 1 MÀN HÌNH TRÒN 240X240PX  NHƯ ESP32  VÀ CÓ THÊM KHUNG GIẢ LẬP CHẾ ĐỘ BẢN DỒ CUỐN CHIẾU THỂ HIỆNH TOÀN BẢN ĐỒ GỮI TỚI ESP32 VÀ KHUNG HIÊNT HỊ ĐANG Ở ĐÂU


Thực hiện chế độ "Track Up" chuẩn Google Maps
•
Xoay bản đồ: Bản đồ sẽ xoay một góc -bearing (ngược chiều di chuyển) để đảm bảo tuyến đường (Polyline) luôn hướng thẳng lên phía 12h trên màn hình điện thoại.   sữa lại cập nhật cho cả esp32 và app 