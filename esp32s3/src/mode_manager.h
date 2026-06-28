#ifndef MODE_MANAGER_H
#define MODE_MANAGER_H

#include <Arduino.h>
#include <OneButton.h>

// Display modes
typedef enum
{
    MODE_HUD = 0,
    MODE_MAP = 1,
    MODE_STATUS = 2
} DisplayMode;

// Device control bitfield for CHA_DEVICE_CTRL
#define CTRL_BIT_ZOOM_IN 0x01
#define CTRL_BIT_ZOOM_OUT 0x02
#define CTRL_BIT_MAP_MODE 0x04
#define CTRL_BIT_REFRESH 0x08

class ModeManager
{
public:
    void init();
    void tick(); // Call in loop()

    // Getters
    DisplayMode getCurrentMode();
    bool isMenuActive();
    int getMenuSelection();

    // Force a mode change
    void setMode(DisplayMode mode);

    // Send device control byte over BLE
    void sendDeviceControl(uint8_t ctrlByte);

private:
    DisplayMode m_currentMode = MODE_HUD;
    DisplayMode m_menuSelection = MODE_HUD;
    bool m_menuActive = false;
    unsigned long m_menuOpenTime = 0;
    const unsigned long MENU_TIMEOUT_MS = 5000;

    // Button instances
    OneButton m_modeBtn;
    OneButton m_zoomBtn;

    // Button callback wrappers
    static void onModeClick(void *ptr);
    static void onModeLongPress(void *ptr);
    static void onZoomClick(void *ptr);
    static void onZoomLongPress(void *ptr);

    // Internal handlers
    void handleModeClick();
    void handleModeLongPress();
    void handleZoomClick();
    void handleZoomLongPress();
    void closeMenu(bool applySelection);
    void redrawMenuOverlay();
};

extern ModeManager modeManager;

#endif // MODE_MANAGER_H
