#ifndef BLE_MANAGER_H
#define BLE_MANAGER_H

#include <Arduino.h>
#include <NimBLEDevice.h>

// BLE Service UUID
#define BLE_SERVICE_UUID "0000feed-0000-1000-8000-00805f9b34fb"

// Characteristic UUIDs
#define CHA_NAV_UUID "0b11deef-1563-447f-aece-d3dfeb1c1f20"
#define CHA_NAV_TBT_ICON_UUID "d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad"
#define CHA_GPS_SPEED_UUID "98b6073a-5cf3-4e73-b6d3-f8e05fa018a9"
#define CHA_SETTINGS_UUID "9d37a346-63d3-4df6-8eee-f0242949f59f"
#define CHA_TIME_UUID "a1b2c3d4-e5f6-4789-a012-3456789abcde"
#define CHA_WEATHER_UUID "b2c3d4e5-f6a7-4890-b123-456789abcdef"
#define CHA_MAP_IMAGE_UUID "c3d4e5f6-a7b8-4901-c234-567890abcdef"
#define CHA_DEVICE_CTRL_UUID "d4e5f6a7-b8c9-4012-d345-678901bcdef0"
#define CHA_REMOTE_CMD_UUID "f1a2b3c4-d5e6-4789-a012-3456789abcde"
#define CHA_DEVICE_STATUS_UUID "a1b2c3d4-e5f6-4789-b012-3456789abcde"

// Max JPEG buffer size (32KB)
#define JPEG_BUF_MAX_SIZE (32 * 1024)

// Navigation data structure
typedef struct
{
    char dist[24];        // Distance string (e.g. "100 m", "1.2 km")
    char road[48];        // Road name or next destination
    char instruction[48]; // Turn instruction (e.g. "Rẽ phải")
    char eta[16];         // ETA string (e.g. "12:45")
    char ete[24];         // Time remaining (e.g. "15 phút")
    bool active;          // Navigation active
    bool navigating;      // Currently navigating
} NavData;

// Weather data structure
typedef struct
{
    float temperature;
    char iconCode[8]; // OpenWeatherMap icon code (e.g. "01d")
} WeatherData;

// Global data shared between BLE callbacks and main loop
extern NavData g_navData;
extern WeatherData g_weatherData;
extern char g_gpsSpeedStr[16];
extern uint8_t g_tbtIconIndex;
extern uint8_t g_tbtBitmap[288]; // 48x48 1bpp bitmap
extern bool g_tbtBitmapValid;
extern uint8_t g_deviceCtrlByte;
extern bool g_newNavData;
extern bool g_newWeatherData;
extern bool g_newGpsSpeed;
extern bool g_newJPEG;
extern bool g_newTbtIcon;
extern uint8_t g_backlightLevel;

// BLE Manager class
class BLEManager
{
public:
    void init(const char *deviceName = "MotoHUD");
    void sendDeviceCtrl(uint8_t ctrlByte);
    void sendStatusReport(uint8_t mode, float voltage, int8_t rssi);
    bool isConnected();
    int8_t getRssi();

private:
    NimBLEServer *pServer = nullptr;
    NimBLECharacteristic *pCharDeviceCtrl = nullptr;
    NimBLECharacteristic *pCharDeviceStatus = nullptr;

    friend class ServerCallbacks;
    friend class NavCharCallbacks;
    friend class NavTbtIconCallbacks;
    friend class GpsSpeedCallbacks;
    friend class SettingsCallbacks;
    friend class TimeCallbacks;
    friend class WeatherCallbacks;
    friend class MapImageCallbacks;
    friend class RemoteCmdCallbacks;
};

// Characteristic callback classes
class NavCharCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override;
};

class NavTbtIconCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override;
};

class GpsSpeedCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override;
};

class SettingsCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override;
};

class TimeCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override;
};

class WeatherCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override;
};

class MapImageCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override;
};

class RemoteCmdCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override;
};

extern BLEManager bleManager;

#endif // BLE_MANAGER_H
