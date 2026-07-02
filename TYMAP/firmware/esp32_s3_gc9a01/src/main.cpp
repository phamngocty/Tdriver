#include <Arduino.h>
#include <FS.h>
#include <SPIFFS.h>
#include <Preferences.h>
#include <NimBLEDevice.h>
#include <TFT_eSPI.h>
#include <JPEGDEC.h>
#include <ESP32Time.h>
#include <OneButton.h>
#include <ArduinoJson.h>
#include <FontMaker.h>
#include "gui.h"

#define MODE_BTN 0
#define ZOOM_BTN 1
#define BAT_ADC 3

// GATT Server UUIDs
const char *SERVICE_UUID = "0000feed-0000-1000-8000-00805f9b34fb";
const char *CHA_NAV_UUID = "0b11deef-1563-447f-aece-d3dfeb1c1f20";
const char *CHA_NAV_TBT_ICON_UUID = "d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad";
const char *CHA_ICON_DATA_UUID = "e2f3a4b5-c6d7-4890-e123-456789abcdef";
const char *CHA_GPS_SPEED_UUID = "98b6073a-5cf3-4e73-b6d3-f8e05fa018a9";
const char *CHA_SETTINGS_UUID = "9d37a346-63d3-4df6-8eee-f0242949f59f";
const char *CHA_TIME_UUID = "a1b2c3d4-e5f6-4789-a012-3456789abcde";
const char *CHA_WEATHER_UUID = "b2c3d4e5-f6a7-4890-b123-456789abcdef";
const char *CHA_MAP_IMAGE_UUID = "c3d4e5f6-a7b8-4901-c234-567890abcdef";
const char *CHA_DEVICE_CTRL_UUID = "d4e5f6a7-b8c9-4012-d345-678901bcdef0";
const char *CHA_REMOTE_CMD_UUID = "f1a2b3c4-d5e6-4789-a012-3456789abcde";
const char *CHA_DEVICE_STATUS_UUID = "a1b2c3d4-e5f6-4789-b012-3456789abcde";
const char *CHA_NOTIFICATION_UUID = "c1d2e3f4-a5b6-4789-c012-3456789abcde";
const char *CHA_PHONE_BATTERY_UUID = "e5f6a7b8-c9d0-4123-e456-789012cdef01"; // NEW: Pin điện thoại

Mode currentMode = STATUS_MODE;

// Nhận JPEG qua BLE
uint8_t *jpegBuffer = nullptr;
uint32_t jpegSize = 0;
uint32_t jpegWritten = 0;
bool isReceivingJpeg = false;
volatile bool newMapImageAvailable = false;

// Trạng thái Popup Bản đồ trong HUD
bool isPopupActive = false;
unsigned long popupStartTime = 0;

Mode selectedMode = STATUS_MODE;
Mode previousModeBeforeNotif = STATUS_MODE;

Preferences preferences;
int hudTimeout = 3; // mặc định 3s

// Notification Storage
NotificationItem notifList[3];
int notifCount = 0;
int notifViewIndex = 0;
bool isNotifPopupTransient = false;
unsigned long notifPopupStartTime = 0;

TFT_eSPI tft = TFT_eSPI();
TFT_eSprite canvasSprite = TFT_eSprite(&tft);
JPEGDEC jpeg;
ESP32Time rtc;
OneButton btnMode(MODE_BTN, true);
OneButton btnZoom(ZOOM_BTN, true);

volatile bool screenNeedsRedraw = true;
volatile bool statusUpdatePending = false;
volatile bool needClearScreen = false;
int lastRenderedSecond = -1;

int16_t clipMinX = 0;
int16_t clipMaxX = 240;
int16_t clipMinY = 0;
int16_t clipMaxY = 240;

void drawPixelOnCanvas(int16_t x, int16_t y, uint16_t color)
{
    if (x >= clipMinX && x < clipMaxX && y >= clipMinY && y < clipMaxY)
    {
        canvasSprite.drawPixel(x, y, color);
    }
}
MakeFont myFont(drawPixelOnCanvas);

volatile bool bleConnected = false;
volatile bool advertisingPending = false;

