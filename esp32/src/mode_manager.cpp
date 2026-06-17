#include "mode_manager.h"
#include "ble_manager.h"
#include "MyFont.h"

const char *MODE_NAMES[] = {"HUD", "MAP", "STATUS"};

// -------------------------------------------------------------------
// Màu sắc menu
// -------------------------------------------------------------------
#define MENU_BG 0x0841    // Nền mờ tối
#define MENU_SEL 0xFFFF   // Chọn
#define MENU_UNSEL 0x8410 // Không chọn
#define MENU_ITEM_W 200
#define MENU_ITEM_H 50
#define MENU_ITEM_GAP 8

ModeManager::ModeManager(uint8_t pinMode, uint8_t pinZoom)
    : _btnMode(pinMode, true), // active low
      _btnZoom(pinZoom, true)
{
}

void ModeManager::begin()
{
    _btnMode.attachClick(onModeClickStatic, this);
    _btnMode.attachLongPressStart(onModeLongClickStatic, this);
    _btnMode.setDebounceMs(30);
    _btnMode.setClickMs(400);
    _btnMode.setPressMs(1000);

    _btnZoom.attachClick(onZoomClickStatic, this);
    _btnZoom.attachLongPressStart(onZoomLongClickStatic, this);
    _btnZoom.setDebounceMs(30);
    _btnZoom.setClickMs(400);
    _btnZoom.setPressMs(1000);
}

void ModeManager::tick()
{
    _btnMode.tick();
    _btnZoom.tick();

    // Timeout menu 5 giây
    if (_menuActive && millis() - _menuStartTime > 5000)
    {
        _menuActive = false;
        if (_tft)
            clearMenu();
    }
}

// ---- Static wrappers ----

void ModeManager::onModeClickStatic(void *arg)
{
    static_cast<ModeManager *>(arg)->onModeClick();
}
void ModeManager::onModeLongClickStatic(void *arg)
{
    static_cast<ModeManager *>(arg)->onModeLongClick();
}
void ModeManager::onZoomClickStatic(void *arg)
{
    static_cast<ModeManager *>(arg)->onZoomClick();
}
void ModeManager::onZoomLongClickStatic(void *arg)
{
    static_cast<ModeManager *>(arg)->onZoomLongClick();
}

// ---- Event handlers ----

void ModeManager::onModeClick()
{
    if (!_menuActive)
    {
        // Mở menu
        _menuActive = true;
        _menuSelection = (int)_currentMode;
        _menuStartTime = millis();
        if (_tft)
            drawMenu();
    }
    else
    {
        // Chuyển lựa chọn
        _menuSelection = (_menuSelection + 1) % MODE_COUNT;
        _menuStartTime = millis();
        if (_tft)
            drawMenu();
    }
}

void ModeManager::onModeLongClick()
{
    if (_menuActive)
    {
        // Xác nhận chọn chế độ
        _currentMode = (Mode)_menuSelection;
        _menuActive = false;
        if (_tft)
            clearMenu();

        // Gửi tín hiệu map mode qua BLE
        if (_sendCtrl)
        {
            if (_currentMode == MODE_MAP)
            {
                _sendCtrl(CTRL_MAP_MODE);
            }
        }
        _mapModeToggled = true;
    }
    else
    {
        // Long click khi không có menu: về HUD
        _currentMode = MODE_HUD;
        if (_sendCtrl)
        {
            _sendCtrl(0); // MAP mode off
        }
    }
}

void ModeManager::onZoomClick()
{
    if (_currentMode == MODE_MAP)
    {
        _zoomIn = true;
        if (_sendCtrl)
            _sendCtrl(CTRL_ZOOM_IN);
    }
}

void ModeManager::onZoomLongClick()
{
    if (_currentMode == MODE_MAP)
    {
        _zoomOut = true;
        if (_sendCtrl)
            _sendCtrl(CTRL_ZOOM_OUT);
    }
}

// ---- Menu drawing ----

void ModeManager::drawMenu()
{
    if (!_tft)
        return;

    // Overlay nền mờ (sprite 240×240)
    int16_t totalH = MODE_COUNT * MENU_ITEM_H + (MODE_COUNT - 1) * MENU_ITEM_GAP;
    int16_t startY = (240 - totalH) / 2;
    int16_t startX = (240 - MENU_ITEM_W) / 2;

    // Vẽ nền mờ
    _tft->fillScreen(MENU_BG);

    for (int i = 0; i < MODE_COUNT; i++)
    {
        int16_t y = startY + i * (MENU_ITEM_H + MENU_ITEM_GAP);
        bool selected = (i == _menuSelection);
        uint16_t bg = selected ? TFT_WHITE : 0x3186;
        uint16_t fg = selected ? TFT_BLACK : TFT_WHITE;

        _tft->fillRoundRect(startX, y, MENU_ITEM_W, MENU_ITEM_H, 8, bg);
        _tft->setTextColor(fg, bg);
        _tft->setTextFont(FONT_MEDIUM);
        _tft->setTextSize(2);
        _tft->setTextDatum(MC_DATUM);
        _tft->drawString(MODE_NAMES[i], 120, y + MENU_ITEM_H / 2);
    }

    _tft->setTextFont(FONT_SMALL);
    _tft->setTextColor(TFT_WHITE, MENU_BG);
    _tft->setTextDatum(BC_DATUM);
    _tft->drawString("Long press to confirm", 120, 235);
}

void ModeManager::clearMenu()
{
    if (!_tft)
        return;
    _tft->fillScreen(TFT_BLACK);
}
