#include "icons.h"

// -------------------------------------------------------------------
// Vẽ mũi tên rẽ bằng fillTriangle + fillRect
// Mỗi arrow nằm trong bounding box 40×40, centered tại (cx, cy)
// -------------------------------------------------------------------

// ↑
static void drawArrowStraight(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx, cy - 20, cx - 12, cy, cx + 12, cy, color);
    tft.fillRect(cx - 5, cy, 10, 18, color);
}

// ↗
static void drawArrowSlightRight(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx + 14, cy - 14, cx - 2, cy - 2, cx + 4, cy - 18, color);
    tft.fillRect(cx - 6, cy - 2, 18, 10, color);
}

// →
static void drawArrowRight(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx + 20, cy, cx, cy - 12, cx, cy + 12, color);
    tft.fillRect(cx - 16, cy - 5, 18, 10, color);
}

// ↘
static void drawArrowSharpRight(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx + 18, cy + 6, cx + 2, cy - 4, cx + 12, cy + 16, color);
    tft.fillRect(cx - 4, cy - 6, 16, 12, color);
}

// ↖
static void drawArrowSlightLeft(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx - 14, cy - 14, cx + 2, cy - 2, cx - 4, cy - 18, color);
    tft.fillRect(cx - 12, cy - 2, 18, 10, color);
}

// ←
static void drawArrowLeft(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx - 20, cy, cx, cy - 12, cx, cy + 12, color);
    tft.fillRect(cx - 2, cy - 5, 18, 10, color);
}

// ↙
static void drawArrowSharpLeft(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx - 18, cy + 6, cx - 2, cy - 4, cx - 12, cy + 16, color);
    tft.fillRect(cx - 12, cy - 6, 16, 12, color);
}

// Quay đầu
static void drawArrowUturn(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color, bool right)
{
    int dir = right ? 1 : -1;
    for (int a = 0; a <= 180; a += 10)
    {
        float rad = a * DEG_TO_RAD;
        int16_t x = cx + dir * (int16_t)(14 * cos(rad));
        int16_t y = cy - 4 + (int16_t)(12 * sin(rad));
        tft.drawPixel(x, y, color);
    }
    int16_t tipX = cx + dir * 14;
    int16_t tipY = cy - 4;
    tft.fillTriangle(tipX + dir * 6, tipY, tipX, tipY - 8, tipX, tipY + 8, color);
}

// Cờ đến nơi
static void drawArrowArrive(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillRect(cx - 2, cy - 18, 4, 30, color);
    tft.fillTriangle(cx + 2, cy - 18, cx + 2, cy - 4, cx + 16, cy - 11, color);
    tft.fillCircle(cx, cy + 14, 6, color);
}

// Chấm xuất phát + mũi tên lên
static void drawArrowDepart(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillCircle(cx, cy + 14, 6, color);
    drawArrowStraight(tft, cx, cy - 6, color);
}

// Vòng xoay
static void drawRoundabout(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color, bool right)
{
    tft.drawCircle(cx, cy, 14, color);
    int dir = right ? 1 : -1;
    tft.fillTriangle(cx + dir * 16, cy, cx + dir * 6, cy - 10, cx + dir * 6, cy + 10, color);
}

// Nhập phải
static void drawArrowMergeRight(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx + 18, cy, cx, cy - 12, cx, cy + 12, color);
    tft.drawLine(cx - 12, cy - 14, cx - 4, cy - 6, color);
    tft.drawLine(cx - 12, cy + 14, cx - 4, cy + 6, color);
}

// Nhập trái
static void drawArrowMergeLeft(TFT_eSPI &tft, int16_t cx, int16_t cy, uint16_t color)
{
    tft.fillTriangle(cx - 18, cy, cx, cy - 12, cx, cy + 12, color);
    tft.drawLine(cx + 12, cy - 14, cx + 4, cy - 6, color);
    tft.drawLine(cx + 12, cy + 14, cx + 4, cy + 6, color);
}

// -------------------------------------------------------------------

void drawTurnIcon(TFT_eSPI &tft, int16_t cx, int16_t cy, uint8_t index, uint16_t color)
{
    switch (index)
    {
    case 0:
        drawArrowStraight(tft, cx, cy, color);
        break;
    case 1:
        drawArrowSlightRight(tft, cx, cy, color);
        break;
    case 2:
        drawArrowRight(tft, cx, cy, color);
        break;
    case 3:
        drawArrowSharpRight(tft, cx, cy, color);
        break;
    case 4:
        drawArrowUturn(tft, cx, cy, color, true);
        break;
    case 5:
        drawArrowSlightLeft(tft, cx, cy, color);
        break;
    case 6:
        drawArrowLeft(tft, cx, cy, color);
        break;
    case 7:
        drawArrowSharpLeft(tft, cx, cy, color);
        break;
    case 8:
        drawArrowUturn(tft, cx, cy, color, false);
        break;
    case 9:
        drawArrowArrive(tft, cx, cy, color);
        break;
    case 10:
        drawArrowDepart(tft, cx, cy, color);
        break;
    case 11:
        drawRoundabout(tft, cx, cy, color, true);
        break;
    case 12:
        drawRoundabout(tft, cx, cy, color, false);
        break;
    case 13:
        drawArrowMergeRight(tft, cx, cy, color);
        break;
    case 14:
        drawArrowMergeLeft(tft, cx, cy, color);
        break;
    case 15:
        drawArrowMergeRight(tft, cx, cy, color);
        break; // ramp right
    case 16:
        drawArrowMergeLeft(tft, cx, cy, color);
        break; // ramp left
    case 17:
        drawArrowSlightRight(tft, cx, cy, color);
        break; // fork right
    case 18:
        drawArrowSlightLeft(tft, cx, cy, color);
        break; // fork left
    case 19:
        drawArrowSlightRight(tft, cx, cy, color);
        break; // keep right
    case 20:
        drawArrowSlightLeft(tft, cx, cy, color);
        break; // keep left
    default:
        drawArrowStraight(tft, cx, cy, color);
        break;
    }
}
