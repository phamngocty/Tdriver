#include "mode_manager.h"
#include "ble_manager.h"
#include "ui_renderer.h"

ModeManager modeManager;

extern TFT_eSPI tft;

void ModeManager::init()
{
    // Configure MODE button (GPIO0, active LOW, pull-up)
    pinMode(0, INPUT_PULLUP);
    m_modeBtn = OneButton(0, true, true);
    m_modeBtn.attachClick(onModeClick, this);
    m_modeBtn.attachLongPressStart(onModeLongPress, this);
    m_modeBtn.setClickMs(300);
    m_modeBtn.setPressMs(1000);

    // Configure ZOOM button (GPIO9, active LOW, pull-up)
    pinMode(9, INPUT_PULLUP);
    m_zoomBtn = OneButton(9, true, true);
    m_zoomBtn.attachClick(onZoomClick, this);
    m_zoomBtn.attachLongPressStart(onZoomLongPress, this);
    m_zoomBtn.setClickMs(300);
    m_zoomBtn.setPressMs(1000);
}

void ModeManager::tick()
{
    m_modeBtn.tick();
    m_zoomBtn.tick();

    // Auto-close menu after timeout
    if (m_menuActive)
    {
        if (millis() - m_menuOpenTime > MENU_TIMEOUT_MS)
        {
            closeMenu(false); // Close without changing mode
        }
    }
}

DisplayMode ModeManager::getCurrentMode()
{
    return m_currentMode;
}

bool ModeManager::isMenuActive()
{
    return m_menuActive;
}

int ModeManager::getMenuSelection()
{
    return (int)m_menuSelection;
}

void ModeManager::setMode(DisplayMode mode)
{
    m_currentMode = mode;
    // Send mode change notification
    sendDeviceControl(m_currentMode == MODE_MAP ? CTRL_BIT_MAP_MODE : 0);
}

// ============================================================
// Button callback wrappers
// ============================================================

void ModeManager::onModeClick(void *ptr)
{
    ((ModeManager *)ptr)->handleModeClick();
}

void ModeManager::onModeLongPress(void *ptr)
{
    ((ModeManager *)ptr)->handleModeLongPress();
}

void ModeManager::onZoomClick(void *ptr)
{
    ((ModeManager *)ptr)->handleZoomClick();
}

void ModeManager::onZoomLongPress(void *ptr)
{
    ((ModeManager *)ptr)->handleZoomLongPress();
}

// ============================================================
// MODE button handlers
// ============================================================

void ModeManager::handleModeClick()
{
    if (!m_menuActive)
    {
        // Open menu overlay
        m_menuActive = true;
        m_menuSelection = m_currentMode;
        m_menuOpenTime = millis();
        redrawMenuOverlay();
    }
    else
    {
        // Cycle selection
        m_menuSelection = (DisplayMode)(((int)m_menuSelection + 1) % 3);
        redrawMenuOverlay();
        m_menuOpenTime = millis(); // Reset timeout
    }
}

void ModeManager::handleModeLongPress()
{
    // Short beep/log
    Serial.println("MODE long press");

    if (m_menuActive)
    {
        // Apply selected mode
        closeMenu(true);
    }
    else
    {
        // Force return to HUD
        m_currentMode = MODE_HUD;
        sendDeviceControl(0); // bit2=0 (not MAP mode)
        Serial.println("Forced HUD mode");
    }
}

// ============================================================
// ZOOM button handlers
// ============================================================

void ModeManager::handleZoomClick()
{
    if (m_currentMode == MODE_MAP)
    {
        // Short click = zoom in
        sendDeviceControl(CTRL_BIT_ZOOM_IN);
        Serial.println("Zoom in requested");
    }
}

void ModeManager::handleZoomLongPress()
{
    if (m_currentMode == MODE_MAP)
    {
        // Long press = zoom out
        sendDeviceControl(CTRL_BIT_ZOOM_OUT);
        Serial.println("Zoom out requested");
    }
}

// ============================================================
// Menu management
// ============================================================

void ModeManager::closeMenu(bool applySelection)
{
    if (applySelection)
    {
        m_currentMode = m_menuSelection;
        uint8_t ctrl = (m_currentMode == MODE_MAP) ? CTRL_BIT_MAP_MODE : 0;
        sendDeviceControl(ctrl);
        Serial.printf("Mode changed to: %d\n", m_currentMode);
    }
    m_menuActive = false;
    // Redraw full screen for new mode
    // (Main loop handles this via mode check)
}

void ModeManager::redrawMenuOverlay()
{
    // Draw overlay via UI renderer
    uiRenderer.drawMenuOverlay(m_menuActive, (int)m_menuSelection);
}

void ModeManager::sendDeviceControl(uint8_t ctrlByte)
{
    bleManager.sendDeviceCtrl(ctrlByte);
}
