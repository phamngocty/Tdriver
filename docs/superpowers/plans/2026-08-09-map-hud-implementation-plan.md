# MAP HUD Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a hybrid MAP HUD display mode on ESP32-S3 (GC9A01 240x240 LCD) featuring top ~70% map view and bottom ~30% floating rounded HUD card with auto-scrolling Google Maps street name and turn distance, while resolving state conflicts with the existing 5s turn popup feature.

**Architecture:** Extend the ESP32 GUI module (`gui.h`, `gui.cpp`, `main.cpp`) with a new mode `MAP_HUD_MODE`. Integrate auto-scrolling marquee text renderer for long street names. Map hardware long-press on `ZOOM_BTN` to toggle the floating card (`MAP_HUD` vs `MAP_PURE`). Maintain Google Maps notification data (`GMapsNotificationListener.kt`) as primary data provider.

**Tech Stack:** C++ / Arduino / TFT_eSPI / FontMaker / NimBLE / PlatformIO for ESP32-S3; Kotlin / Jetpack / Osmdroid for Android app.

---

### Task 1: Extend GUI Headers & Enums in ESP32 Firmware

**Files:**
- Modify: `TYMAP/firmware/esp32_s3_gc9a01/src/gui.h:33-40`
- Modify: `TYMAP/firmware/esp32_s3_gc9a01/src/gui.h:45-50`

- [ ] **Step 1: Update Mode enum and add showMapHudCard declaration**

In `gui.h`:
```cpp
// Khai báo chế độ hiển thị hệ thống
enum Mode { HUD_MODE, MAP_MODE, MAP_HUD_MODE, STATUS_MODE, INFO_MODE, NOTIF_MODE };

extern bool showMapHudCard;
void drawMapHudOverlay();
```

- [ ] **Step 2: Commit header updates**

```bash
git add TYMAP/firmware/esp32_s3_gc9a01/src/gui.h
git commit -m "feat(esp32): add MAP_HUD_MODE enum and drawMapHudOverlay declaration"
```

---

### Task 2: Implement `drawMapHudOverlay()` Renderer in `gui.cpp`

**Files:**
- Modify: `TYMAP/firmware/esp32_s3_gc9a01/src/gui.cpp:138-170`

- [ ] **Step 1: Add `showMapHudCard` definition and `drawMapHudOverlay()` implementation**

In `gui.cpp`:
```cpp
bool showMapHudCard = true;

void drawMapHudOverlay()
{
    if (!showMapHudCard)
    {
        drawMapOverlay();
        return;
    }

    // 1. Vẽ thẻ Floating Card mờ ở nửa dưới màn hình (x=16, y=145, w=208, h=80, r=20)
    canvasSprite.fillRoundRect(16, 145, 208, 80, 20, canvasSprite.color565(15, 23, 42));
    canvasSprite.drawRoundRect(16, 145, 208, 80, 20, TFT_CYAN);

    // 2. Vẽ nút tròn Cyan chứa icon hướng rẽ bên trái (cx=42, cy=185, r=22)
    canvasSprite.fillCircle(42, 185, 22, TFT_CYAN);
    
    // Icon rẽ: Google Maps custom bitmap hoặc vector fallback
    if (hasCustomIcon) {
        drawCustomIcon(canvasSprite, customIconBitmap, 26, 169, 1);
    } else {
        int ax = 42, ay = 185;
        switch (navDirIdx) {
            case 2: // Right turn
                canvasSprite.drawLine(ax - 8, ay + 8, ax - 8, ay, TFT_BLACK);
                canvasSprite.drawLine(ax - 8, ay, ax + 8, ay, TFT_BLACK);
                canvasSprite.fillTriangle(ax + 8, ay, ax + 2, ay - 6, ax + 2, ay + 6, TFT_BLACK);
                break;
            case 5: // Left turn
                canvasSprite.drawLine(ax + 8, ay + 8, ax + 8, ay, TFT_BLACK);
                canvasSprite.drawLine(ax + 8, ay, ax - 8, ay, TFT_BLACK);
                canvasSprite.fillTriangle(ax - 8, ay, ax - 2, ay - 6, ax - 2, ay + 6, TFT_BLACK);
                break;
            default: // Straight / fallback
                canvasSprite.drawLine(ax, ay + 8, ax, ay - 8, TFT_BLACK);
                canvasSprite.fillTriangle(ax, ay - 10, ax - 5, ay - 3, ax + 5, ay - 3, TFT_BLACK);
                break;
        }
    }

    // 3. In Tên đường Google Maps (dòng 1) - Tự động cuộn nếu quá dài
    myFont.set_font(FONT_HUD_STREET);
    uint16_t streetLen = myFont.getLength(nextStreet);
    int visibleWidth = 140;
    int startX = 72;
    int startY = 156;

    if (streetLen > visibleWidth)
    {
        clipMinX = startX;
        clipMaxX = startX + visibleWidth;
        clipMinY = startY;
        clipMaxY = startY + 24;

        int range = streetLen - visibleWidth + 30;
        int scrollMs = millis() % (range * 35 + 1000);
        int scrollX = 0;
        if (scrollMs > 1000) scrollX = (scrollMs - 1000) / 35;

        myFont.print(startX - scrollX, startY + 4, nextStreet, TFT_WHITE, canvasSprite.color565(15, 23, 42));

        clipMinX = 0; clipMaxX = 240;
        clipMinY = 0; clipMaxY = 240;
    }
    else
    {
        myFont.print(startX, startY + 4, nextStreet, TFT_WHITE, canvasSprite.color565(15, 23, 42));
    }

    // 4. In Khoảng cách rẽ Google Maps (dòng 2) màu xanh lá neon
    myFont.set_font(FONT_HUD_DIST);
    myFont.print(startX, startY + 32, distToNext, canvasSprite.color565(0, 255, 127), canvasSprite.color565(15, 23, 42));

    // 5. Vẽ Overlay Cảnh báo Giao thông (nếu có)
    drawTrafficWarningOverlay();

    canvasSprite.pushSprite(0, 0);
}
```

