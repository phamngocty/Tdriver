#ifndef ICONS_H
#define ICONS_H

#include <Arduino.h>
#include <TFT_eSPI.h>

// -------------------------------------------------------------------
// Vẽ icon mũi tên rẽ (turn-by-turn) lên TFT tại (cx, cy)
// Kích thước ~48×48 px, phù hợp màn hình 240×240
// index: 0=thẳng, 1-20 các hướng rẽ (xem mapping bên dưới)
// -------------------------------------------------------------------

// Maneuver mapping (OSRM → icon index):
//   0 = straight
//   1 = slight right     2 = right       3 = sharp right
//   4 = uturn right
//   5 = slight left      6 = left        7 = sharp left
//   8 = uturn left
//   9 = arrive          10 = depart
//  11 = roundabout right
//  12 = roundabout left
//  13 = merge right      14 = merge left
//  15 = ramp right       16 = ramp left
//  17 = fork right       18 = fork left
//  19 = keep right       20 = keep left

void drawTurnIcon(TFT_eSPI &tft, int16_t cx, int16_t cy, uint8_t index, uint16_t color);

#endif // ICONS_H
