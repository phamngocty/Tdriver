#include "ble_manager.h"
#include "jpeg_handler.h"
#include <ArduinoJson.h>
#include <esp32Time.h>

// ============================================================
// Global shared data
// ============================================================
NavData g_navData = {0};
WeatherData g_weatherData = {0};
char g_gpsSpeedStr[16] = "0";
uint8_t g_tbtIconIndex = 0;
uint8_t g_tbtBitmap[288] = {0};
bool g_tbtBitmapValid = false;
bool g_newNavData = false;
bool g_newWeatherData = false;
bool g_newGpsSpeed = false;
bool g_newJPEG = false;
bool g_newTbtIcon = false;
uint8_t g_backlightLevel = 128;

extern ESP32Time rtc;

// ============================================================
// Helper: parse key=value lines from nav string
// ============================================================
static void parseNavData(const char *str)
{
    g_newNavData = true;
    char buffer[512];
    strncpy(buffer, str, sizeof(buffer) - 1);
    buffer[sizeof(buffer) - 1] = '\0';

    char *line = strtok(buffer, "\n");
    while (line)
    {
        while (*line == ' ' || *line == '\r')
            line++;
        char *eq = strchr(line, '=');
        if (eq)
        {
            *eq = '\0';
            const char *key = line;
            const char *val = eq + 1;
            if (strcmp(key, "dist") == 0)
                strncpy(g_navData.dist, val, sizeof(g_navData.dist) - 1);
            else if (strcmp(key, "title") == 0 || strcmp(key, "road") == 0)
                strncpy(g_navData.road, val, sizeof(g_navData.road) - 1);
            else if (strcmp(key, "dir") == 0 || strcmp(key, "inst") == 0)
                strncpy(g_navData.instruction, val, sizeof(g_navData.instruction) - 1);
            else if (strcmp(key, "eta") == 0)
                strncpy(g_navData.eta, val, sizeof(g_navData.eta) - 1);
            else if (strcmp(key, "ete") == 0)
                strncpy(g_navData.ete, val, sizeof(g_navData.ete) - 1);
            else if (strcmp(key, "active") == 0)
                g_navData.active = (atoi(val) == 1);
            else if (strcmp(key, "nav") == 0)
                g_navData.navigating = (atoi(val) == 1);
        }
        line = strtok(NULL, "\n");
    }
}

// ============================================================
// BLE Characteristic Callbacks
// ============================================================
void NavCharCallbacks::onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo)
{
    (void)connInfo;
    std::string value = pChar->getValue();
    if (value.length() > 0)
        parseNavData(value.c_str());
}

void NavTbtIconCallbacks::onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo)
{
    (void)connInfo;
    std::string value = pChar->getValue();
    size_t len = value.length();
    if (len == 1)
    {
        g_tbtIconIndex = (uint8_t)value[0];
        if (g_tbtIconIndex > 20)
            g_tbtIconIndex = 0;
        g_tbtBitmapValid = false;
        g_newTbtIcon = true;
    }
    else if (len == 288)
    {
        memcpy(g_tbtBitmap, value.data(), 288);
        g_tbtBitmapValid = true;
        g_newTbtIcon = true;
    }
}

void GpsSpeedCallbacks::onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo)
{
    (void)connInfo;
    std::string value = pChar->getValue();
    if (value.length() > 0)
    {
        strncpy(g_gpsSpeedStr, value.c_str(), sizeof(g_gpsSpeedStr) - 1);
        g_newGpsSpeed = true;
    }
}

void SettingsCallbacks::onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo)
{
    (void)connInfo;
    std::string value = pChar->getValue();
    if (value.length() == 0)
        return;

    char buffer[256];
    strncpy(buffer, value.c_str(), sizeof(buffer) - 1);
    buffer[sizeof(buffer) - 1] = '\0';

    char *line = strtok(buffer, "\n");
    while (line)
    {
        while (*line == ' ' || *line == '\r')
            line++;
        char *eq = strchr(line, '=');
        if (eq)
        {
            *eq = '\0';
            if (strcmp(line, "brightness") == 0)
            {
                int level = atoi(eq + 1);
                if (level < 0)
                    level = 0;
                if (level > 255)
                    level = 255;
                g_backlightLevel = (uint8_t)level;
            }
        }
        line = strtok(NULL, "\n");
    }
}

void TimeCallbacks::onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo)
{
    (void)connInfo;
    std::string value = pChar->getValue();
    if (value.length() >= 4)
    {
        uint32_t epoch = (uint8_t)value[0] | ((uint32_t)(uint8_t)value[1] << 8) | ((uint32_t)(uint8_t)value[2] << 16) | ((uint32_t)(uint8_t)value[3] << 24);
        rtc.setTime(epoch);
    }
}

void WeatherCallbacks::onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo)
{
    (void)connInfo;
    std::string value = pChar->getValue();
    if (value.length() == 0)
        return;

    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, value.c_str());
    if (!err)
    {
        g_weatherData.temperature = doc["t"] | 0.0f;
        const char *icon = doc["i"] | "";
        strncpy(g_weatherData.iconCode, icon, sizeof(g_weatherData.iconCode) - 1);
        g_newWeatherData = true;
    }
}

