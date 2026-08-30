#include <Arduino.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <NimBLEDevice.h>
#include <ESP32Time.h>
#include <OneButton.h>
#include <ArduinoJson.h>
#include <FontMaker.h>
#include <Update.h>
#include <Preferences.h>
#include "gui.h"

Preferences preferences;

#ifndef OLED_SDA
#define OLED_SDA 6
#endif

#ifndef OLED_SCL
#define OLED_SCL 7
#endif

#define MODE_BTN 1
#define ZOOM_BTN 2
#define BAT_ADC 3

// Firmware Version
#define FW_VERSION_STR "1.0.9"
#define FW_VERSION_CODE 9

// GATT Server UUIDs
const char *SERVICE_UUID = "0000feed-0000-1000-8000-00805f9b34fb";
const char *CHA_NAV_UUID = "0b11deef-1563-447f-aece-d3dfeb1c1f20";
const char *CHA_NAV_TBT_ICON_UUID = "d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad";
const char *CHA_ICON_DATA_UUID = "e2f3a4b5-c6d7-4890-e123-456789abcdef";
const char *CHA_GPS_SPEED_UUID = "98b6073a-5cf3-4e73-b6d3-f8e05fa018a9";
const char *CHA_SETTINGS_UUID = "9d37a346-63d3-4df6-8eee-f0242949f59f";
const char *CHA_TIME_UUID = "a1b2c3d4-e5f6-4789-a012-3456789abcde";
const char *CHA_WEATHER_UUID = "b2c3d4e5-f6a7-4890-b123-456789abcdef";
const char *CHA_OLED_IMAGE_UUID = "e1f2a3b4-c5d6-4789-a012-3456789abcde";
const char *CHA_MAP_IMAGE_UUID = "c3d4e5f6-a7b8-4901-c234-567890abcdef";
const char *CHA_DEVICE_CTRL_UUID = "d4e5f6a7-b8c9-4012-d345-678901bcdef0";
const char *CHA_REMOTE_CMD_UUID = "f1a2b3c4-d5e6-4789-a012-3456789abcde";
const char *CHA_DEVICE_STATUS_UUID = "a1b2c3d4-e5f6-4789-b012-3456789abcde";
const char *CHA_NOTIFICATION_UUID = "c1d2e3f4-a5b6-4789-c012-3456789abcde";
const char *CHA_PHONE_BATTERY_UUID = "e5f6a7b8-c9d0-4123-e456-789012cdef01";
const char *CHA_WARNING_UUID = "e4f5a6b7-c8d9-4012-e345-678901bcdef0";
const char *CHA_OTA_UUID = "f0a1b2c3-d4e5-4f60-a012-bcdef0123456";
const char *CHA_MAP_TILE_UUID = "d1e2f3a4-b5c6-4789-d012-3456789abcde";
const char *CHA_MAP_CTRL_UUID = "e2f3a4b5-c6d7-4890-e123-456789abcdef";
const char *CHA_MAP_STATUS_UUID = "f3a4b5c6-d7e8-4901-f234-567890abcdef";

// Khởi tạo phần cứng U8g2 SH1106 I2C 128x64
U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, /* reset=*/ U8X8_PIN_NONE);

// Tọa độ Giới hạn Cửa sổ vẽ (Clipping Window)
int16_t clipMinX = 0;
int16_t clipMaxX = 128;
int16_t clipMinY = 0;
int16_t clipMaxY = 64;

// Callback vẽ pixel cho FontMaker với hỗ trợ Clipping chống lem viền
void drawPixelOnOled(int16_t x, int16_t y, uint16_t color)
{
    if (color != 0 && x >= clipMinX && x < clipMaxX && y >= clipMinY && y < clipMaxY)
    {
        u8g2.setDrawColor(1);
        u8g2.drawPixel(x, y);
    }
}
MakeFont myFont(drawPixelOnOled);

ESP32Time rtc;
OneButton btnMode(MODE_BTN, true);
OneButton btnZoom(ZOOM_BTN, true);

// BLE Characteristics
NimBLECharacteristic *pDeviceStatusChar = nullptr;
NimBLECharacteristic *pDeviceCtrlChar = nullptr;

// Biến trạng thái toàn cục
Mode currentMode = STATUS_MODE;
Mode selectedMode = STATUS_MODE;
Mode previousModeBeforeNotif = STATUS_MODE;
bool isNavigating = false;
unsigned long lastNavDataTime = 0;
unsigned long lastMapFrameTime = 0;
unsigned long lastUserInteractionTime = 0;
volatile bool bleConnected = false;

String nextStreet = "";
String distToNext = "";
String totalDist = "";
String eta = "";
String ete = "";
int navDirIdx = 0;
int gpsSpeed = 0;
uint8_t staticIconIndex = 0;
bool hasCustomIcon = false;
uint8_t customIconBitmap[288];

float weatherTemp = -999.0f;
String weatherIcon = "";
float batteryVoltage = 0.0f;
unsigned long lastStatusSent = 0;

int phoneBatteryLevel = -1;
bool phoneBatteryCharging = false;
bool timeSynced = false;

NotificationItem notifList[3];
int notifCount = 0;
int notifViewIndex = 0;
bool isNotifPopupTransient = false;
unsigned long notifPopupStartTime = 0;

