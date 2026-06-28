#include "jpeg_handler.h"
#include <TFT_eSPI.h>

// Forward declaration of TFT (defined in main.cpp)
extern TFT_eSPI tft;

JPEGHandler jpegHandler;

JPEGHandler::JPEGHandler()
    : m_buf(nullptr), m_totalSize(0), m_received(0), m_receiving(false)
{
}

JPEGHandler::~JPEGHandler()
{
    if (m_buf)
    {
        free(m_buf);
        m_buf = nullptr;
    }
}

bool JPEGHandler::init()
{
    // Allocate JPEG buffer in PSRAM
    m_buf = (uint8_t *)ps_malloc(JPEG_BUF_SIZE);
    if (!m_buf)
    {
        Serial.println("ERROR: Failed to allocate JPEG buffer in PSRAM!");
        // Fallback: try regular heap (might fail on memory-constrained devices)
        m_buf = (uint8_t *)malloc(JPEG_BUF_SIZE);
        if (!m_buf)
        {
            Serial.println("ERROR: Failed to allocate JPEG buffer in regular heap!");
            return false;
        }
        Serial.println("JPEG buffer allocated in regular heap (not PSRAM)");
    }
    else
    {
        Serial.println("JPEG buffer allocated in PSRAM (32KB)");
    }
    return true;
}

bool JPEGHandler::beginReceive(uint32_t totalSize)
{
    // If totalSize > 0, this is a new transfer
    if (totalSize > 0)
    {
        if (totalSize > JPEG_BUF_SIZE)
        {
            Serial.printf("JPEG too large: %u > %u\n", totalSize, JPEG_BUF_SIZE);
            return false;
        }
        m_totalSize = totalSize;
        m_received = 0;
        m_receiving = true;
        Serial.printf("JPEG receive start: %u bytes\n", totalSize);
        return true;
    }
    // totalSize == 0: just return whether we're already receiving
    return m_receiving;
}

bool JPEGHandler::feedData(const uint8_t *data, size_t len)
{
    if (!m_receiving || !m_buf)
        return false;

    uint32_t remaining = m_totalSize - m_received;
    size_t toCopy = (len <= remaining) ? len : remaining;

    memcpy(m_buf + m_received, data, toCopy);
    m_received += toCopy;

    return true;
}

bool JPEGHandler::isComplete()
{
    return m_receiving && m_received >= m_totalSize;
}

uint8_t JPEGHandler::getProgress()
{
    if (!m_receiving || m_totalSize == 0)
        return 0;
    return (uint8_t)((m_received * 100) / m_totalSize);
}

bool JPEGHandler::decodeToTFT()
{
    if (!m_buf || !isComplete())
        return false;

    Serial.printf("Decoding JPEG: %u bytes\n", m_received);

    // Open JPEG from memory
    int ret = m_jpeg.openRAM(m_buf, m_received, JPEGDraw);
    if (!ret)
    {
        Serial.println("JPEG openRAM failed!");
        reset();
        return false;
    }

    // Decode the entire JPEG. The callback JPEGDraw pushes pixels to TFT.
    ret = m_jpeg.decode(0, 0, 0);
    m_jpeg.close();

    if (ret)
    {
        Serial.println("JPEG decoded successfully");
    }
    else
    {
        Serial.println("JPEG decode failed!");
    }

    // Reset handler for next image
    reset();
    return (ret != 0);
}

void JPEGHandler::reset()
{
    m_totalSize = 0;
    m_received = 0;
    m_receiving = false;
}

// Static callback: called by JPEGDEC for each MCU decoded
int JPEGHandler::JPEGDraw(JPEGDRAW *pDraw)
{
    // pDraw->x, pDraw->y: top-left position of the decoded block
    // pDraw->w, pDraw->h: dimensions
    // pDraw->pixels: 16-bit RGB565 pixel data
    // pDraw->scale: scale factor

    // Push pixels directly to TFT — no full framebuffer needed
    tft.pushImage(pDraw->x, pDraw->y, pDraw->iWidth, pDraw->iHeight, pDraw->pPixels);
    return 1; // Continue decoding
}
