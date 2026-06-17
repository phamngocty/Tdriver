#ifndef JPEG_HANDLER_H
#define JPEG_HANDLER_H

#include <Arduino.h>
#include <FS.h>

// JPEGDEC uses File (from fs namespace) in its API - ensure it's accessible
using fs::File;

#include <TFT_eSPI.h>
#include <JPEGDEC.h>

// -------------------------------------------------------------------
// Giải mã JPEG từ RAM buffer và vẽ lên TFT
// -------------------------------------------------------------------

class JpegHandler
{
public:
    JpegHandler(TFT_eSPI *tft);
    bool processJPEG(uint8_t *buffer, uint32_t size);

private:
    TFT_eSPI *_tft;
    JPEGDEC _jpeg;
    static int _pdraw(JPEGDRAW *draw);
};

#endif // JPEG_HANDLER_H
