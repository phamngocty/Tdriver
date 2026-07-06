#include <Arduino.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <NimBLEDevice.h>
#include <ESP32Time.h>
#include <OneButton.h>
#include <ArduinoJson.h>

#define MODE_BTN 2
#define ZOOM_BTN 3
#define BAT_ADC 0

// GATT Server UUIDs
const char* SERVICE_UUID = "0000feed-0000-1000-8000-00805f9b34fb";
const char* CHA_NAV_UUID = "0b11deef-1563-447f-aece-d3dfeb1c1f20";
const char* CHA_NAV_TBT_ICON_UUID = "d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad";
const char* CHA_ICON_DATA_UUID = "e2f3a4b5-c6d7-4890-e123-456789abcdef";
const char* CHA_GPS_SPEED_UUID = "98b6073a-5cf3-4e73-b6d3-f8e05fa018a9";
const char* CHA_SETTINGS_UUID = "9d37a346-63d3-4df6-8eee-f0242949f59f";
const char* CHA_TIME_UUID = "a1b2c3d4-e5f6-4789-a012-3456789abcde";
const char* CHA_WEATHER_UUID = "b2c3d4e5-f6a7-4890-b123-456789abcdef";
const char* CHA_OLED_IMAGE_UUID = "e1f2a3b4-c5d6-4789-a012-3456789abcdef";
const char* CHA_DEVICE_CTRL_UUID = "d4e5f6a7-b8c9-4012-d345-678901bcdef0";
const char* CHA_REMOTE_CMD_UUID = "f1a2b3c4-d5e6-4789-a012-3456789abcde";
const char* CHA_DEVICE_STATUS_UUID = "a1b2c3d4-e5f6-4789-b012-3456789abcde";
const char* CHA_NOTIFICATION_UUID = "c1d2e3f4-a5b6-4789-c012-3456789abcdef";

enum Mode { HUD_MODE, MAP_MODE, STATUS_MODE };
Mode currentMode = STATUS_MODE;
Mode selectedMode = STATUS_MODE;

U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, /* reset=*/ U8X8_PIN_NONE);
ESP32Time rtc;
OneButton btnMode(MODE_BTN, true);
OneButton btnZoom(ZOOM_BTN, true);

// BLE Characteristics
NimBLECharacteristic* pDeviceStatusChar = nullptr;
NimBLECharacteristic* pDeviceCtrlChar = nullptr;

// HUD Dẫn đường
String nextStreet = "";
String distToNext = "";
String totalDist = "";
String eta = "";
String ete = "";
int gpsSpeed = 0;
uint8_t staticIconIndex = 0;
uint32_t lastNavUpdate = 0;

// Cài đặt
int brightness = 80;

// Thời tiết & Pin
float weatherTemp = 0.0;
String weatherIcon = "";
float batteryVoltage = 0.0;
unsigned long lastStatusSent = 0;

// Notification
String notifApp = "";
String notifTitle = "";
String notifMsg = "";
unsigned long notifTime = 0;

// Icon Cache (FIFO)
struct CachedIcon {
    uint32_t hash;
    uint8_t bitmap[288];
};
CachedIcon iconCache[20];
int cacheSize = 0;
uint32_t currentIconHash = 0;
bool hasCustomIcon = false;
uint8_t customIconBitmap[288];

// Nhận ảnh OLED 128x64 1bpp (1024 bytes)
uint8_t oledBuffer[1024];
uint32_t oledImageSize = 0;
uint32_t oledImageWritten = 0;
bool isReceivingOledImage = false;

// Trạng thái Menu Chồng
bool isMenuOpen = false;
unsigned long menuStartTime = 0;
int menuSelectedIndex = 0; // 0: HUD, 1: MAP, 2: STATUS

// Tìm icon trong cache
bool getCachedIcon(uint32_t hash, uint8_t* outBitmap) {
    for (int i = 0; i < cacheSize; i++) {
        if (iconCache[i].hash == hash) {
            memcpy(outBitmap, iconCache[i].bitmap, 288);
            return true;
        }
    }
    return false;
}

