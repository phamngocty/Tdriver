#include <Arduino.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <NimBLEDevice.h>
#include <ESP32Time.h>
#include <OneButton.h>
#include <ArduinoJson.h>
#include <FontMaker.h>
#include <Update.h>
#include "gui.h"

#ifndef OLED_SDA
#define OLED_SDA 6
#endif

#ifndef OLED_SCL
#define OLED_SCL 7
#endif

#define MODE_BTN 2
#define ZOOM_BTN 3
#define BAT_ADC 0

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
const char *CHA_OLED_IMAGE_UUID = "e1f2a3b4-c5d6-4789-a012-3456789abcdef";
const char *CHA_DEVICE_CTRL_UUID = "d4e5f6a7-b8c9-4012-d345-678901bcdef0";
const char *CHA_REMOTE_CMD_UUID = "f1a2b3c4-d5e6-4789-a012-3456789abcde";
const char *CHA_DEVICE_STATUS_UUID = "a1b2c3d4-e5f6-4789-b012-3456789abcde";
const char *CHA_NOTIFICATION_UUID = "c1d2e3f4-a5b6-4789-c012-3456789abcde";
const char *CHA_PHONE_BATTERY_UUID = "e5f6a7b8-c9d0-4123-e456-789012cdef01";
const char *CHA_WARNING_UUID = "e4f5a6b7-c8d9-4012-e345-678901bcdef0";
const char *CHA_OTA_UUID = "f0a1b2c3-d4e5-4f60-a012-bcdef0123456";

// Khởi tạo phần cứng U8g2 SH1106 I2C 128x64
U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, /* reset=*/ U8X8_PIN_NONE);

// Callback vẽ pixel cho FontMaker
void drawPixelOnOled(int16_t x, int16_t y, uint16_t color)
{
    if (x >= 0 && x < 128 && y >= 0 && y < 64)
    {
        u8g2.setDrawColor(color != 0 ? 1 : 0);
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
             "mode=%s\nvoltage=%.1f\nrssi=%d\ndisplay=SH1106\ntimeSynced=%d\nnotifCount=%d\nver=%s\nfw_code=%d",
             modeStr.c_str(), batteryVoltage, rssi,
             timeSynced ? 1 : 0, notifCount, FW_VERSION_STR, FW_VERSION_CODE);

    pDeviceStatusChar->setValue((uint8_t*)buffer, strlen(buffer));
    pDeviceStatusChar->notify();
    lastStatusSent = millis();
}

// Đo điện áp pin xe
void updateBatteryVoltage()
{
    int adcVal = analogRead(BAT_ADC);
    // Cầu phân áp R1=100k, R2=27k, Attenuation 11dB (0 - 3.1V)
    float vAdc = (adcVal / 4095.0f) * 3.1f;
    batteryVoltage = vAdc * (127.0f / 27.0f);
}

// Server Callbacks BLE
class MyServerCallbacks : public NimBLEServerCallbacks
{
    void onConnect(NimBLEServer *pServer) override
    {
        bleConnected = true;
        screenNeedsRedraw = true;
        sendDeviceStatus();
    }
    void onDisconnect(NimBLEServer *pServer) override
    {
        bleConnected = false;
        if (currentMode == HUD_MODE || currentMode == MAP_MODE) {
            currentMode = STATUS_MODE;
        }
        hasActiveOledImage = false;
        screenNeedsRedraw = true;
    }
};

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
                else if (k == "dist") distToNext = v;
                else if (k == "title" || k == "street") nextStreet = v;
                else if (k == "eta") eta = v;
                else if (k == "ete") ete = v;
                else if (k == "dir") navDirIdx = v.toInt();
                else if (k == "total") totalDist = v;
            }
        }

        if (navActive == 0) {
            // App tắt dẫn đường -> tự động về STATUS
            currentMode = STATUS_MODE;
            hasCustomIcon = false;
        } else if (navActive == 1) {
            if (currentMode != MAP_MODE) {
                currentMode = HUD_MODE;
            }
        }
        screenNeedsRedraw = true;
    }
};

// Characterstic Callback: Icon TBT Bitmap
class IconDataCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.size() >= 288) {
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
        if (!val.empty()) {
            gpsSpeed = atoi(val.c_str());
            screenNeedsRedraw = true;
        }
    }
};

// Characterstic Callback: Thời gian
class TimeCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.length() >= 10) {
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
                if (doc["icon"].is<const char*>()) weatherIcon = doc["icon"].as<String>();
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
        if (!val.empty()) {
            int sep = val.find(',');
            if (sep != std::string::npos) {
                phoneBatteryLevel = atoi(val.substr(0, sep).c_str());
                phoneBatteryCharging = (atoi(val.substr(sep + 1).c_str()) == 1);
            } else {
                phoneBatteryLevel = atoi(val.c_str());
            }
            screenNeedsRedraw = true;
        }
    }
};

// Characterstic Callback: Cảnh báo giao thông (Tốc độ & Camera)
class WarningCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (!val.empty()) {
            int sep = val.find(',');
            if (sep != std::string::npos) {
                trafficWarningType = atoi(val.substr(0, sep).c_str());
                trafficWarningValue = atoi(val.substr(sep + 1).c_str());
            } else {
                trafficWarningType = atoi(val.c_str());
                trafficWarningValue = 60;
            }
            isTrafficWarningActive = true;
            trafficWarningStartTime = millis();
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
            for (int i = 2; i > 0; i--) {
                notifList[i] = notifList[i - 1];
            }
            notifList[0].app = doc["app"].as<String>();
            notifList[0].title = doc["title"].as<String>();
            notifList[0].msg = doc["msg"].as<String>();
            notifList[0].time = millis();

