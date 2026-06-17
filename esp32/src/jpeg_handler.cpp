#include "jpeg_handler.h"

// Con trỏ tĩnh để callback có thể truy cập đối tượng TFT
static TFT_eSPI *_tftPtr = nullptr;

JpegHandler::JpegHandler(TFT_eSPI *tft) : _tft(tft)
{
    _tftPtr = tft;
}

// Callback JPEGDEC – được gọi khi có một khối ảnh giải mã xong
int JpegHandler::_pdraw(JPEGDRAW *draw)
{
    if (_tftPtr)
    {
        // pushImageRect nhanh hơn pushImage cho nhiều block nhỏ
        _tftPtr->pushImage(draw->x, draw->y, draw->iWidth, draw->iHeight, draw->pPixels);
    }
    return 1; // tiếp tục giải mã
}

bool JpegHandler::processJPEG(uint8_t *buffer, uint32_t size)
{
    if (!buffer || size == 0)
        return false;

    _jpeg.openRAM(buffer, size, _pdraw);
    // decode: 0=scale none (240×240 gốc)
    bool ok = _jpeg.decode(0, 0, 0);
    _jpeg.close();

    if (!ok)
    {
        Serial.println("[JPEG] Decode failed");
    }
    return ok;
}