// Thêm icon vào cache
void addIconToCache(uint32_t hash, const uint8_t* bitmap) {
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

// Gửi trạng thái thiết bị
void sendDeviceStatus() {
    if (!pDeviceStatusChar) return;
    char buffer[128];
    String modeStr = "STATUS";
    if (currentMode == HUD_MODE) modeStr = "HUD";
    else if (currentMode == MAP_MODE) modeStr = "MAP";
    
    int rssi = -55 - (random() % 15);
    
    snprintf(buffer, sizeof(buffer), "mode=%s\nvoltage=%.1f\nrssi=%d\ndisplay=OLED128x64", 
             modeStr.c_str(), batteryVoltage, rssi);
    pDeviceStatusChar->setValue(buffer);
    pDeviceStatusChar->notify();
    lastStatusSent = millis();
}

// Đo pin C3
void updateBatteryVoltage() {
    int adcVal = analogRead(BAT_ADC);
    // Đo trực tiếp với cầu phân áp R1=100k, R2=27k. GPIO0
    float vAdc = (adcVal / 4095.0) * 3.1;
    batteryVoltage = vAdc * (127.0 / 27.0);
}

// Cài đặt độ sáng OLED
void setOledContrast(int pct) {
    brightness = pct;
    int contrast = (pct * 255) / 100;
    u8g2.setContrast(contrast);
}

// Callback nhận dữ liệu BLE
class ServerCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pCharacteristic) {
        String uuid = pCharacteristic->getUUID().toString().c_str();
        std::string val = pCharacteristic->getValue();