            if (notifCount < 3) notifCount++;
            notifViewIndex = 0;

            previousModeBeforeNotif = currentMode;
            currentMode = NOTIF_MODE;
            isNotifPopupTransient = true;
            notifPopupStartTime = millis();
            screenNeedsRedraw = true;
        }
    }
};

// Characterstic Callback: Cài Đặt (Settings)
class SettingsCallback : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.empty()) return;
        JsonDocument doc;
        if (deserializeJson(doc, val.c_str()) == DeserializationError::Ok) {
            if (doc["hud_style"].is<int>()) hudStyle = doc["hud_style"].as<int>() % 4;
            if (doc["brightness"].is<int>()) {
                brightness = doc["brightness"].as<int>();
                u8g2.setContrast(brightness);
            }
            screenNeedsRedraw = true;
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

        if (cmd == 0x10) currentMode = HUD_MODE;
        else if (cmd == 0x11) currentMode = MAP_MODE;
        else if (cmd == 0x12) {
            currentMode = STATUS_MODE;
            hasCustomIcon = false;
            hasActiveOledImage = false;
        }
        else if (cmd == 0x13) currentMode = INFO_MODE;
        else if (cmd == 0x20) sendDeviceStatus();
        else if (cmd == 0x30) {
            // Chuyển kiểu HUD
            hudStyle = (hudStyle + 1) % 4;
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
        if (val.size() < 4) return;

        uint8_t pktType = (uint8_t)val[0];
        if (pktType == 0x01) {
            // Header: Bắt đầu truyền ảnh
            oledImageSize = ((uint8_t)val[1] << 8) | (uint8_t)val[2];
            oledImageWritten = 0;
            isReceivingOledImage = true;
        } else if (pktType == 0x02 && isReceivingOledImage) {
            // Payload data
            size_t len = val.size() - 1;
            if (oledImageWritten + len <= 1024) {
                memcpy(&oledBuffer[oledImageWritten], val.data() + 1, len);
                oledImageWritten += len;
            }
            if (oledImageWritten >= oledImageSize && oledImageSize > 0) {
                isReceivingOledImage = false;
                hasActiveOledImage = true;
                currentMode = MAP_MODE;
                screenNeedsRedraw = true;
            }
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

    // Khởi tạo I2C và U8g2 SH1106
    Wire.begin(OLED_SDA, OLED_SCL);
    u8g2.begin();
    u8g2.setContrast(200);

    // Màn hình Splash Logo khởi động
    drawLogoSplash(millis(), millis(), 1500);
    delay(1200);

    // Cấu hình Nút bấm
    btnMode.attachClick([]() {
        if (currentMode == STATUS_MODE) currentMode = HUD_MODE;
        else if (currentMode == HUD_MODE) currentMode = MAP_MODE;
        else if (currentMode == MAP_MODE) currentMode = INFO_MODE;
        else currentMode = STATUS_MODE;
        screenNeedsRedraw = true;
    });

    // Double click nút Mode: Chuyển đổi nhanh 4 kiểu HUD (H1, H2, H3, H4)
    btnMode.attachDoubleClick([]() {
        hudStyle = (hudStyle + 1) % 4;
        screenNeedsRedraw = true;
    });

    btnMode.attachLongPressStart([]() {
        // Đảo ngược màu hoặc Reset về STATUS
        currentMode = STATUS_MODE;
        screenNeedsRedraw = true;
    });

    btnZoom.attachClick([]() {
        if (notifCount > 0) {
            notifViewIndex = (notifViewIndex + 1) % notifCount;
            currentMode = NOTIF_MODE;
            screenNeedsRedraw = true;
        }
    });

    // Double click nút Zoom: Chuyển đổi kiểu HUD
    btnZoom.attachDoubleClick([]() {
        hudStyle = (hudStyle + 1) % 4;
        screenNeedsRedraw = true;
    });

    // Khởi tạo NimBLE Server
    NimBLEDevice::init("TYMAP-SH1106");
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);
    NimBLEServer *pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    NimBLEService *pService = pServer->createService(SERVICE_UUID);

    NimBLECharacteristic *pNavChar = pService->createCharacteristic(CHA_NAV_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pNavChar->setCallbacks(new NavCallback());

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

    NimBLECharacteristic *pOledImageChar = pService->createCharacteristic(CHA_OLED_IMAGE_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pOledImageChar->setCallbacks(new OledImageCallback());

    NimBLECharacteristic *pOtaChar = pService->createCharacteristic(CHA_OTA_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pOtaChar->setCallbacks(new OtaCallback());

    pDeviceStatusChar = pService->createCharacteristic(CHA_DEVICE_STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);

    pService->start();

    NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->start();

    updateBatteryVoltage();
}

void loop()
{
    btnMode.tick();
    btnZoom.tick();

    // Tự động đóng popup thông báo sau 8 giây
    if (isNotifPopupTransient && currentMode == NOTIF_MODE) {
        if (millis() - notifPopupStartTime > 8000) {
            isNotifPopupTransient = false;
            currentMode = previousModeBeforeNotif;
            screenNeedsRedraw = true;
        }
    }

    // Định kỳ đo điện áp pin (mỗi 3 giây)
    static unsigned long lastBatCheck = 0;
    if (millis() - lastBatCheck > 3000) {
        updateBatteryVoltage();
        lastBatCheck = millis();
    }

    // Định kỳ gửi trạng thái BLE (mỗi 5 giây)
    if (bleConnected && millis() - lastStatusSent > 5000) {
        sendDeviceStatus();
    }

    // Redraw giao diện
    unsigned long now = millis();
    if (screenNeedsRedraw || (now - lastRedrawTime >= 200)) {
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