bool isTrafficWarningActive = false;
uint8_t trafficWarningType = 0;
uint8_t trafficWarningValue = 0;
unsigned long trafficWarningStartTime = 0;

bool isOtaMode = false;
uint32_t otaExpectedSize = 0;
uint32_t otaWritten = 0;

uint8_t oledBuffer[1024];
bool hasActiveOledImage = false;
uint32_t oledImageSize = 0;
uint32_t oledImageWritten = 0;
bool isReceivingOledImage = false;

volatile bool screenNeedsRedraw = true;
volatile bool needClearScreen = false;
unsigned long lastRedrawTime = 0;

// Gửi trạng thái thiết bị sang App qua BLE
void sendDeviceStatus()
{
    if (!pDeviceStatusChar) return;
    char buffer[192];
    String modeStr = "STATUS";
    if (currentMode == HUD_MODE) modeStr = "HUD";
    else if (currentMode == MAP_MODE) modeStr = "MAP";
    else if (currentMode == INFO_MODE) modeStr = "INFO";
    else if (currentMode == NOTIF_MODE) modeStr = "NOTIF";

    int rssi = -55 - (random() % 15);

    snprintf(buffer, sizeof(buffer),
             "mode=%s\nvoltage=%.2f\nrssi=%d\ndisplay=SH1106\ntimeSynced=%d\nnotifCount=%d\nver=%s\nfw_code=%d",
             modeStr.c_str(), batteryVoltage, rssi,
             timeSynced ? 1 : 0, notifCount, FW_VERSION_STR, FW_VERSION_CODE);

    pDeviceStatusChar->setValue((uint8_t*)buffer, strlen(buffer));
    pDeviceStatusChar->notify();
    lastStatusSent = millis();
}

// Đo điện áp pin / ắc quy xe với độ nhạy Oscilloscope & chống nhiễu bugi
void updateBatteryVoltage()
{
    static unsigned long lastSampleTime = 0;
    unsigned long now = millis();
    if (now - lastSampleTime < autoSampleIntervalMs && lastSampleTime != 0)
    {
        return; // Auto-Time: Tự động điều chỉnh chu kỳ lấy mẫu 15ms - 50ms theo động lực học tín hiệu
    }
    lastSampleTime = now;

    // Lấy 16 mẫu nhanh và lọc cắt tỉa ngoại lai (Trimmed-Mean Filter triệt tiêu xung bugi)
    const int NUM_SAMPLES = 16;
    uint32_t samples[NUM_SAMPLES];
    for (int i = 0; i < NUM_SAMPLES; i++)
    {
        samples[i] = analogReadMilliVolts(BAT_ADC);
    }

    for (int i = 0; i < NUM_SAMPLES - 1; i++)
    {
        for (int j = i + 1; j < NUM_SAMPLES; j++)
        {
            if (samples[i] > samples[j])
            {
                uint32_t temp = samples[i];
                samples[i] = samples[j];
                samples[j] = temp;
            }
        }
    }

    // Bỏ qua 4 mẫu thấp nhất và 4 mẫu cao nhất, lấy trung bình 8 mẫu ở giữa
    uint32_t sumMv = 0;
    for (int i = 4; i < 12; i++)
    {
        sumMv += samples[i];
    }
    float rawMv = (float)sumMv / 8.0f;

    // Cầu phân áp R1 = 100k, R2 = 10k => Hệ số: (100k + 10k) / 10k = 11.0f
    float instantVoltage = (rawMv / 1000.0f) * 11.0f;

    // Khởi tạo giá trị ban đầu
    if (batteryVoltage <= 0.5f)
    {
        batteryVoltage = instantVoltage;
        pushVoltSample(batteryVoltage);
        return;
    }

    // 1. Cập nhật điện áp hiển thị số (Lọc mượt mà êm dịu, không rung số lẻ)
    batteryVoltage = batteryVoltage + 0.12f * (instantVoltage - batteryVoltage);

    // 2. Đẩy trực tiếp điện áp tức thời vào máy hiện sóng (bảo toàn 100% hình dáng uốn cong tự nhiên, không bị hãm bẹp đầu)
    pushVoltSample(instantVoltage);
}

volatile bool advertisingPending = false;

// Server Callbacks BLE
class MyServerCallbacks : public NimBLEServerCallbacks
{
    void onConnect(NimBLEServer *pServer) override
    {
        bleConnected = true;
        screenNeedsRedraw = true;
        sendDeviceStatus();
    }
    void onConnect(NimBLEServer *pServer, ble_gap_conn_desc *desc) override
    {
        bleConnected = true;
        screenNeedsRedraw = true;
        sendDeviceStatus();
    }
    void onDisconnect(NimBLEServer *pServer) override
    {
        bleConnected = false;
        isNavigating = false;
        hasActiveOledImage = false;
        hasCustomIcon = false;
        if (currentMode == HUD_MODE || currentMode == MAP_MODE) {
            currentMode = STATUS_MODE;
        }
        screenNeedsRedraw = true;
        advertisingPending = true;
    }
    void onDisconnect(NimBLEServer *pServer, ble_gap_conn_desc *desc) override
    {
        bleConnected = false;
        isNavigating = false;
        hasActiveOledImage = false;
        hasCustomIcon = false;
        if (currentMode == HUD_MODE || currentMode == MAP_MODE) {
            currentMode = STATUS_MODE;
        }
        screenNeedsRedraw = true;
        advertisingPending = true;
    }
};

