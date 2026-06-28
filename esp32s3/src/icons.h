#ifndef ICONS_H
#define ICONS_H

#include <Arduino.h>
#include <pgmspace.h>

// ============================================================
// Turn-by-turn arrow icons (64x62 px, 1bpp = 496 bytes each)
// Index 0 = no icon, indexes 1-20 = various turn arrows
// ============================================================

#define ICON_WIDTH 64
#define ICON_HEIGHT 62
#define ICON_SIZE ((ICON_WIDTH * ICON_HEIGHT) / 8) // 496 bytes

// Weather icons (32x32 px, 1bpp = 128 bytes each)
#define WTHR_ICON_WIDTH 32
#define WTHR_ICON_HEIGHT 32
#define WTHR_ICON_SIZE ((WTHR_ICON_WIDTH * WTHR_ICON_HEIGHT) / 8) // 128 bytes

extern const uint8_t turnIcons[21][ICON_SIZE] PROGMEM;
extern const uint8_t weatherIconSunny[WTHR_ICON_SIZE] PROGMEM;
extern const uint8_t weatherIconCloudy[WTHR_ICON_SIZE] PROGMEM;
extern const uint8_t weatherIconRainy[WTHR_ICON_SIZE] PROGMEM;
extern const uint8_t weatherIconSnowy[WTHR_ICON_SIZE] PROGMEM;

// Map weather icon codes to arrays
int getWeatherIconIndex(const char *iconCode);

#endif // ICONS_H