        if (uuid == CHA_NAV_UUID) {
            String data = val.c_str();
            int startIdx = 0;
            while (startIdx < data.length()) {
                int endIdx = data.indexOf('\n', startIdx);
                if (endIdx == -1) endIdx = data.length();
                String line = data.substring(startIdx, endIdx);
                startIdx = endIdx + 1;
                
                int eqIdx = line.indexOf('=');
                if (eqIdx != -1) {
                    String key = line.substring(0, eqIdx);
                    String value = line.substring(eqIdx + 1);
                    if (key == "dist") distToNext = value;
                    else if (key == "inst" || key == "title") nextStreet = value;
                    else if (key == "road" || key == "dir") totalDist = value;
                    else if (key == "eta") eta = value;
                    else if (key == "ete") ete = value;
                }
            }
            lastNavUpdate = millis();
        }
        else if (uuid == CHA_NAV_TBT_ICON_UUID) {
            if (val.length() == 1) {
                staticIconIndex = val[0];
                hasCustomIcon = false;
            } else {
                String strVal = val.c_str();
                if (strVal.startsWith("hash=")) {
                    String hashStr = strVal.substring(5);
                    uint32_t hash = strtoul(hashStr.c_str(), nullptr, 16);
                    currentIconHash = hash;
                    
                    uint8_t bitmap[288];
                    if (getCachedIcon(hash, bitmap)) {
                        memcpy(customIconBitmap, bitmap, 288);
                        hasCustomIcon = true;
                    } else {
                        char reqBuf[64];
                        snprintf(reqBuf, sizeof(reqBuf), "icon_req=%s", hashStr.c_str());
                        pDeviceStatusChar->setValue(reqBuf);
                        pDeviceStatusChar->notify();
                    }
                } else if (val.length() == 288) {
                    memcpy(customIconBitmap, val.data(), 288);
                    hasCustomIcon = true;
                }
            }
        }
        else if (uuid == CHA_ICON_DATA_UUID) {
            if (val.length() == 292) {
                uint32_t hash;
                memcpy(&hash, val.data(), 4);
                const uint8_t* bitmap = (const uint8_t*)(val.data() + 4);
                addIconToCache(hash, bitmap);
                
                if (hash == currentIconHash) {
                    memcpy(customIconBitmap, bitmap, 288);
                    hasCustomIcon = true;
                }
                
                char ackBuf[64];
                snprintf(ackBuf, sizeof(ackBuf), "icon_ack=%08X", hash);
                pDeviceStatusChar->setValue(ackBuf);
                pDeviceStatusChar->notify();
            }
        }
        else if (uuid == CHA_GPS_SPEED_UUID) {
            gpsSpeed = atoi(val.c_str());
        }
        else if (uuid == CHA_SETTINGS_UUID) {
            String s = val.c_str();
            int eqIdx = s.indexOf('=');
            if (eqIdx != -1) {
                String key = s.substring(0, eqIdx);
                String value = s.substring(eqIdx + 1);
                if (key == "brightness") {
                    setOledContrast(value.toInt());
                }
            }
        }
        else if (uuid == CHA_TIME_UUID) {
            if (val.length() == 4) {
                uint32_t ts;
                memcpy(&ts, val.data(), 4);
                rtc.setTime(ts);
            }
        }
        else if (uuid == CHA_WEATHER_UUID) {
            JsonDocument doc;
            DeserializationError error = deserializeJson(doc, val.c_str());
            if (!error) {
                weatherTemp = doc["t"] | 0.0;
                weatherIcon = doc["i"] | "";
            }
        }
        else if (uuid == CHA_OLED_IMAGE_UUID) {
            if (!isReceivingOledImage) {
                if (val.length() == 2) {
                    uint16_t size;
                    memcpy(&size, val.data(), 2);
                    oledImageSize = size;
                    oledImageWritten = 0;
                    isReceivingOledImage = true;
                }
            } else {
                memcpy(oledBuffer + oledImageWritten, val.data(), val.length());
                oledImageWritten += val.length();
                if (oledImageWritten >= oledImageSize) {
                    isReceivingOledImage = false;
                }
            }
        }
        else if (uuid == CHA_REMOTE_CMD_UUID) {
            if (val.length() == 1) {
                uint8_t cmd = val[0];
                if (cmd == 0x10) currentMode = HUD_MODE;
                else if (cmd == 0x11) currentMode = MAP_MODE;
                else if (cmd == 0x12) currentMode = STATUS_MODE;
                else if (cmd == 0x20) sendDeviceStatus();
                else if (cmd == 0x30) {
                    pDeviceStatusChar->setValue("ping=ok");
                    pDeviceStatusChar->notify();
                }
                else if (cmd == 0xFF) ESP.restart();
            }
        }
        else if (uuid == CHA_NOTIFICATION_UUID) {
            JsonDocument doc;
            DeserializationError error = deserializeJson(doc, val.c_str());
            if (!error) {
                notifApp = doc["app"] | "";
                notifTitle = doc["title"] | "";
                notifMsg = doc["message"] | "";
                notifTime = millis();
            }
        }
    }
};

// Vẽ custom icon 1bpp lên OLED
void drawCustomIconOled(int xOffset, int yOffset, const uint8_t* bitmap) {
    for (int y = 0; y < 48; y++) {
        for (int x = 0; x < 48; x++) {
            int byteIdx = (y * 48 + x) / 8;
            int bitPos = 7 - (x % 8);
            bool isPixel = (bitmap[byteIdx] & (1 << bitPos)) != 0;
            if (isPixel) {
                u8g2.drawPixel(xOffset + x, yOffset + y);
            }
        }
    }
}

// Vẽ Default Turn Icon lên OLED
void drawDefaultTurnIconOled(uint8_t idx, int cx, int cy) {
    u8g2.drawCircle(cx, cy, 20);
    switch (idx) {
        case 0: // Đi thẳng
            u8g2.drawLine(cx, cy + 12, cx, cy - 12);
            u8g2.drawLine(cx, cy - 12, cx - 4, cy - 8);
            u8g2.drawLine(cx, cy - 12, cx + 4, cy - 8);
            break;
        case 1:
        case 2:
        case 3: // Rẽ trái (1: Slight Left, 2: Left, 3: Sharp Left)
            u8g2.drawLine(cx + 8, cy + 8, cx + 8, cy);
            u8g2.drawLine(cx + 8, cy, cx - 8, cy);
            u8g2.drawLine(cx - 8, cy, cx - 4, cy - 4);
            u8g2.drawLine(cx - 8, cy, cx - 4, cy + 4);
            break;
        case 4:
        case 5:
        case 6: // Rẽ phải (4: Slight Right, 5: Right, 6: Sharp Right)
            u8g2.drawLine(cx - 8, cy + 8, cx - 8, cy);
            u8g2.drawLine(cx - 8, cy, cx + 8, cy);
            u8g2.drawLine(cx + 8, cy, cx + 4, cy - 4);
            u8g2.drawLine(cx + 8, cy, cx + 4, cy + 4);
            break;
        case 7:
        case 8: // Quay đầu
            u8g2.drawCircle(cx, cy + 3, 6);
            break;
        default:
            u8g2.drawCircle(cx, cy, 8);
            break;
    }
}