void MapImageCallbacks::onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo)
{
    (void)connInfo;
    std::string value = pChar->getValue();
    size_t len = value.length();
    if (len == 0)
        return;

    if (len == 4 && !jpegHandler.isComplete())
    {
        uint32_t totalSize = (uint8_t)value[0] | ((uint32_t)(uint8_t)value[1] << 8) | ((uint32_t)(uint8_t)value[2] << 16) | ((uint32_t)(uint8_t)value[3] << 24);
        jpegHandler.beginReceive(totalSize);
        return;
    }
    jpegHandler.feedData((const uint8_t *)value.data(), len);
    if (jpegHandler.isComplete())
        g_newJPEG = true;
}

void RemoteCmdCallbacks::onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo)
{
    (void)pChar;
    (void)connInfo;
    // Reserved for future remote commands
}

// ============================================================
// Server callbacks
// ============================================================
class ServerCallbacks : public NimBLEServerCallbacks
{
    void onConnect(NimBLEServer *pServer, NimBLEConnInfo &connInfo) override
    {
        Serial.printf("BLE connected: %s\n", connInfo.getAddress().toString().c_str());
    }
    void onDisconnect(NimBLEServer *pServer, NimBLEConnInfo &connInfo, int reason) override
    {
        (void)connInfo;
        (void)reason;
        Serial.println("BLE client disconnected");
        pServer->startAdvertising();
    }
    void onMTUChange(uint16_t mtu, NimBLEConnInfo &connInfo) override
    {
        (void)connInfo;
        Serial.printf("BLE MTU updated: %d\n", mtu);
    }
};

// ============================================================
// BLEManager implementation
// ============================================================
BLEManager bleManager;

void BLEManager::init(const char *deviceName)
{
    NimBLEDevice::init(deviceName);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);

    pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    NimBLEService *pService = pServer->createService(BLE_SERVICE_UUID);

    // CHA_NAV: Write, Notify
    NimBLECharacteristic *pCharNav = pService->createCharacteristic(
        CHA_NAV_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    pCharNav->setCallbacks(new NavCharCallbacks());

    // CHA_NAV_TBT_ICON: Write
    NimBLECharacteristic *pCharTbtIcon = pService->createCharacteristic(
        CHA_NAV_TBT_ICON_UUID, NIMBLE_PROPERTY::WRITE);
    pCharTbtIcon->setCallbacks(new NavTbtIconCallbacks());

    // CHA_GPS_SPEED: Write, Notify
    NimBLECharacteristic *pCharSpeed = pService->createCharacteristic(
        CHA_GPS_SPEED_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    pCharSpeed->setCallbacks(new GpsSpeedCallbacks());

    // CHA_SETTINGS: Write
    NimBLECharacteristic *pCharSettings = pService->createCharacteristic(
        CHA_SETTINGS_UUID, NIMBLE_PROPERTY::WRITE);
    pCharSettings->setCallbacks(new SettingsCallbacks());

    // CHA_TIME: Write
    NimBLECharacteristic *pCharTime = pService->createCharacteristic(
        CHA_TIME_UUID, NIMBLE_PROPERTY::WRITE);
    pCharTime->setCallbacks(new TimeCallbacks());

    // CHA_WEATHER: Write
    NimBLECharacteristic *pCharWeather = pService->createCharacteristic(
        CHA_WEATHER_UUID, NIMBLE_PROPERTY::WRITE);
    pCharWeather->setCallbacks(new WeatherCallbacks());

    // CHA_MAP_IMAGE: Write
    NimBLECharacteristic *pCharMapImage = pService->createCharacteristic(
        CHA_MAP_IMAGE_UUID, NIMBLE_PROPERTY::WRITE);
    pCharMapImage->setCallbacks(new MapImageCallbacks());

    // CHA_DEVICE_CTRL: Write, Notify (bidirectional)
    pCharDeviceCtrl = pService->createCharacteristic(
        CHA_DEVICE_CTRL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    pCharDeviceCtrl->setCallbacks(new RemoteCmdCallbacks());

    // CHA_REMOTE_CMD: Write
    NimBLECharacteristic *pCharRemoteCmd = pService->createCharacteristic(
        CHA_REMOTE_CMD_UUID, NIMBLE_PROPERTY::WRITE);
    pCharRemoteCmd->setCallbacks(new RemoteCmdCallbacks());

    // CHA_DEVICE_STATUS: Notify
    pCharDeviceStatus = pService->createCharacteristic(
        CHA_DEVICE_STATUS_UUID, NIMBLE_PROPERTY::NOTIFY);

    // Services are auto-started when server starts; no need to call start()
    NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(BLE_SERVICE_UUID);
    pAdvertising->setAppearance(0x0000);
    pAdvertising->setName(deviceName);
    pAdvertising->start();

    Serial.println("BLE server started, advertising as: " + String(deviceName));
}

void BLEManager::sendDeviceCtrl(uint8_t ctrlByte)
{
    if (pCharDeviceCtrl && isConnected())
    {
        pCharDeviceCtrl->setValue(&ctrlByte, 1);
        pCharDeviceCtrl->notify();
    }
}

void BLEManager::sendStatusReport(uint8_t mode, float voltage, int8_t rssi)
{
    if (!pCharDeviceStatus || !isConnected())
        return;
    char buf[128];
    snprintf(buf, sizeof(buf), "mode=%d\nvoltage=%.1f\nrssi=%d\nping=1\n",
             mode, voltage, rssi);
    pCharDeviceStatus->setValue((uint8_t *)buf, strlen(buf));
    pCharDeviceStatus->notify();
}

bool BLEManager::isConnected()
{
    return pServer && pServer->getConnectedCount() > 0;
}

int8_t BLEManager::getRssi()
{
    if (!isConnected())
        return -127;
    // Return default RSSI; full retrieval requires NimBLE host API
    return -65;
}