class MyServerCallbacks : public NimBLEServerCallbacks
{
    void onConnect(NimBLEServer *pServer) override
    {
        bleConnected = true;
        needClearScreen = true;
        screenNeedsRedraw = true;
        Serial.println("BLE: Client Connected!");
    }
    void onDisconnect(NimBLEServer *pServer) override
    {
        bleConnected = false;
        Serial.println("BLE: Client Disconnected!");
        if (currentMode == HUD_MODE || currentMode == MAP_MODE)
        {
            currentMode = STATUS_MODE;
        }
        isPopupActive = false;
        isReceivingJpeg = false;
        jpegSize = 0;
        jpegWritten = 0;
        needClearScreen = true;
        screenNeedsRedraw = true;
        advertisingPending = true;
    }
};

// BLE Characteristics
NimBLECharacteristic *pNavChar = nullptr;
NimBLECharacteristic *pNavIconChar = nullptr;
NimBLECharacteristic *pIconDataChar = nullptr;
NimBLECharacteristic *pSpeedChar = nullptr;
NimBLECharacteristic *pSettingsChar = nullptr;
NimBLECharacteristic *pTimeChar = nullptr;
NimBLECharacteristic *pWeatherChar = nullptr;
NimBLECharacteristic *pMapImageChar = nullptr;
NimBLECharacteristic *pDeviceCtrlChar = nullptr;
NimBLECharacteristic *pRemoteCmdChar = nullptr;
NimBLECharacteristic *pDeviceStatusChar = nullptr;
NimBLECharacteristic *pNotificationChar = nullptr;
NimBLECharacteristic *pPhoneBatteryChar = nullptr; // NEW: Pin điện thoại

// HUD Dẫn đường
String nextStreet = "";
String distToNext = "";
String totalDist = "";
String eta = "";
String ete = "";
int gpsSpeed = 0;
uint32_t lastNavUpdate = 0;

// Cài đặt
bool popupEnabled = true;
int popupDuration = 5; // giây
int brightness = 80;   // 0-100

// Thời tiết & Trạng thái xe
float weatherTemp = 0.0;
String weatherIcon = "";
float batteryVoltage = 0.0;
unsigned long lastStatusSent = 0;

// Pin điện thoại và trạng thái đồng bộ
int phoneBatteryLevel = -1; // -1 = chưa biết, 0-100 = mức pin
bool phoneBatteryCharging = false;
bool timeSynced = false; // Đã đồng bộ thời gian từ app chưa

// Icon Cache (FIFO)
struct CachedIcon
{
    uint32_t hash;
    uint8_t bitmap[288];
};
CachedIcon iconCache[50];
int cacheSize = 0;
uint32_t currentIconHash = 0;
bool hasCustomIcon = false;
uint8_t customIconBitmap[288];



// Trạng thái Menu Chồng
bool isMenuOpen = false;
unsigned long menuStartTime = 0;
int menuSelectedIndex = 0; // 0: HUD, 1: MAP, 2: STATUS

// JPEG draw callback
int drawJPEG(JPEGDRAW *pDraw)
{
    canvasSprite.pushImage(pDraw->x, pDraw->y, pDraw->iWidth, pDraw->iHeight, pDraw->pPixels);
    return 1;
}

// Tìm icon trong cache
bool getCachedIcon(uint32_t hash, uint8_t *outBitmap)
{
    for (int i = 0; i < cacheSize; i++)
    {
        if (iconCache[i].hash == hash)
        {
            memcpy(outBitmap, iconCache[i].bitmap, 288);
            return true;
        }
    }
    return false;
}

// Thêm icon vào cache (FIFO)
void addIconToCache(uint32_t hash, const uint8_t *bitmap)
{
    // Nếu trùng hash thì không thêm
    for (int i = 0; i < cacheSize; i++)
    {
        if (iconCache[i].hash == hash)
            return;
    }

    if (cacheSize < 50)
    {
        iconCache[cacheSize].hash = hash;
        memcpy(iconCache[cacheSize].bitmap, bitmap, 288);
        cacheSize++;
    }
    else
    {
        // Đẩy phần tử đầu tiên ra (FIFO)
        for (int i = 0; i < 49; i++)
        {
            iconCache[i] = iconCache[i + 1];
        }
        iconCache[49].hash = hash;
        memcpy(iconCache[49].bitmap, bitmap, 288);
    }
}