// Cấu trúc và bộ nhớ Cache Icon TBT
struct CachedIcon {
    uint32_t hash;
    uint8_t bitmap[288];
};
CachedIcon iconCache[20];
int cacheSize = 0;
uint32_t currentIconHash = 0;

bool getCachedIcon(uint32_t hash, uint8_t *outBitmap) {
    for (int i = 0; i < cacheSize; i++) {
        if (iconCache[i].hash == hash) {
            memcpy(outBitmap, iconCache[i].bitmap, 288);
            return true;
        }
    }
    return false;
}

void addIconToCache(uint32_t hash, const uint8_t *bitmap) {
    for (int i = 0; i < cacheSize; i++) {
        if (iconCache[i].hash == hash) return;
    }
    if (cacheSize < 20) {
        iconCache[cacheSize].hash = hash;
        memcpy(iconCache[cacheSize].bitmap, bitmap, 288);
        cacheSize++;
    } else {
        for (int i = 0; i < 19; i++) {
            iconCache[i] = iconCache[i + 1];
        }
        iconCache[19].hash = hash;
        memcpy(iconCache[19].bitmap, bitmap, 288);
    }
}

// Characterstic Callback: Dẫn đường HUD
class NavCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;

        String data = String(val.c_str());
        int navActive = -1;

        int start = 0;
        while (start < data.length()) {
            int end = data.indexOf('\n', start);
            if (end == -1) end = data.length();
            String line = data.substring(start, end);
            start = end + 1;

            int eq = line.indexOf('=');
            if (eq != -1) {
                String k = line.substring(0, eq);
                String v = line.substring(eq + 1);
                k.trim(); v.trim();

                if (k == "active" || k == "nav") navActive = v.toInt();
                else if (k == "dist" || k == "distance" || k == "d") distToNext = v;
                else if (k == "inst" || k == "title" || k == "street") nextStreet = v;
                else if (k == "road" || k == "roadName") {
                    if (nextStreet.length() == 0) nextStreet = v;
                    totalDist = v;
                }
                else if (k == "eta") eta = v;
                else if (k == "ete") ete = v;
                else if (k == "dir" || k == "iconIndex") navDirIdx = v.toInt();
                else if (k == "total") totalDist = v;
            }
        }

        if (navActive == 0) {
            isNavigating = false;
            hasCustomIcon = false;
            currentMode = STATUS_MODE;
        } else if (navActive == 1) {
            isNavigating = true;
            lastNavDataTime = millis();
            if (currentMode != MAP_MODE && currentMode != NOTIF_MODE) {
                currentMode = HUD_MODE;
            }
        } else {
            // Có dữ liệu dẫn đường gửi đến (dist hoặc street)
            if (distToNext.length() > 0 || nextStreet.length() > 0) {
                isNavigating = true;
                lastNavDataTime = millis();
                if (currentMode == STATUS_MODE) {
                    currentMode = HUD_MODE;
                }
            }
        }
        screenNeedsRedraw = true;
    }
};

// Characterstic Callback: Nhận mã băm Icon TBT từ Google Maps
class NavIconCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;

        String strVal = String(val.c_str());
        if (strVal.startsWith("hash=")) {
            String hashStr = strVal.substring(5);
            uint32_t hash = strtoul(hashStr.c_str(), nullptr, 16);
            currentIconHash = hash;

            uint8_t bitmap[288];
            if (getCachedIcon(hash, bitmap)) {
                memcpy(customIconBitmap, bitmap, 288);
                hasCustomIcon = true;
                screenNeedsRedraw = true;
            } else {
                hasCustomIcon = false; // Tạm dùng icon vector trong lúc yêu cầu app gửi bitmap
                if (pDeviceStatusChar) {
                    char reqBuf[64];
                    snprintf(reqBuf, sizeof(reqBuf), "icon_req=%s", hashStr.c_str());
                    pDeviceStatusChar->setValue((uint8_t*)reqBuf, strlen(reqBuf));
                    pDeviceStatusChar->notify();
                }
            }
        } else if (val.size() == 288) {
            memcpy(customIconBitmap, val.data(), 288);
            hasCustomIcon = true;
            screenNeedsRedraw = true;
        }
    }
};

// Characterstic Callback: Nhận dữ liệu Icon Bitmap 1bpp (288 bytes / 292 bytes kèm hash)
class IconDataCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.size() == 292) {
            uint32_t hash;
            memcpy(&hash, val.data(), 4);
            const uint8_t *bitmap = (const uint8_t *)(val.data() + 4);
            memcpy(customIconBitmap, bitmap, 288);
            addIconToCache(hash, bitmap);
            hasCustomIcon = true;
            screenNeedsRedraw = true;
        } else if (val.size() >= 288) {
            memcpy(customIconBitmap, val.data(), 288);
            hasCustomIcon = true;
            screenNeedsRedraw = true;
        }
    }
};

