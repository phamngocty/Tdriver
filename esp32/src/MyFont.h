#ifndef MYFONT_H
#define MYFONT_H

#include <Arduino.h>
#include <TFT_eSPI.h>

// ===================================================================
// PHẦN 1: TFT_eSPI built-in fonts — dùng cho text ASCII không dấu
//          (tốc độ, số, ETA, tên đường tiếng Anh)
//          Font 6,7,8 có sẵn khi define LOAD_FONT6/7/8=1 trong build_flags
// ===================================================================
#define FONT_SPEED 7  // Font rất lớn — tốc độ (cao ~53 px)
#define FONT_LARGE 6  // Font lớn — tiêu đề / khoảng cách
#define FONT_MEDIUM 4 // Font vừa — tên đường
#define FONT_SMALL 2  // Font nhỏ — ghi chú, đơn vị

// ===================================================================
// PHẦN 2: FontMaker custom fonts — dùng cho text Unicode có dấu
//          (tiếng Việt, Nhật, Trung...)
//
// Cách dùng:
//   1. Chạy VN_font_maker.exe trong thư mục FontMaker để tạo font
//   2. Điền tên 4 font (tương ứng 4 cỡ) vào các macro bên dưới
//   3. Bỏ comment dòng #include "FontMaker.h" ở PHẦN 3
//   4. Trong file .cpp: khai báo set_px callback + MakeFont object
//      VD: MakeFont myfont(&setpx);  myfont.set_font(FM_MEDIUM);
//
// Font có sẵn trong thư viện: MakeFont_Font1, Tahoma12, Tahoma16,
//   Microsoft_Sans_Serif_9/10/11/12/14, time4x8, ...
// ===================================================================

// 👇 Bỏ comment và điền font cho từng cỡ (sau khi tạo bằng VN_font_maker)
// #define FM_SPEED    Tahoma16              // Cỡ tốc độ (to nhất)
// #define FM_LARGE    Tahoma12              // Cỡ lớn
// #define FM_MEDIUM   MakeFont_Font1        // Cỡ vừa
// #define FM_SMALL    Microsoft_Sans_Serif_9 // Cỡ nhỏ

// ===================================================================
// PHẦN 3: Tuỳ chọn — bỏ comment dòng dưới để kích hoạt FontMaker
//          Sau đó khai báo MakeFont + set_px trong file code của bạn
// ===================================================================
// #include "FontMaker.h"

#endif // MYFONT_H