// Gửi trạng thái qua BLE
void sendDeviceStatus()
{
    if (!pDeviceStatusChar)
        return;
    char buffer[192];
    String modeStr = "STATUS";
    if (currentMode == HUD_MODE)
        modeStr = "HUD";
    else if (currentMode == MAP_MODE)
        modeStr = "MAP";
    else if (currentMode == INFO_MODE)
        modeStr = "INFO";
    else if (currentMode == NOTIF_MODE)
        modeStr = "NOTIF";

    int rssi = -55 - (random() % 15);

    // Gửi thêm thông tin pin xe đạp và trạng thái thời gian
    snprintf(buffer, sizeof(buffer),
             "mode=%s\nvoltage=%.1f\nrssi=%d\ndisplay=GC9A01\ntimeSynced=%d\nnotifCount=%d",
             modeStr.c_str(), batteryVoltage, rssi,
             timeSynced ? 1 : 0, notifCount);
    pDeviceStatusChar->setValue((uint8_t*)buffer, strlen(buffer));
    pDeviceStatusChar->notify();
    Serial.printf("BLE: Sent status update notification, subscribers=%d\n", pDeviceStatusChar->getSubscribedCount());
    lastStatusSent = millis();
}

// Đo điện áp pin
void updateBatteryVoltage()
{
    int adcVal = analogRead(BAT_ADC);
    // Cầu phân áp R1=100k, R2=27k. Attenuation 11dB (0 - 3.1V)
    float vAdc = (adcVal / 4095.0) * 3.1;
    batteryVoltage = vAdc * (127.0 / 27.0);
}

// Thiết lập độ sáng màn hình
void setDisplayBrightness(int pct)
{
    brightness = pct;
    int duty = (pct * 255) / 100;
    ledcWrite(0, duty);
}

// Giải mã và vẽ ảnh JPEG
void renderJpegImage(const uint8_t *data, uint32_t size)
{
    if (jpeg.openRAM((uint8_t *)data, size, drawJPEG))
    {
        canvasSprite.setSwapBytes(true);
        jpeg.decode(0, 0, 0);
        canvasSprite.setSwapBytes(false);
        jpeg.close();

        // Vẽ thêm thông tin đè lên bản đồ nếu ở chế độ MAP hoặc Popup HUD
        if (currentMode == MAP_MODE || isPopupActive)
        {
            drawMapOverlay();
        }

        canvasSprite.pushSprite(0, 0);
    }
}