// Characterstic Callback: Tốc độ GPS
class SpeedCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;

        String sVal = val.c_str();
        if (sVal.startsWith("speed=")) {
            int speed = 0;
            int start = 0;
            while (start < sVal.length()) {
                int comma = sVal.indexOf(',', start);
                if (comma == -1) comma = sVal.length();
                String part = sVal.substring(start, comma);
                start = comma + 1;
                int eq = part.indexOf('=');
                if (eq != -1) {
                    String k = part.substring(0, eq);
                    String v = part.substring(eq + 1);
                    if (k == "speed") speed = v.toInt();
                }
            }
            gpsSpeed = speed;
        } else {
            gpsSpeed = atoi(val.c_str());
        }
        screenNeedsRedraw = true;
    }
};

// Characterstic Callback: Thời gian (Hỗ trợ cả 4-byte Epoch Binary và 10-char String)
class TimeCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.length() == 4) {
            uint32_t ts;
            memcpy(&ts, val.data(), 4);
            rtc.setTime(ts);
            timeSynced = true;
            screenNeedsRedraw = true;
        } else if (val.length() >= 10) {
            long epoch = atol(val.c_str());
            if (epoch > 1700000000) {
                rtc.setTime(epoch);
                timeSynced = true;
                screenNeedsRedraw = true;
            }
        }
    }
};

// Characterstic Callback: Thời tiết
class WeatherCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (!val.empty()) {
            JsonDocument doc;
            if (deserializeJson(doc, val.c_str()) == DeserializationError::Ok) {
                if (doc["temp"].is<float>()) weatherTemp = doc["temp"].as<float>();
                else if (doc["t"].is<float>()) weatherTemp = doc["t"].as<float>();

                if (doc["icon"].is<const char*>()) weatherIcon = doc["icon"].as<String>();
                else if (doc["i"].is<const char*>()) weatherIcon = doc["i"].as<String>();

                screenNeedsRedraw = true;
            }
        }
    }
};

// Characterstic Callback: Pin Điện Thoại
class PhoneBatteryCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;

        JsonDocument doc;
        if (deserializeJson(doc, val.c_str()) == DeserializationError::Ok) {
            phoneBatteryLevel = doc["level"] | -1;
            phoneBatteryCharging = doc["charging"] | false;
        } else {
            int sep = val.find(',');
            if (sep != std::string::npos) {
                phoneBatteryLevel = atoi(val.substr(0, sep).c_str());
                phoneBatteryCharging = (atoi(val.substr(sep + 1).c_str()) == 1);
            } else {
                phoneBatteryLevel = atoi(val.c_str());
            }
        }
        screenNeedsRedraw = true;
    }
};

// Characterstic Callback: Cảnh báo giao thông (Tốc độ & Camera)
class WarningCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (!val.empty()) {
            if (val.size() >= 2 && (uint8_t)val[0] <= 10) {
                trafficWarningType = (uint8_t)val[0];
                trafficWarningValue = (uint8_t)val[1];
            } else {
                int sep = val.find(',');
                if (sep != std::string::npos) {
                    trafficWarningType = atoi(val.substr(0, sep).c_str());
                    trafficWarningValue = atoi(val.substr(sep + 1).c_str());
                } else {
                    trafficWarningType = atoi(val.c_str());
                    trafficWarningValue = 60;
                }
            }
            isTrafficWarningActive = true;
            trafficWarningStartTime = millis();

            // Nếu đang ở chế độ thường (STATUS_MODE hoặc INFO_MODE), kích hoạt popup thông báo
            if (currentMode == STATUS_MODE || currentMode == INFO_MODE) {
                for (int i = 2; i > 0; i--) {
                    notifList[i] = notifList[i - 1];
                }
                notifList[0].app = "CẢNH BÁO";
                char tBuf[32], mBuf[64];
                snprintf(tBuf, sizeof(tBuf), "QUÁ TỐC ĐỘ: %d KM/H", gpsSpeed);
                snprintf(mBuf, sizeof(mBuf), "Giới hạn: %d km/h | Hiện tại: %d km/h", trafficWarningValue, gpsSpeed);
                notifList[0].title = tBuf;
                notifList[0].msg = mBuf;
                notifList[0].time = millis();
                if (notifCount < 3) notifCount++;
                notifViewIndex = 0;
                previousModeBeforeNotif = currentMode;
                currentMode = NOTIF_MODE;
                isNotifPopupTransient = true;
                notifPopupStartTime = millis();
            }

            screenNeedsRedraw = true;
        }
    }
};

// Characterstic Callback: Thông Báo (Notification)
class NotifCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;

        JsonDocument doc;
        if (deserializeJson(doc, val.c_str()) == DeserializationError::Ok) {
            String app = doc["app"] | "";
            String title = doc["title"] | "";
            String msg = doc["msg"] | "";

            bool isSpeedWarning = (app.indexOf("CẢNH BÁO") != -1 || title.indexOf("QUÁ TỐC ĐỘ") != -1 || title.indexOf("TỐC ĐỘ") != -1);
            if (isSpeedWarning) {
                isTrafficWarningActive = true;
                trafficWarningStartTime = millis();

                int slashIdx = title.indexOf('/');
                if (slashIdx != -1) {
                    int valParsed = title.substring(slashIdx + 1).toInt();
                    if (valParsed > 0) trafficWarningValue = valParsed;
                } else if (trafficWarningValue <= 0) {
                    trafficWarningValue = 60;
                }

                // Khi đang ở chế độ HUD hoặc đang dẫn đường:
                // KHÔNG hiển thị popup NOTIF_MODE làm che màn hình điều hướng,
                // để HUD tự nhấp nháy cảnh báo giới hạn tốc độ.
                if (currentMode == HUD_MODE || isNavigating) {
                    screenNeedsRedraw = true;
                    return;
                }
            }

            for (int i = 2; i > 0; i--) {
                notifList[i] = notifList[i - 1];
            }
            notifList[0].app = app;
            notifList[0].title = title;
            notifList[0].msg = msg;
            notifList[0].time = millis();

            if (notifCount < 3) notifCount++;
            notifViewIndex = 0;

            if (currentMode != NOTIF_MODE) {
                previousModeBeforeNotif = currentMode;
            }
            currentMode = NOTIF_MODE;
            isNotifPopupTransient = true;
            notifPopupStartTime = millis();
            screenNeedsRedraw = true;
        }
    }
};


