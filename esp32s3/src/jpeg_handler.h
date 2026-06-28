#ifndef JPEG_HANDLER_H
#define JPEG_HANDLER_H

#include <Arduino.h>
#include <JPEGDEC.h>

// Max JPEG buffer (32 KB) allocated in PSRAM
#define JPEG_BUF_SIZE (32 * 1024)

class JPEGHandler
{
public:
    JPEGHandler();
    ~JPEGHandler();

    // Initialize: allocate PSRAM buffer
    bool init();

    // Start receiving a new JPEG: first 4 bytes = total size (uint32 LE)
    // Returns false if size exceeds max buffer
    bool beginReceive(uint32_t totalSize);

    // Feed a chunk of JPEG data (fragment from BLE write)
    // Returns false if buffer overflow
    bool feedData(const uint8_t *data, size_t len);

    // Check if JPEG is complete
    bool isComplete();

    // Decode and draw JPEG to TFT display
    // Uses the pdraw callback to push pixels directly to display
    bool decodeToTFT();

    // Reset state (e.g., on error)
    void reset();

    // Get progress (0-100)
    uint8_t getProgress();

private:
    uint8_t *m_buf = nullptr;
    uint32_t m_totalSize = 0;
    uint32_t m_received = 0;
    bool m_receiving = false;

    // JPEG decoder instance
    JPEGDEC m_jpeg;

    // Callback: draw decoded MCU to TFT
    static int JPEGDraw(JPEGDRAW *pDraw);
};

extern JPEGHandler jpegHandler;

#endif // JPEG_HANDLER_H