// Nhận dữ liệu BLE
class ServerCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pCharacteristic)
    {
        String uuid = pCharacteristic->getUUID().toString().c_str();
        std::string val = pCharacteristic->getValue();

        if (uuid == CHA_NAV_UUID)
        {
            String data = val.c_str();
            Serial.printf("BLE: Received Nav Text, len=%d:\n%s\n", val.length(), data.c_str());
            int startIdx = 0;
            bool isActive = true; // mặc định là true để tương thích ngược với các phiên bản app cũ
            while (startIdx < data.length())
            {
                int endIdx = data.indexOf('\n', startIdx);
                if (endIdx == -1)
                    endIdx = data.length();
                String line = data.substring(startIdx, endIdx);
                startIdx = endIdx + 1;

                int eqIdx = line.indexOf('=');
                if (eqIdx != -1)
                {
                    String key = line.substring(0, eqIdx);
                    String value = line.substring(eqIdx + 1);
                    if (key == "active" || key == "nav")
                        isActive = (value.toInt() == 1);
                    else if (key == "dist")
                        distToNext = value;
                    else if (key == "inst" || key == "title")
                        nextStreet = value;
                    else if (key == "road" || key == "dir")
                        totalDist = value; // tên đường rẽ
                    else if (key == "eta")
                        eta = value;
                    else if (key == "ete")
                        ete = value;
                }
            }
            
            if (isActive)
            {
                lastNavUpdate = millis();
            }
            else
            {
                // Nếu dừng dẫn đường, chuyển về STATUS_MODE từ cả HUD và MAP mode
                if (currentMode == HUD_MODE || currentMode == MAP_MODE)
                {
                    currentMode = STATUS_MODE;
                    isPopupActive = false;
                    isReceivingJpeg = false;
                    jpegSize = 0;
                    jpegWritten = 0;
                    statusUpdatePending = true; // Cập nhật trạng thái an toàn qua luồng loop
                }
            }
            screenNeedsRedraw = true;
        }
        else if (uuid == CHA_NAV_TBT_ICON_UUID)
        {
            Serial.printf("BLE: Received CHA_NAV_TBT_ICON, len=%d\n", val.length());
            String strVal = val.c_str();
            if (strVal.startsWith("hash="))
            {
                String hashStr = strVal.substring(5);
                uint32_t hash = strtoul(hashStr.c_str(), nullptr, 16);
                Serial.printf("BLE: Received Icon Hash = %08X\n", hash);
                currentIconHash = hash;

                uint8_t bitmap[288];
                if (getCachedIcon(hash, bitmap))
                {
                    Serial.println("BLE: Icon found in Cache!");
                    memcpy(customIconBitmap, bitmap, 288);
                    hasCustomIcon = true;
                    screenNeedsRedraw = true;
                }
                else
                {
                    Serial.println("BLE: Icon NOT in cache. Requesting from App...");
                    hasCustomIcon = false;
                    char reqBuf[64];
                    snprintf(reqBuf, sizeof(reqBuf), "icon_req=%s", hashStr.c_str());
                    pDeviceStatusChar->setValue((uint8_t*)reqBuf, strlen(reqBuf));
                    pDeviceStatusChar->notify();
                    Serial.printf("BLE: Sent icon_req notification, subscribers=%d\n", pDeviceStatusChar->getSubscribedCount());
                }
            }
            else if (val.length() == 288)
            {
                Serial.println("BLE: Received Raw 288-byte Bitmap directly");
                memcpy(customIconBitmap, val.data(), 288);
                hasCustomIcon = true;
                screenNeedsRedraw = true;
            }
        }
        else if (uuid == CHA_ICON_DATA_UUID)
        {
            Serial.printf("BLE: Received CHA_ICON_DATA, len=%d\n", val.length());
            if (val.length() == 292)
            {
                uint32_t hash;
                memcpy(&hash, val.data(), 4);
                const uint8_t *bitmap = (const uint8_t *)(val.data() + 4);

                Serial.printf("BLE: Received bitmap data for Hash = %08X (current required=%08X)\n", hash, currentIconHash);
                addIconToCache(hash, bitmap);

                if (hash == currentIconHash || !hasCustomIcon)
                {
                    Serial.println("BLE: Hash matches current required. Copying to screen.");
                    memcpy(customIconBitmap, bitmap, 288);
                    hasCustomIcon = true;
                    screenNeedsRedraw = true;
                }

                char ackBuf[64];
                snprintf(ackBuf, sizeof(ackBuf), "icon_ack=%08X", hash);
                pDeviceStatusChar->setValue((uint8_t*)ackBuf, strlen(ackBuf));
                pDeviceStatusChar->notify();
            }
        }
        else if (uuid == CHA_GPS_SPEED_UUID)
        {
            int newSpeed = atoi(val.c_str());
            if (newSpeed != gpsSpeed)
            {
                gpsSpeed = newSpeed;
                screenNeedsRedraw = true;
            }
        }
        else if (uuid == CHA_SETTINGS_UUID)
        {
            String s = val.c_str();
            int startIdx = 0;
            while (startIdx < s.length())
            {
                int endIdx = s.indexOf('\n', startIdx);
                if (endIdx == -1)
                    endIdx = s.length();
                String line = s.substring(startIdx, endIdx);
                startIdx = endIdx + 1;

                int eqIdx = line.indexOf('=');
                if (eqIdx != -1)
                {
                    String key = line.substring(0, eqIdx);
                    String value = line.substring(eqIdx + 1);
                    if (key == "brightness")
                    {
                        setDisplayBrightness(value.toInt());
                        preferences.putInt("brightness", brightness);
                    }
                    else if (key == "popupEnabled")
                    {
                        popupEnabled = value.toInt() == 1;
                    }
                    else if (key == "popupDuration")
                    {
                        popupDuration = value.toInt();
                        preferences.putInt("popupDuration", popupDuration);
                    }
                    else if (key == "hudTimeout")
                    {
                        hudTimeout = value.toInt();
                        preferences.putInt("hudTimeout", hudTimeout);
                    }
                }
            }
        }
        else if (uuid == CHA_TIME_UUID)
        {
            if (val.length() == 4)
            {
                uint32_t ts;
                memcpy(&ts, val.data(), 4);
                rtc.setTime(ts);
                timeSynced = true; // Đánh dấu đã đồng bộ thời gian từ app
                screenNeedsRedraw = true;
            }
        }
        else if (uuid == CHA_WEATHER_UUID)
        {
            JsonDocument doc;
            DeserializationError error = deserializeJson(doc, val.c_str());
            if (!error)
            {
                weatherTemp = doc["t"] | 0.0f;
                weatherIcon = doc["i"] | "";
                screenNeedsRedraw = true;
            }
        }
        else if (uuid == CHA_PHONE_BATTERY_UUID)
        {
            // Định dạng: JSON {"level":85,"charging":true}
            // Hoặc Text đơn giản: "85" hoặc "85,1"
            JsonDocument doc;
            DeserializationError error = deserializeJson(doc, val.c_str());
            if (!error)
            {
                phoneBatteryLevel = doc["level"] | -1;
                phoneBatteryCharging = doc["charging"] | false;
            }
            else if (val.length() <= 4)
            {
                // Fallback: dạng plain text "85"
                phoneBatteryLevel = String(val.c_str()).toInt();
                phoneBatteryCharging = false;
            }
            screenNeedsRedraw = true;
        }
        else if (uuid == CHA_MAP_IMAGE_UUID)
        {
            if (!isReceivingJpeg)
            {
                if (val.length() == 4)
                {
                    memcpy(&jpegSize, val.data(), 4);
                    jpegWritten = 0;
                    isReceivingJpeg = true;
                }
            }
            else
            {
                memcpy(jpegBuffer + jpegWritten, val.data(), val.length());
                jpegWritten += val.length();
                if (jpegWritten >= jpegSize)
                {
                    isReceivingJpeg = false;
                    newMapImageAvailable = true;
                    screenNeedsRedraw = true; // Request redraw immediately on the Main Thread

                    if (currentMode == HUD_MODE && popupEnabled)
                    {
                        isPopupActive = true;
                        popupStartTime = millis();
                    }
                }
            }
        }
        else if (uuid == CHA_DEVICE_CTRL_UUID)
        {
            if (val.length() == 1)
            {
                uint8_t ctrl = val[0];
                if (ctrl & 8)
                { // bit3=1 -> Exit Popup
                    if (isPopupActive)
                    {
                        isPopupActive = false;
                        screenNeedsRedraw = true;
                    }
                }
            }
        }
        else if (uuid == CHA_REMOTE_CMD_UUID)
        {
            if (val.length() == 1)
            {
                uint8_t cmd = val[0];
                if (cmd == 0x10)
                {
                    currentMode = HUD_MODE;
                    isPopupActive = false;
                    isReceivingJpeg = false;
                    needClearScreen = true;
                    screenNeedsRedraw = true;
                    statusUpdatePending = true;
                }
                else if (cmd == 0x11)
                {
                    currentMode = MAP_MODE;
                    isPopupActive = false;
                    isReceivingJpeg = false;
                    needClearScreen = true;
                    screenNeedsRedraw = true;
                    statusUpdatePending = true;
                }
                else if (cmd == 0x12)
                {
                    currentMode = STATUS_MODE;
                    isPopupActive = false;
                    isReceivingJpeg = false;
                    needClearScreen = true;
                    screenNeedsRedraw = true;
                    statusUpdatePending = true;
                }
                else if (cmd == 0x13)
                {
                    currentMode = INFO_MODE;
                    isPopupActive = false;
                    isReceivingJpeg = false;
                    needClearScreen = true;
                    screenNeedsRedraw = true;
                    statusUpdatePending = true;
                }
                else if (cmd == 0x14)
                {
                    if (notifCount > 0)
                    {
                        previousModeBeforeNotif = currentMode;
                        currentMode = NOTIF_MODE;
                        notifViewIndex = 0;
                        isNotifPopupTransient = false; // Persistent mode từ menu
                        isPopupActive = false;
                        isReceivingJpeg = false;
                        needClearScreen = true;
                        screenNeedsRedraw = true;
                        statusUpdatePending = true;
                    }
                }
                else if (cmd == 0x20)
                {
                    statusUpdatePending = true;
                }
                else if (cmd == 0x30)
                {
                    pDeviceStatusChar->setValue((uint8_t*)"ping=ok", 7);
                    pDeviceStatusChar->notify();
                }
                else if (cmd == 0xFF)
                {
                    ESP.restart();
                }
            }
        }
        else if (uuid == CHA_NOTIFICATION_UUID)
        {
            JsonDocument doc;
            DeserializationError error = deserializeJson(doc, val.c_str());
            if (!error)
            {
                // 1. Lưu chế độ hiện tại trước khi chuyển sang NOTIF popup
                previousModeBeforeNotif = currentMode;

                // Ép kiểu sang String và giới hạn độ dài tin nhắn tối đa 120 ký tự để tránh tràn heap
                String appStr = doc["app"] | "";
                String titleStr = doc["title"] | "";
                String msgStr = doc["message"] | "";
                if (msgStr.length() > 120)
                {
                    msgStr = msgStr.substring(0, 120) + "...";
                }

                // 2. FIFO: thêm vào danh sách (tối đa 3)
                if (notifCount < 3)
                {
                    notifList[notifCount].app = appStr;
                    notifList[notifCount].title = titleStr;
                    notifList[notifCount].msg = msgStr;
                    notifList[notifCount].time = millis();
                    notifCount++;
                }
                else
                {
                    notifList[0] = notifList[1];
                    notifList[1] = notifList[2];
                    notifList[2].app = appStr;
                    notifList[2].title = titleStr;
                    notifList[2].msg = msgStr;
                    notifList[2].time = millis();
                }

                // 3. Hiển thị popup thông báo trong 5s, sau đó về STATUS
                currentMode = NOTIF_MODE;
                notifViewIndex = notifCount - 1;
                isNotifPopupTransient = true;
                notifPopupStartTime = millis();

                screenNeedsRedraw = true;
                statusUpdatePending = true;
            }
        }
    }
};

