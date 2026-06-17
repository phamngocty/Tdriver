#ifndef MODE_MANAGER_H
#define MODE_MANAGER_H

#include <Arduino.h>
#include <OneButton.h>
#include <TFT_eSPI.h>

// -------------------------------------------------------------------
// Các chế độ hiển thị
// -------------------------------------------------------------------
enum Mode : uint8_t
{
    MODE_HUD = 0,
    MODE_MAP = 1,
    MODE_STATUS = 2,
    MODE_COUNT = 3
};

// Tên hiển thị cho từng chế độ
extern const char *MODE_NAMES[];

// -------------------------------------------------------------------
// Quản lý chuyển đổi chế độ, menu overlay, nút bấm
// -------------------------------------------------------------------
class ModeManager
{
public:
    ModeManager(uint8_t pinMode, uint8_t pinZoom);

    void begin();
    void tick(); // Gọi mỗi loop

    Mode getCurrentMode() const { return _currentMode; }
    bool isMenuActive() const { return _menuActive; }
    int getMenuSelection() const { return _menuSelection; }
    bool isZoomInTriggered()
    {
        bool v = _zoomIn;
        _zoomIn = false;
        return v;
    }
    bool isZoomOutTriggered()
    {
        bool v = _zoomOut;
        _zoomOut = false;
        return v;
    }
    bool isMapModeToggled()
    {
        bool v = _mapModeToggled;
        _mapModeToggled = false;
        return v;
    }

    void setTft(TFT_eSPI *tft) { _tft = tft; }
    void setOnSendCtrl(void (*cb)(uint8_t)) { _sendCtrl = cb; }

private:
    OneButton _btnMode;
    OneButton _btnZoom;
    TFT_eSPI *_tft = nullptr;
    Mode _currentMode = MODE_HUD;
    bool _menuActive = false;
    int _menuSelection = 0;
    unsigned long _menuStartTime = 0;
    bool _zoomIn = false;
    bool _zoomOut = false;
    bool _mapModeToggled = false;
    void (*_sendCtrl)(uint8_t) = nullptr;

    static void onModeClickStatic(void *arg);
    static void onModeLongClickStatic(void *arg);
    static void onZoomClickStatic(void *arg);
    static void onZoomLongClickStatic(void *arg);
    void onModeClick();
    void onModeLongClick();
    void onZoomClick();
    void onZoomLongClick();

    void drawMenu();
    void clearMenu();
};

#endif // MODE_MANAGER_H