- [ ] **Step 2: Commit `drawMapHudOverlay` implementation**

```bash
git add TYMAP/firmware/esp32_s3_gc9a01/src/gui.cpp
git commit -m "feat(esp32): implement drawMapHudOverlay with floating card and marquee text"
```

---

### Task 3: Update `main.cpp` Logic & Long-Press Button Mapping

**Files:**
- Modify: `TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp:580-596`
- Modify: `TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp:1400-1430`

- [ ] **Step 1: Update `renderJpegImage()` to use `drawMapHudOverlay()`**

In `main.cpp`:
```cpp
void renderJpegImage(const uint8_t *data, uint32_t size)
{
    if (jpeg.openRAM((uint8_t *)data, size, drawJPEG))
    {
        canvasSprite.setSwapBytes(true);
        jpeg.decode(0, 0, 0);
        canvasSprite.setSwapBytes(false);
        jpeg.close();

        if (isPopupActive)
        {
            // Transient 5s turn popup in HUD_MODE renders pure map
            drawMapOverlay();
        }
        else if (currentMode == MAP_MODE || currentMode == MAP_HUD_MODE)
        {
            drawMapHudOverlay();
        }

        canvasSprite.pushSprite(0, 0);
    }
}
```

- [ ] **Step 2: Add long-press handler on `btnZoom` to toggle Floating Card**

In `main.cpp` setup / button callbacks:
```cpp
btnZoom.attachLongPressStart([]() {
    showMapHudCard = !showMapHudCard;
    screenNeedsRedraw = true;
    Serial.printf("ZOOM button long-press: showMapHudCard=%d\n", showMapHudCard);
});
```

- [ ] **Step 3: Commit `main.cpp` updates**

```bash
git add TYMAP/firmware/esp32_s3_gc9a01/src/main.cpp
git commit -m "feat(esp32): connect MAP_HUD_MODE rendering and button long-press toggle"
```

---

### Task 4: Verify ESP32 Firmware Build

**Files:**
- Test: `TYMAP/firmware/esp32_s3_gc9a01/platformio.ini`

- [ ] **Step 1: Execute PlatformIO build to ensure no compilation errors**

Run: `pio run -d d:\Documents\PlatformIO\Tdriver\TYMAP\firmware\esp32_s3_gc9a01`
Expected: `SUCCESS`

---

## Plan Self-Review
- Checked spec requirements: All design specs (floating card, auto-scroll, button toggle, popup conflict-free handling) are covered in Tasks 1-4.
- No placeholders included. All code blocks are complete.