void setup()
{
    Serial.begin(115200);
    psramInit();

    // Cấp phát buffer JPEG trong PSRAM
    jpegBuffer = (uint8_t *)ps_malloc(32 * 1024);
    if (!jpegBuffer)
    {
        jpegBuffer = (uint8_t *)malloc(32 * 1024); // Fallback to SRAM
    }

    // Khởi tạo màn hình
    tft.init();
    tft.setRotation(0);
    tft.fillScreen(TFT_BLACK);

    // Cấu hình LED Backlight PWM
    ledcSetup(0, 5000, 8); // Kênh 0, tần số 5kHz, độ phân giải 8-bit
    ledcAttachPin(TFT_BL, 0);
    setDisplayBrightness(brightness);

    // Khởi tạo Sprite Canvas đệm
    canvasSprite.setColorDepth(16);
    canvasSprite.createSprite(240, 240);

    // Khởi tạo Font chữ Tiếng Việt mặc định
    myFont.set_font(FONT_STATUS_INFO);

    // Chạy màn hình khởi động (Logo + Loading Bar) kéo dài 2.5 giây
    unsigned long bootStartTime = millis();
    unsigned long introDuration = 2500; // 2.5 giây
    while (millis() - bootStartTime < introDuration)
    {
        drawLogoWithLoadingBar(millis(), bootStartTime, introDuration);
        delay(10);
    }

    // Khởi tạo RTC thời gian mặc định
    rtc.setTime(1719403200); // 2026-06-26 12:00:00

    // Tải cài đặt từ bộ nhớ NVS
    preferences.begin("tymap", false);
    hudTimeout = preferences.getInt("hudTimeout", 3);
    popupDuration = preferences.getInt("popupDuration", 5);
    brightness = preferences.getInt("brightness", 80);
    setDisplayBrightness(brightness);

    NimBLEDevice::init("TYMAP-S3");
    NimBLEServer *pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    NimBLEService *pService = pServer->createService(SERVICE_UUID);

    ServerCallbacks *sCallbacks = new ServerCallbacks();

    pNavChar = pService->createCharacteristic(CHA_NAV_UUID, NIMBLE_PROPERTY::WRITE);
    pNavChar->setCallbacks(sCallbacks);

    pNavIconChar = pService->createCharacteristic(CHA_NAV_TBT_ICON_UUID, NIMBLE_PROPERTY::WRITE);
    pNavIconChar->setCallbacks(sCallbacks);

    pIconDataChar = pService->createCharacteristic(CHA_ICON_DATA_UUID, NIMBLE_PROPERTY::WRITE);
    pIconDataChar->setCallbacks(sCallbacks);

    pSpeedChar = pService->createCharacteristic(CHA_GPS_SPEED_UUID, NIMBLE_PROPERTY::WRITE);
    pSpeedChar->setCallbacks(sCallbacks);

    pSettingsChar = pService->createCharacteristic(CHA_SETTINGS_UUID, NIMBLE_PROPERTY::WRITE);
    pSettingsChar->setCallbacks(sCallbacks);

    pTimeChar = pService->createCharacteristic(CHA_TIME_UUID, NIMBLE_PROPERTY::WRITE);
    pTimeChar->setCallbacks(sCallbacks);

    pWeatherChar = pService->createCharacteristic(CHA_WEATHER_UUID, NIMBLE_PROPERTY::WRITE);
    pWeatherChar->setCallbacks(sCallbacks);

    pMapImageChar = pService->createCharacteristic(CHA_MAP_IMAGE_UUID, NIMBLE_PROPERTY::WRITE);
    pMapImageChar->setCallbacks(sCallbacks);

    pDeviceCtrlChar = pService->createCharacteristic(CHA_DEVICE_CTRL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    pDeviceCtrlChar->setCallbacks(sCallbacks);

    pRemoteCmdChar = pService->createCharacteristic(CHA_REMOTE_CMD_UUID, NIMBLE_PROPERTY::WRITE);
    pRemoteCmdChar->setCallbacks(sCallbacks);

    pDeviceStatusChar = pService->createCharacteristic(CHA_DEVICE_STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);

    pNotificationChar = pService->createCharacteristic(CHA_NOTIFICATION_UUID, NIMBLE_PROPERTY::WRITE);
    pNotificationChar->setCallbacks(sCallbacks);

    pPhoneBatteryChar = pService->createCharacteristic(CHA_PHONE_BATTERY_UUID, NIMBLE_PROPERTY::WRITE);
    pPhoneBatteryChar->setCallbacks(sCallbacks);

    pService->start();
    NimBLEDevice::getAdvertising()->start();

    // Khởi tạo phím bấm
    btnMode.attachClick([]()
                        {
        if (isPopupActive) {
            isPopupActive = false;
            tft.fillScreen(TFT_BLACK);
            screenNeedsRedraw = true;
            return;
        }

        if (currentMode == NOTIF_MODE) {
            if (isNotifPopupTransient) {
                // Popup: bấm nút là thoát ngay về STATUS
                isNotifPopupTransient = false;
                currentMode = STATUS_MODE;
                tft.fillScreen(TFT_BLACK);
                sendDeviceStatus();
                screenNeedsRedraw = true;
                return;
            }
            // Menu NOTIF: chuyển sang thông báo tiếp theo
            notifViewIndex++;
            if (notifViewIndex >= notifCount) {
                // Đã xem hết -> về chế độ trước đó
                currentMode = previousModeBeforeNotif;
                previousModeBeforeNotif = STATUS_MODE;
                tft.fillScreen(TFT_BLACK);
                sendDeviceStatus();
            }
            screenNeedsRedraw = true;
            return;
        }

        if (!isMenuOpen)
        {
            isMenuOpen = true;
            menuSelectedIndex = (int)currentMode;
            if (menuSelectedIndex > 3) menuSelectedIndex = 2; // STATUS mặc định
            menuStartTime = millis();
            screenNeedsRedraw = true;
        }
        else
        {
            // Chuyển highlight menu (HUD, MAP, STATUS, INFO, NOTIF)
            menuSelectedIndex = (menuSelectedIndex + 1) % 5;
            menuStartTime = millis();
            screenNeedsRedraw = true;
        } });

    btnMode.attachLongPressStart([]()
                                {
        if (isMenuOpen) {
            selectedMode = (Mode)menuSelectedIndex;
            currentMode = selectedMode;
            isMenuOpen = false;

            // NOTIF từ menu: persistent, bắt đầu từ thông báo đầu tiên
            if (currentMode == NOTIF_MODE && notifCount > 0) {
                notifViewIndex = 0;
                isNotifPopupTransient = false;
                previousModeBeforeNotif = STATUS_MODE;
            }

            tft.fillScreen(TFT_BLACK);
            sendDeviceStatus();
            screenNeedsRedraw = true;
        } });

    btnZoom.attachClick([]()
                        {
        if (currentMode == MAP_MODE && pDeviceCtrlChar) {
            // Zoom In: Gửi bit 0 = 1
            uint8_t val = 1;
            pDeviceCtrlChar->setValue(&val, 1);
            pDeviceCtrlChar->notify();
        } });

    btnZoom.attachLongPressStart([]()
                                {
        if (currentMode == MAP_MODE && pDeviceCtrlChar) {
            // Zoom Out: Gửi bit 1 = 1 (giá trị 2)
            uint8_t val = 2;
            pDeviceCtrlChar->setValue(&val, 1);
            pDeviceCtrlChar->notify();
        } });
}

void loop()
{
    btnMode.tick();
    btnZoom.tick();

    // Khởi động lại advertising an toàn từ main loop
    if (advertisingPending)
    {
        advertisingPending = false;
        NimBLEDevice::startAdvertising();
    }

    // Cập nhật pin
    updateBatteryVoltage();

    // Kiểm tra và cập nhật thời gian ở STATUS hoặc INFO mode để vẽ lại mỗi giây
    if ((currentMode == STATUS_MODE || currentMode == INFO_MODE) && !isMenuOpen && !isPopupActive)
    {
        int currentSecond = rtc.getSecond();
        if (currentSecond != lastRenderedSecond)
        {
            lastRenderedSecond = currentSecond;
            screenNeedsRedraw = true;
        }
    }

    // Xử lý gửi trạng thái trì hoãn từ BLE thread
    if (statusUpdatePending)
    {
        statusUpdatePending = false;
        sendDeviceStatus();
    }

    // Kiểm tra timeout của HUD để về STATUS mode
    if (currentMode == HUD_MODE && millis() - lastNavUpdate > (uint32_t)(hudTimeout * 30000))
    {
        currentMode = STATUS_MODE;
        screenNeedsRedraw = true;
    }

    // Kiểm tra timeout Popup Map trong HUD
    if (isPopupActive && millis() - popupStartTime > (uint32_t)(popupDuration * 5000))
    {
        isPopupActive = false;
        screenNeedsRedraw = true;
    }

    // Kiểm tra timeout NOTIF_MODE -> luôn về STATUS
    unsigned long notifTimeout = isNotifPopupTransient ? 5000 : 15000;
    if (currentMode == NOTIF_MODE && millis() - notifPopupStartTime > notifTimeout)
    {
        isNotifPopupTransient = false;
        currentMode = STATUS_MODE;
        screenNeedsRedraw = true;
        statusUpdatePending = true;
    }

    // Đóng Menu tự động sau 5s không tương tác
    if (isMenuOpen && millis() - menuStartTime > 5000)
    {
        isMenuOpen = false;
        screenNeedsRedraw = true;
    }

    // Gửi trạng thái định kỳ 10s
    if (millis() - lastStatusSent > 10000)
    {
        statusUpdatePending = true;
    }

    // Clear screen if requested from BLE thread safely
    if (needClearScreen)
    {
        needClearScreen = false;
        tft.fillScreen(TFT_BLACK);
    }

    // Render giao diện chính
    if (isMenuOpen)
    {
        if (screenNeedsRedraw)
        {
            drawMenuOverlay();
            screenNeedsRedraw = false;
        }
    }
    else if (isPopupActive)
    {
        // Đang vẽ ảnh chụp bản đồ (JPEG) phủ đè lên HUD
        if (newMapImageAvailable && jpegSize > 0 && !isReceivingJpeg)
        {
            renderJpegImage(jpegBuffer, jpegSize);
            newMapImageAvailable = false;
        }
        else if (screenNeedsRedraw && jpegSize > 0 && !isReceivingJpeg)
        {
            renderJpegImage(jpegBuffer, jpegSize);
        }
    }
    else
    {
        if (currentMode == HUD_MODE)
        {
            myFont.set_font(FONT_HUD_STREET);
            if (myFont.getLength(nextStreet) > 200)
            {
                screenNeedsRedraw = true;
            }
        }

        if (screenNeedsRedraw)
        {
            switch (currentMode)
            {
            case HUD_MODE:
                drawHUD();
                break;
            case MAP_MODE:
                if (newMapImageAvailable && jpegSize > 0 && !isReceivingJpeg)
                {
                    renderJpegImage(jpegBuffer, jpegSize);
                    newMapImageAvailable = false;
                }
                else if (screenNeedsRedraw && jpegSize > 0 && !isReceivingJpeg)
                {
                    renderJpegImage(jpegBuffer, jpegSize);
                }
                break;
            case STATUS_MODE:
                drawSTATUS();
                break;
            case INFO_MODE:
                drawINFO();
                break;
            case NOTIF_MODE:
                drawNOTIF();
                break;
            }
            screenNeedsRedraw = false;
        }
    }

    delay(20);
}