void drawHUD() {
    u8g2.clearBuffer();
    
    // 1. Vẽ Icon rẽ
    if (hasCustomIcon) {
        drawCustomIconOled(4, 8, customIconBitmap);
    } else {
        drawDefaultTurnIconOled(staticIconIndex, 28, 32);
    }
    
    // 2. Vẽ khoảng cách rẽ
    u8g2.setFont(u8g2_font_7x14_tf);
    u8g2.drawStr(60, 18, distToNext.c_str());
    
    // 3. Vẽ chỉ dẫn đường đi
    u8g2.setFont(u8g2_font_6x10_tf);
    u8g2.drawUTF8(60, 32, nextStreet.substring(0, 11).c_str());
    
    // 4. Vẽ tốc độ và ETA
    char speedBuf[32];
    snprintf(speedBuf, sizeof(speedBuf), "%d km/h", gpsSpeed);
    u8g2.drawStr(60, 48, speedBuf);
    
    u8g2.drawUTF8(48, 62, (ete.length() > 0 ? (ete + " | " + eta) : ("ETA " + eta)).c_str());
    
    u8g2.sendBuffer();
}

void drawSTATUS() {
    u8g2.clearBuffer();
    
    // 1. Vẽ đồng hồ giờ
    u8g2.setFont(u8g2_font_9x15B_tf);
    u8g2.drawStr(32, 22, rtc.getTime("%H:%M:%S").c_str());
    
    // 2. Vẽ thời tiết và pin
    u8g2.setFont(u8g2_font_6x10_tf);
    char statusBuf[64];
    snprintf(statusBuf, sizeof(statusBuf), "%.1f C | %.1f V", weatherTemp, batteryVoltage);
    u8g2.drawUTF8(24, 38, statusBuf);
    
    // 3. Vẽ notification nếu có trong vòng 8 giây
    if (notifApp.length() > 0 && millis() - notifTime < 8000) {
        u8g2.drawFrame(0, 42, 128, 22);
        u8g2.drawStr(4, 52, (notifApp + ": " + notifTitle).substring(0, 20).c_str());
        u8g2.drawStr(4, 61, notifMsg.substring(0, 20).c_str());
    } else {
        u8g2.drawStr(12, 54, "Hệ thống TYMAP-C3");
    }
    
    u8g2.sendBuffer();
}

void drawMenuOverlay() {
    u8g2.clearBuffer();
    u8g2.drawFrame(0, 0, 128, 64);
    u8g2.setFont(u8g2_font_6x10_tf);
    u8g2.drawUTF8(24, 12, "CHỌN CHẾ ĐỘ");
    
    const char* options[] = {"1. HUD MODE", "2. MAP MODE", "3. STATUS MODE"};
    for (int i = 0; i < 3; i++) {
        if (i == menuSelectedIndex) {
            u8g2.drawBox(4, 18 + i * 14, 120, 13);
            u8g2.setDrawColor(0); // Chữ màu đen trên nền trắng highlight
            u8g2.drawUTF8(8, 28 + i * 14, options[i]);
            u8g2.setDrawColor(1);
        } else {
            u8g2.drawUTF8(8, 28 + i * 14, options[i]);
        }
    }
    u8g2.sendBuffer();
}

