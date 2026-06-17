#ifndef BLE_MANAGER_H
#define BLE_MANAGER_H

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>

// -------------------------------------------------------------------
// BLE Characteristic UUIDs (theo giao thức)
// -------------------------------------------------------------------
#define SERVICE_UUID "f1e4b2c3-5a67-4b89-9c0d-1e2f3a4b5c6d"

#define CHA_NAV_UUID "0b11deef-1563-447f-aece-d3dfeb1c1f20"
#define CHA_NAV_TBT_ICON_UUID "d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad"
#define CHA_GPS_SPEED_UUID "98b6073a-5cf3-4e73-b6d3-f8e05fa018a9"
#define CHA_SETTINGS_UUID "9d37a346-63d3-4df6-8eee-f0242949f59f"
#define CHA_TIME_UUID "a1b2c3d4-e5f6-4789-a012-3456789abcde"
#define CHA_WEATHER_UUID "b2c3d4e5-f6a7-4890-b123-456789abcdef"
#define CHA_MAP_IMAGE_UUID "c3d4e5f6-a7b8-4901-c234-567890abcdef"
#define CHA_DEVICE_CTRL_UUID "d4e5f6a7-b8c9-4012-d345-678901bcdef0"
#define CHA_DEVICE_STATUS_UUID "e5f6a7b8-c9d0-4123-e456-7890abcdef01"

// -------------------------------------------------------------------
// Bitmask cho CHA_DEVICE_CTRL
// -------------------------------------------------------------------
#define CTRL_ZOOM_IN 0x01
#define CTRL_ZOOM_OUT 0x02
#define CTRL_MAP_MODE 0x04
#define CTRL_REFRESH 0x08

// -------------------------------------------------------------------
// Cấu trúc lưu dữ liệu nhận từ App
// -------------------------------------------------------------------
struct BleNavData
{
    String navString;      // nextRd, distToNext, eta, ete, totalDist
    uint8_t iconIndex = 0; // 0-20
    String speedStr;       // km/h
    int brightness = 100;  // 0-100
    bool lightTheme = false;
    int speedLimit = 0; // km/h
    uint32_t unixTime = 0;
    String weatherJson; // {"t":30,"i":"01d"}
    uint8_t *jpegBuffer = nullptr;
    uint32_t jpegSize = 0;
    uint32_t jpegReceived = 0;
    bool newJpegReady = false;
    bool navUpdated = false;
    bool speedUpdated = false;
    bool timeUpdated = false;
    bool weatherUpdated = false;
    bool settingsUpdated = false;
};

class BleManager
{
public:
    void begin(const char *deviceName);
    void sendCtrl(uint8_t byte);
    void sendStatus(const String &status);
    bool isConnected();
    BleNavData *getData() { return &_data; }
    void clearNavFlag() { _data.navUpdated = false; }
    void clearSpeedFlag() { _data.speedUpdated = false; }
    void clearTimeFlag() { _data.timeUpdated = false; }
    void clearWeatherFlag() { _data.weatherUpdated = false; }
    void clearSettingsFlag() { _data.settingsUpdated = false; }
    void clearJpegFlag() { _data.newJpegReady = false; }

private:
    BleNavData _data;
    BLEServer *_server = nullptr;
    BLECharacteristic *_ctrlChar = nullptr;
    BLECharacteristic *_statusChar = nullptr;

    class ServerCallbacks;
    class NavCharCallbacks;
    class TbtIconCharCallbacks;
    class SpeedCharCallbacks;
    class SettingsCharCallbacks;
    class TimeCharCallbacks;
    class WeatherCharCallbacks;
    class MapImageCharCallbacks;
};

#endif // BLE_MANAGER_H
