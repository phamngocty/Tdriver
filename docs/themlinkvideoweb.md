1. Chỗ Dán Link Video Cho Android (TYMAP App)
👉 Tìm khoảng dòng 1738 - 1745:

html
<!-- ========================================================================= -->
<!-- [CHỖ DÁN LINK VIDEO ANDROID CỦA BẠN]:                                     -->
<!-- 1. Nếu dùng YouTube: Thay mã video vào src="https://www.youtube.com/embed/VIDEO_ID" -->
<!-- 2. Nếu dùng file MP4: Điền link file MP4 hoặc video trực tiếp             -->
<!-- ========================================================================= -->
<iframe 
    id="videoPlayerAndroid" 
    src="👉 DÁN_LINK_VIDEO_ANDROID_VÀO_ĐÂY 👈" 
    title="Video hướng dẫn ứng dụng Android TYMAP" 
    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" 
    allowfullscreen>
</iframe>
2. Chỗ Dán Link Video Cho iOS (Sygic GPS Trên iPhone)
👉 Tìm khoảng dòng 1993 - 2000:

html
<!-- ========================================================================= -->
<!-- [CHỖ DÁN LINK VIDEO IOS SYGIC CỦA BẠN]:                                   -->
<!-- 1. Nếu dùng YouTube: Thay mã video vào src="https://www.youtube.com/embed/VIDEO_ID" -->
<!-- 2. Nếu dùng file MP4: Điền link file MP4 hoặc video trực tiếp             -->
<!-- ========================================================================= -->
<iframe 
    id="videoPlayerIos" 
    src="👉 DÁN_LINK_VIDEO_IOS_SYGIC_VÀO_ĐÂY 👈" 
    title="Video hướng dẫn mở khóa Sygic iOS BLE HUD" 
    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" 
    allowfullscreen>
</iframe>

3. Chỗ Dán Link Video Đấu Nối 2 Dây Nguồn Xe Máy (Công Tắc Phanh Trước)
👉 Tìm khoảng dòng 2330 - 2340 trong file `docs/index.html`:

```html
<!-- ========================================================================= -->
<!-- [CHỖ DÁN LINK VIDEO ĐẤU DÂY XE MÁY]:                                       -->
<!-- 1. Nếu dùng YouTube: Thay mã video vào src="https://www.youtube.com/embed/VIDEO_ID" -->
<!-- 2. Nếu dùng file MP4: Điền link file MP4 hoặc video trực tiếp             -->
<!-- ========================================================================= -->
<iframe 
    id="videoPlayerWiring" 
    src="👉 DÁN_LINK_VIDEO_ĐẤU_DÂY_VÀO_ĐÂY 👈" 
    title="Video hướng dẫn đấu nối 2 dây nguồn xe máy" 
    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" 
    allowfullscreen>
</iframe>
```

💡 Hướng Dẫn Cách Lấy Link Video Đúng Chuẩn
Nếu bạn đăng video lên YouTube:

Link xem thông thường có dạng: https://www.youtube.com/watch?v=AbCd1234
Link nhúng chuẩn để dán vào src: Chuyển thành dạng embed: https://www.youtube.com/embed/AbCd1234
Nếu bạn dùng file video MP4 (lưu trong máy hoặc tải lên hosting):

Bạn có thể dán trực tiếp đường dẫn file, ví dụ: src="assets/videos/huong_dan_android.mp4" hoặc link online https://domain.com/video.mp4
Khi chưa dán link:

Màn hình sẽ hiển thị khung chờ công nghệ cao cấp với nút Play neon, thông tin thời lượng và thông báo hướng dẫn người xem.
Khi dán link vào src, mã Javascript trong trang sẽ tự động kích hoạt trình phát video ngay lập tức.
1. Chi Tiết Phương Pháp Đấu Nối 2 Dây Thương Mại (Plug & Play)
Khác với sơ đồ 4 chân thí nghiệm thô, bộ thương mại hoàn thiện chỉ có 2 dây nguồn ra ngoài nhờ các linh kiện đã được đúc tích hợp bảo vệ bên trong:

Dây Ra Ngoài	Điện Áp	Vị Trí Đấu Nối Chuẩn	Chức Năng Hoạt Động
Dây Đỏ (+)	+12V DC	Dây sau khóa tại công tắc phanh trước (thắng đĩa tay phải)	Bật chìa khóa là thiết bị khởi động + Đo liên tục điện áp bình ắc quy
Dây Đen (-)	0V (GND)	Dây Mass cùm đầu đèn hoặc bắt ốc sườn xe	Mass chung bảo vệ chống nhiễu
Ưu điểm vượt trội:

Thao tác hoàn toàn ở cùm tay lái phía trước, chỉ mất 5 phút, không cần mổ ổ khóa và không cần tháo rã dàn áo xe.
Mẹo thử nhanh chân công tắc phanh: Công tắc phanh trước có 2 chân giắc. Bật khóa xe (chưa bóp phanh), dùng bút thử điện hoặc đồng hồ đo: Chân nào có điện 12V thì trích dây Đỏ vào đó (Honda thường là dây Đen; Yamaha thường là dây Nâu). Chân còn lại chỉ có điện khi bóp phanh (dẫn về đèn hậu).
2. Vị Trí Dán Link Video Hướng Dẫn Đấu Dây
Trong file 

docs/index.html
 tại dòng 2332 - 2339:

html
<!-- ========================================================================= -->
<!-- [CHỖ DÁN LINK VIDEO ĐẤU DÂY XE MÁY]:                                       -->
<!-- 1. Nếu dùng YouTube: Thay mã video vào src="https://www.youtube.com/embed/VIDEO_ID" -->
<!-- 2. Nếu dùng file MP4: Điền link file MP4 hoặc video trực tiếp             -->
<!-- ========================================================================= -->
<iframe 
    id="videoPlayerWiring" 
    src="👉 DÁN_LINK_VIDEO_VÀO_ĐÂY 👈" 
    title="Video hướng dẫn đấu nối 2 dây nguồn xe máy" 
    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" 
    allowfullscreen 
    style="display:none;">
</iframe>
3. Tổng Hợp 3 Vị Trí Video Trong Trang Web
File cẩm nang 

docs/themlinkvideoweb.md
 đã được cập nhật đầy đủ cả 3 video:

Video App Android (TYMAP BLE): Tìm dòng 1744 - 1752 (id="videoPlayerAndroid")
Video App iOS (Sygic GPS): Tìm dòng 2000 - 2008 (id="videoPlayerIos")
Video Đấu Dây Xe Máy (Công Tắc Phanh Trước): Tìm dòng 2332 - 2339 (id="videoPlayerWiring")
Khi dán link vào thuộc tính src="...", mã Javascript trong trang web sẽ tự động ẩn khung chờ neon và kích hoạt trình phát video ngay lập tức.