// Characterstic Callback: Cài Đặt (Settings - JSON & Key-Value)
class SettingsCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;
        lastUserInteractionTime = millis();

        String s = val.c_str();
        if (s.startsWith("{")) {
            JsonDocument doc;
            if (deserializeJson(doc, s.c_str()) == DeserializationError::Ok) {
                if (doc["hud_style"].is<int>()) {
                    hudStyle = doc["hud_style"].as<int>() % 4;
                    preferences.putUChar("hudStyle", hudStyle);
                    currentMode = HUD_MODE;
                } else if (doc["hudStyle"].is<int>()) {
                    hudStyle = doc["hudStyle"].as<int>() % 4;
                    preferences.putUChar("hudStyle", hudStyle);
                    currentMode = HUD_MODE;
                }

                if (doc["status_style"].is<int>()) {
                    statusStyle = doc["status_style"].as<int>() % 5;
                    preferences.putUChar("statusStyle", statusStyle);
                    currentMode = STATUS_MODE;
                } else if (doc["statusStyle"].is<int>()) {
                    statusStyle = doc["statusStyle"].as<int>() % 5;
                    preferences.putUChar("statusStyle", statusStyle);
                    currentMode = STATUS_MODE;
                }

                if (doc["notif_style"].is<int>()) {
                    notifStyle = doc["notif_style"].as<int>() % 3;
                    preferences.putUChar("notifStyle", notifStyle);
                } else if (doc["notifStyle"].is<int>()) {
                    notifStyle = doc["notifStyle"].as<int>() % 3;
                    preferences.putUChar("notifStyle", notifStyle);
                }

                if (doc["mode"].is<int>()) {
                    currentMode = (Mode)(doc["mode"].as<int>() % 5);
                }

                if (doc["brightness"].is<int>()) {
                    brightness = doc["brightness"].as<int>();
                    preferences.putInt("brightness", brightness);
                    uint8_t cVal = (brightness <= 100) ? (uint8_t)((brightness * 255) / 100) : (uint8_t)(brightness > 255 ? 255 : brightness);
                    u8g2.setContrast(cVal);
                }
                screenNeedsRedraw = true;
            }
        } else {
            int startIdx = 0;
            while (startIdx < s.length()) {
                int endIdx = s.indexOf('\n', startIdx);
                if (endIdx == -1) endIdx = s.length();
                String line = s.substring(startIdx, endIdx);
                startIdx = endIdx + 1;

                int eqIdx = line.indexOf('=');
                if (eqIdx != -1) {
                    String key = line.substring(0, eqIdx);
                    String value = line.substring(eqIdx + 1);
                    key.trim(); value.trim();

                    if (key == "brightness") {
                        brightness = value.toInt();
                        preferences.putInt("brightness", brightness);
                        uint8_t cVal = (brightness <= 100) ? (uint8_t)((brightness * 255) / 100) : (uint8_t)(brightness > 255 ? 255 : brightness);
                        u8g2.setContrast(cVal);
                    } else if (key == "statusStyle" || key == "status_style") {
                        statusStyle = (uint8_t)(value.toInt() % 5);
                        preferences.putUChar("statusStyle", statusStyle);
                        currentMode = STATUS_MODE;
                    } else if (key == "hudStyle" || key == "hud_style") {
                        hudStyle = (uint8_t)(value.toInt() % 4);
                        preferences.putUChar("hudStyle", hudStyle);
                        currentMode = HUD_MODE;
                    } else if (key == "notifStyle" || key == "notif_style") {
                        notifStyle = (uint8_t)(value.toInt() % 3);
                        preferences.putUChar("notifStyle", notifStyle);
                    } else if (key == "mode") {
                        int m = value.toInt();
                        currentMode = (Mode)(m % 5);
                    }
                    screenNeedsRedraw = true;
                }
            }
        }
    }
};