void setup() {
    Serial.begin(115200);
    
    // Khởi động màn hình OLED qua I2C: SDA=8, SCL=9 cho ESP32-C3
    Wire.begin(8, 9);
    Wire.setClock(400000); // Tăng tốc I2C lên 400kHz (Fast Mode)
    u8g2.begin();
    u8g2.setFont(u8g2_font_6x10_tf);
    
    rtc.setTime(1719360000); // Mặc định 00:00:00

    // Khởi tạo BLE
    NimBLEDevice::init("TYMAP-C3");
    NimBLEServer* pServer = NimBLEDevice::createServer();
    NimBLEService* pService = pServer->createService(SERVICE_UUID);

    ServerCallbacks* sCallbacks = new ServerCallbacks();

    pService->createCharacteristic(CHA_NAV_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    pService->createCharacteristic(CHA_NAV_TBT_ICON_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    pService->createCharacteristic(CHA_ICON_DATA_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    pService->createCharacteristic(CHA_GPS_SPEED_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    pService->createCharacteristic(CHA_SETTINGS_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    pService->createCharacteristic(CHA_TIME_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    pService->createCharacteristic(CHA_WEATHER_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    pService->createCharacteristic(CHA_OLED_IMAGE_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    
    pDeviceCtrlChar = pService->createCharacteristic(CHA_DEVICE_CTRL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
    pDeviceCtrlChar->setCallbacks(sCallbacks);
    
    pService->createCharacteristic(CHA_REMOTE_CMD_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);
    
    pDeviceStatusChar = pService->createCharacteristic(CHA_DEVICE_STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
    
    pService->createCharacteristic(CHA_NOTIFICATION_UUID, NIMBLE_PROPERTY::WRITE)->setCallbacks(sCallbacks);

    pService->start();
    NimBLEDevice::getAdvertising()->start();

    // Khởi tạo phím bấm
    btnMode.attachClick([]() {
        if (!isMenuOpen) {
            isMenuOpen = true;
            menuSelectedIndex = (int)currentMode;
            menuStartTime = millis();
        } else {
            menuSelectedIndex = (menuSelectedIndex + 1) % 3;
            menuStartTime = millis();
        }
    });

    btnMode.attachLongPressStart([]() {
        if (isMenuOpen) {
            selectedMode = (Mode)menuSelectedIndex;
            currentMode = selectedMode;
            isMenuOpen = false;
            sendDeviceStatus();
        }
    });

    btnZoom.attachClick([]() {
        if (currentMode == MAP_MODE && pDeviceCtrlChar) {
            uint8_t val = 1; // Zoom In
            pDeviceCtrlChar->setValue(&val, 1);
            pDeviceCtrlChar->notify();
        }
    });

    btnZoom.attachLongPressStart([]() {
        if (currentMode == MAP_MODE && pDeviceCtrlChar) {
            uint8_t val = 2; // Zoom Out
            pDeviceCtrlChar->setValue(&val, 1);
            pDeviceCtrlChar->notify();
        }
    });
}

void loop() {
    btnMode.tick();
    btnZoom.tick();

    updateBatteryVoltage();

    // Timeout 3s HUD
    if (currentMode == HUD_MODE && millis() - lastNavUpdate > 3000) {
        currentMode = STATUS_MODE;
    }

    // Timeout menu 5s
    if (isMenuOpen && millis() - menuStartTime > 5000) {
        isMenuOpen = false;
    }

    // Gửi status định kỳ 10s
    if (millis() - lastStatusSent > 10000) {
        sendDeviceStatus();
    }

    // Render giao diện
    if (isMenuOpen) {
        drawMenuOverlay();
    } else {
        switch (currentMode) {
            case HUD_MODE:
                drawHUD();
                break;
            case MAP_MODE:
                // Ảnh OLED map được vẽ trực tiếp qua drawXBM khi nhận đủ qua BLE
                u8g2.clearBuffer();
                u8g2.drawXBM(0, 0, 128, 64, oledBuffer);
                u8g2.sendBuffer();
                break;
            case STATUS_MODE:
                drawSTATUS();
                break;
        }
    }

    delay(30);
}
