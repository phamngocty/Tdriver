// Setup for the ESP32 S3 with GC9A01 240x240 round display
// Pin configuration matches build_flags in platformio.ini

#define USER_SETUP_ID 70

#define GC9A01_DRIVER

#define TFT_WIDTH 240

#define TFT_HEIGHT 240

// SPI pins for GC9A01 on ESP32-S3 (via HSPI)
#define TFT_CS   10
#define TFT_MOSI 11
#define TFT_SCLK 12
#define TFT_DC   13
#define TFT_RST  14
#define TFT_BL   2

// Note: TFT_MISO not used (GC9A01 is write-only)

#define LOAD_GLCD
#define LOAD_FONT2
#define LOAD_FONT4
#define LOAD_FONT6
#define LOAD_FONT7
#define LOAD_FONT8
#define LOAD_GFXFF
#define SMOOTH_FONT

// HSPI port (SPI3) — matches SPI_PORT=2 patch in TFT_eSPI_ESP32_S3.h
#define USE_HSPI_PORT

#define SPI_FREQUENCY      80000000
#define SPI_READ_FREQUENCY 20000000