// Characterstic Callback: Lệnh Điều Khiển Từ Xa (Remote Command)
class RemoteCmdCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;
        uint8_t cmd = (uint8_t)val[0];
        lastUserInteractionTime = millis();

        if (cmd == 0x10) currentMode = HUD_MODE;
        else if (cmd == 0x11) currentMode = MAP_MODE;
        else if (cmd == 0x12) {
            currentMode = STATUS_MODE;
            isNavigating = false;
            hasCustomIcon = false;
            hasActiveOledImage = false;
        }
        else if (cmd == 0x13) currentMode = INFO_MODE;
        else if (cmd == 0x14) {
            if (notifCount > 0) currentMode = NOTIF_MODE;
        }
        else if (cmd == 0x20) sendDeviceStatus();
        else if (cmd == 0x30) {
            // Chuyển kiểu HUD
            hudStyle = (hudStyle + 1) % 4;
            preferences.putUChar("hudStyle", hudStyle);
        }

        screenNeedsRedraw = true;
    }
};

// Characterstic Callback: Nhận Ảnh OLED 128x64 1bpp
class OledImageCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;

        // 1. Gói Header 2 byte kích thước (Little Endian Size: 1024 bytes -> 0x00, 0x04)
        if (val.size() == 2) {
            oledImageSize = (uint8_t)val[0] | ((uint8_t)val[1] << 8);
            if (oledImageSize > 1024 || oledImageSize == 0) oledImageSize = 1024;
            oledImageWritten = 0;
            isReceivingOledImage = true;
            return;
        }

        // 2. Gói có tiền tố phân loại 0x01 (Header) hoặc 0x02 (Data)
        uint8_t pktType = (uint8_t)val[0];
        if (pktType == 0x01 && val.size() >= 3) {
            oledImageSize = ((uint8_t)val[1] << 8) | (uint8_t)val[2];
            if (oledImageSize > 1024 || oledImageSize == 0) oledImageSize = 1024;
            oledImageWritten = 0;
            isReceivingOledImage = true;
            return;
        } else if (pktType == 0x02 && isReceivingOledImage) {
            size_t len = val.size() - 1;
            if (oledImageWritten + len <= 1024) {
                memcpy(&oledBuffer[oledImageWritten], val.data() + 1, len);
                oledImageWritten += len;
            }
            if (oledImageWritten >= oledImageSize && oledImageSize > 0) {
                isReceivingOledImage = false;
                hasActiveOledImage = true;
                lastMapFrameTime = millis();
                if (currentMode != NOTIF_MODE) {
                    currentMode = MAP_MODE;
                }
                screenNeedsRedraw = true;
            }
            return;
        }

        // 3. Gói dữ liệu thô (Raw chunks) sau Header 2 byte
        if (isReceivingOledImage) {
            size_t len = val.size();
            if (oledImageWritten + len <= 1024) {
                memcpy(&oledBuffer[oledImageWritten], val.data(), len);
                oledImageWritten += len;
            }
            if (oledImageWritten >= oledImageSize && oledImageSize > 0) {
                isReceivingOledImage = false;
                hasActiveOledImage = true;
                lastMapFrameTime = millis();
                if (currentMode != NOTIF_MODE) {
                    currentMode = MAP_MODE;
                }
                screenNeedsRedraw = true;
            }
            return;
        }

        // 4. Nhận trọn vẹn 1024 bytes trong 1 MTU duy nhất
        if (val.size() == 1024) {
            memcpy(oledBuffer, val.data(), 1024);
            hasActiveOledImage = true;
            lastMapFrameTime = millis();
            if (currentMode != NOTIF_MODE) {
                currentMode = MAP_MODE;
            }
            screenNeedsRedraw = true;
        }
    }
};

// Characterstic Callback: BLE OTA Firmware Update
class OtaCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;

        uint8_t cmd = (uint8_t)val[0];
        if (cmd == 0x01 && val.size() >= 5) {
            // START OTA
            otaExpectedSize = ((uint8_t)val[1] << 24) | ((uint8_t)val[2] << 16) | ((uint8_t)val[3] << 8) | (uint8_t)val[4];
            otaWritten = 0;
            if (Update.begin(otaExpectedSize > 0 ? otaExpectedSize : UPDATE_SIZE_UNKNOWN)) {
                isOtaMode = true;
                screenNeedsRedraw = true;
            }
        } else if (cmd == 0x02 && isOtaMode) {
            // WRITE OTA CHUNK
            size_t len = val.size() - 1;
            Update.write((uint8_t*)val.data() + 1, len);
            otaWritten += len;
            screenNeedsRedraw = true;
        } else if (cmd == 0x03 && isOtaMode) {
            // END OTA
            if (Update.end(true)) {
                ESP.restart();
            }
        }
    }
};

void setup()
{
    Serial.begin(115200);
    analogReadResolution(12);
    analogSetPinAttenuation(BAT_ADC, ADC_11db);

    // 0. Khởi tạo bộ nhớ NVS Flash và đọc cấu hình người dùng đã lưu
    preferences.begin("tymap", false);
    brightness = preferences.getInt("brightness", 80);
    hudStyle = preferences.getUChar("hudStyle", 0);
    statusStyle = preferences.getUChar("statusStyle", 0);
    notifStyle = preferences.getUChar("notifStyle", 0);

    // Khởi tạo I2C và U8g2 SH1106 ở tốc độ cao 400kHz
    Wire.begin(OLED_SDA, OLED_SCL);
    u8g2.begin();
    u8g2.setBusClock(400000);
    Wire.setClock(400000);
    uint8_t cVal = (brightness <= 100) ? (uint8_t)((brightness * 255) / 100) : (uint8_t)(brightness > 255 ? 255 : brightness);
    u8g2.setContrast(cVal);

    // 1. Khởi tạo NimBLE Server ngay từ đầu để phát Bluetooth tức thì khi cấp nguồn
    NimBLEDevice::init("TYMAP-SH1106");
    NimBLEDevice::setMTU(517);
    NimBLEServer *pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    NimBLEService *pService = pServer->createService(SERVICE_UUID);

    NimBLECharacteristic *pNavChar = pService->createCharacteristic(CHA_NAV_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pNavChar->setCallbacks(new NavCallback());

    NimBLECharacteristic *pNavIconChar = pService->createCharacteristic(CHA_NAV_TBT_ICON_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pNavIconChar->setCallbacks(new NavIconCallback());

    NimBLECharacteristic *pSettingsChar = pService->createCharacteristic(CHA_SETTINGS_UUID, NIMBLE_PROPERTY::WRITE);
    pSettingsChar->setCallbacks(new SettingsCallback());

    NimBLECharacteristic *pIconDataChar = pService->createCharacteristic(CHA_ICON_DATA_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pIconDataChar->setCallbacks(new IconDataCallback());

    NimBLECharacteristic *pSpeedChar = pService->createCharacteristic(CHA_GPS_SPEED_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pSpeedChar->setCallbacks(new SpeedCallback());

    NimBLECharacteristic *pTimeChar = pService->createCharacteristic(CHA_TIME_UUID, NIMBLE_PROPERTY::WRITE);
    pTimeChar->setCallbacks(new TimeCallback());

    NimBLECharacteristic *pWeatherChar = pService->createCharacteristic(CHA_WEATHER_UUID, NIMBLE_PROPERTY::WRITE);
    pWeatherChar->setCallbacks(new WeatherCallback());

    NimBLECharacteristic *pPhoneBatChar = pService->createCharacteristic(CHA_PHONE_BATTERY_UUID, NIMBLE_PROPERTY::WRITE);
    pPhoneBatChar->setCallbacks(new PhoneBatteryCallback());

    NimBLECharacteristic *pWarningChar = pService->createCharacteristic(CHA_WARNING_UUID, NIMBLE_PROPERTY::WRITE);
    pWarningChar->setCallbacks(new WarningCallback());

    NimBLECharacteristic *pNotifChar = pService->createCharacteristic(CHA_NOTIFICATION_UUID, NIMBLE_PROPERTY::WRITE);
    pNotifChar->setCallbacks(new NotifCallback());

    NimBLECharacteristic *pRemoteCmdChar = pService->createCharacteristic(CHA_REMOTE_CMD_UUID, NIMBLE_PROPERTY::WRITE);
    pRemoteCmdChar->setCallbacks(new RemoteCmdCallback());

    pDeviceCtrlChar = pService->createCharacteristic(CHA_DEVICE_CTRL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    pDeviceCtrlChar->setCallbacks(new RemoteCmdCallback());

    pDeviceStatusChar = pService->createCharacteristic(CHA_DEVICE_STATUS_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);

    NimBLECharacteristic *pOledImageChar = pService->createCharacteristic(CHA_OLED_IMAGE_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pOledImageChar->setCallbacks(new OledImageCallback());

    NimBLECharacteristic *pMapImageChar = pService->createCharacteristic(CHA_MAP_IMAGE_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pMapImageChar->setCallbacks(new OledImageCallback());

    NimBLECharacteristic *pOtaChar = pService->createCharacteristic(CHA_OTA_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pOtaChar->setCallbacks(new OtaCallback());

    NimBLECharacteristic *pMapTileChar = pService->createCharacteristic(CHA_MAP_TILE_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pMapTileChar->setCallbacks(new OledImageCallback());

    NimBLECharacteristic *pMapCtrlChar = pService->createCharacteristic(CHA_MAP_CTRL_UUID, NIMBLE_PROPERTY::WRITE);
    pMapCtrlChar->setCallbacks(new RemoteCmdCallback());

    NimBLECharacteristic *pMapStatusChar = pService->createCharacteristic(CHA_MAP_STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);

    pService->start();

    NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    pAdvertising->start();

    Serial.println("BLE Advertising Started: TYMAP-SH1106");

    // 2. Màn hình Splash Logo khởi động kèm Thanh Loading 3 Giây (3000ms)
    unsigned long bootStartTime = millis();
    unsigned long introDuration = 3000; // 3 giây
    while (millis() - bootStartTime < introDuration)
    {
        drawLogoSplash(millis(), bootStartTime, introDuration);
        delay(20);
    }

    // 3. Cấu hình Nút bấm
    btnMode.attachClick([]() {
        lastUserInteractionTime = millis();
        // Nếu đang hiện popup thông báo, bấm nút sẽ đóng thông báo ngay lập tức
        if (isNotifPopupTransient && currentMode == NOTIF_MODE) {
            isNotifPopupTransient = false;
            currentMode = previousModeBeforeNotif;
            screenNeedsRedraw = true;
            return;
        }

        // Chuyển chế độ thông minh theo ngữ cảnh:
        if (currentMode == STATUS_MODE) {
            currentMode = isNavigating ? HUD_MODE : INFO_MODE;
        } else if (currentMode == HUD_MODE) {
            currentMode = hasActiveOledImage ? MAP_MODE : INFO_MODE;
        } else if (currentMode == MAP_MODE) {
            currentMode = INFO_MODE;
        } else if (currentMode == INFO_MODE) {
            currentMode = isNavigating ? HUD_MODE : STATUS_MODE;
        } else {
            currentMode = STATUS_MODE;
        }
        screenNeedsRedraw = true;
    });

    // Double click nút Mode: Chuyển đổi nhanh 4 kiểu HUD (H1, H2, H3, H4) và lưu bộ nhớ
    btnMode.attachDoubleClick([]() {
        lastUserInteractionTime = millis();
        hudStyle = (hudStyle + 1) % 4;
        preferences.putUChar("hudStyle", hudStyle);
        screenNeedsRedraw = true;
    });

    btnMode.attachLongPressStart([]() {
        lastUserInteractionTime = millis();
        // Giữ nút Mode: Chuyển thẳng về STATUS_MODE hoặc HUD_MODE
        currentMode = isNavigating ? HUD_MODE : STATUS_MODE;
        screenNeedsRedraw = true;
    });

    btnZoom.attachClick([]() {
        lastUserInteractionTime = millis();
        // Đóng popup thông báo nếu đang mở
        if (isNotifPopupTransient && currentMode == NOTIF_MODE) {
            isNotifPopupTransient = false;
            currentMode = previousModeBeforeNotif;
            screenNeedsRedraw = true;
            return;
        }

        if (notifCount > 0) {
            notifViewIndex = (notifViewIndex + 1) % notifCount;
            currentMode = NOTIF_MODE;
            screenNeedsRedraw = true;
        }
    });

    // Double click nút Zoom: Chuyển đổi kiểu giao diện và lưu bộ nhớ
    btnZoom.attachDoubleClick([]() {
        lastUserInteractionTime = millis();
        if (currentMode == STATUS_MODE) {
            statusStyle = (statusStyle + 1) % 5;
            preferences.putUChar("statusStyle", statusStyle);
        } else {
            hudStyle = (hudStyle + 1) % 4;
            preferences.putUChar("hudStyle", hudStyle);
        }
        screenNeedsRedraw = true;
    });

    updateBatteryVoltage();
}

void loop()
{
    btnMode.tick();
    btnZoom.tick();

    unsigned long now = millis();

    // Khởi động lại BLE advertising an toàn khi bị ngắt kết nối
    if (advertisingPending) {
        advertisingPending = false;
        NimBLEDevice::getAdvertising()->start();
    }

    // 1. Tự động đóng popup thông báo sau 8 giây
    if (isNotifPopupTransient && currentMode == NOTIF_MODE) {
        if (now - notifPopupStartTime > 8000) {
            isNotifPopupTransient = false;
            currentMode = previousModeBeforeNotif;
            screenNeedsRedraw = true;
        }
    }

    // 2. Tự động quay về HUD khi đang dẫn đường (nếu người dùng bấm xem INFO hoặc STATUS tạm thời) sau 15s rảnh
    if (isNavigating && (currentMode == INFO_MODE || currentMode == STATUS_MODE)) {
        if (now - lastUserInteractionTime > 15000) {
            currentMode = HUD_MODE;
            screenNeedsRedraw = true;
        }
    }

    // 3. Tự động hoàn về HUD/STATUS nếu đang ở MAP_MODE mà mất stream bản đồ (quá 20s không có frame mới)
    if (currentMode == MAP_MODE && hasActiveOledImage) {
        if (now - lastMapFrameTime > 20000) {
            hasActiveOledImage = false;
            currentMode = isNavigating ? HUD_MODE : STATUS_MODE;
            screenNeedsRedraw = true;
        }
    }

    // 4. Timeout tự động kết thúc trạng thái dẫn đường nếu không có dữ liệu dẫn đường mới trong 60 giây
    if (isNavigating && (now - lastNavDataTime > 60000)) {
        isNavigating = false;
        hasCustomIcon = false;
        if (currentMode == HUD_MODE || currentMode == MAP_MODE) {
            currentMode = STATUS_MODE;
            screenNeedsRedraw = true;
        }
    }

    // Cập nhật pin & đẩy mẫu sóng Oscilloscope liên tục (bên trong hàm đã có timer 30ms)
    updateBatteryVoltage();

    // Định kỳ gửi trạng thái BLE (mỗi 5 giây)
    if (bleConnected && now - lastStatusSent > 5000) {
        sendDeviceStatus();
    }

    // Redraw giao diện mượt mà (35ms ~ 28 FPS trong HUD_MODE và STATUS_MODE cho sóng và chuyển động cực mượt)
    unsigned long frameInterval = (currentMode == HUD_MODE || currentMode == STATUS_MODE) ? 35 : 60;
    if (screenNeedsRedraw || (now - lastRedrawTime >= frameInterval)) {
        lastRedrawTime = now;
        screenNeedsRedraw = false;

        if (isOtaMode) {
            drawOtaProgressScreen();
        } else if (currentMode == HUD_MODE) {
            drawHUD();
        } else if (currentMode == MAP_MODE) {
            drawMAP();
        } else if (currentMode == NOTIF_MODE) {
            drawNOTIF();
        } else if (currentMode == INFO_MODE) {
            drawINFO();
        } else {
            drawSTATUS();
        }
    }

    delay(5);
}
