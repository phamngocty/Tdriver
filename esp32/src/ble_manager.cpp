#include "ble_manager.h"
#include <Arduino.h>

// ===================================================================
// ServerCallbacks
// ===================================================================
class BleManager::ServerCallbacks : public BLEServerCallbacks
{
    BleManager *_parent;

public:
    ServerCallbacks(BleManager *parent) : _parent(parent) {}
    void onConnect(BLEServer *pServer, esp_ble_gatts_cb_param_t *param) override
    {
        (void)param;
        Serial.println("[BLE] Client connected");
        BLEDevice::stopAdvertising();
    }
    void onDisconnect(BLEServer *pServer) override
    {
        Serial.println("[BLE] Client disconnected. Restart advertising.");
        BLEDevice::startAdvertising();
    }
};

// ===================================================================
// onWrite callbacks cho từng characteristic
// ===================================================================

// CHA_NAV: dạng text key=value, cách nhau \n
class BleManager::NavCharCallbacks : public BLECharacteristicCallbacks
{
    BleManager *_parent;

public:
    NavCharCallbacks(BleManager *parent) : _parent(parent) {}
    void onWrite(BLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        _parent->getData()->navString = String(val.c_str());
        _parent->getData()->navUpdated = true;
        Serial.printf("[BLE] NAV: %s\n", val.c_str());
    }
};

// CHA_NAV_TBT_ICON: 1 byte
class BleManager::TbtIconCharCallbacks : public BLECharacteristicCallbacks
{
    BleManager *_parent;

public:
    TbtIconCharCallbacks(BleManager *parent) : _parent(parent) {}
    void onWrite(BLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.length() >= 1)
        {
            _parent->getData()->iconIndex = (uint8_t)val[0];
        }
    }
};

// CHA_GPS_SPEED: text km/h
class BleManager::SpeedCharCallbacks : public BLECharacteristicCallbacks
{
    BleManager *_parent;

public:
    SpeedCharCallbacks(BleManager *parent) : _parent(parent) {}
    void onWrite(BLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        _parent->getData()->speedStr = String(val.c_str());
        _parent->getData()->speedUpdated = true;
    }
};

// CHA_SETTINGS: key=value
class BleManager::SettingsCharCallbacks : public BLECharacteristicCallbacks
{
    BleManager *_parent;

public:
    SettingsCharCallbacks(BleManager *parent) : _parent(parent) {}
    void onWrite(BLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        String s(val.c_str());
        int eq = s.indexOf('=');
        if (eq > 0)
        {
            String key = s.substring(0, eq);
            String value = s.substring(eq + 1);
            key.trim();
            value.trim();
            if (key == "brightness")
            {
                _parent->getData()->brightness = value.toInt();
                _parent->getData()->settingsUpdated = true;
            }
            else if (key == "lightTheme")
            {
                _parent->getData()->lightTheme = (value == "true" || value == "1");
                _parent->getData()->settingsUpdated = true;
            }
            else if (key == "speedLimit")
            {
                _parent->getData()->speedLimit = value.toInt();
                _parent->getData()->settingsUpdated = true;
            }
        }
        Serial.printf("[BLE] SETTINGS: %s\n", val.c_str());
    }
};

// CHA_TIME: 4 byte uint32 LE
class BleManager::TimeCharCallbacks : public BLECharacteristicCallbacks
{
    BleManager *_parent;

public:
    TimeCharCallbacks(BleManager *parent) : _parent(parent) {}
    void onWrite(BLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        if (val.length() >= 4)
        {
            uint32_t ts = (uint8_t)val[0] | ((uint32_t)(uint8_t)val[1] << 8) | ((uint32_t)(uint8_t)val[2] << 16) | ((uint32_t)(uint8_t)val[3] << 24);
            _parent->getData()->unixTime = ts;
            _parent->getData()->timeUpdated = true;
        }
    }
};

// CHA_WEATHER: JSON
class BleManager::WeatherCharCallbacks : public BLECharacteristicCallbacks
{
    BleManager *_parent;

public:
    WeatherCharCallbacks(BleManager *parent) : _parent(parent) {}
    void onWrite(BLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        _parent->getData()->weatherJson = String(val.c_str());
        _parent->getData()->weatherUpdated = true;
    }
};

// CHA_MAP_IMAGE: 4 byte header (size) + JPEG data chunks
class BleManager::MapImageCharCallbacks : public BLECharacteristicCallbacks
{
    BleManager *_parent;
    enum State
    {
        WAIT_HEADER,
        RECV_DATA
    };
    State _state = WAIT_HEADER;
    uint8_t _headerBuf[4];
    uint8_t _headerIdx = 0;

public:
    MapImageCharCallbacks(BleManager *parent) : _parent(parent) {}

    void onWrite(BLECharacteristic *pChar) override
    {
        std::string val = pChar->getValue();
        const uint8_t *data = (const uint8_t *)val.data();
        size_t len = val.length();

        BleNavData *d = _parent->getData();

        if (_state == WAIT_HEADER)
        {
            // Tích lũy 4 byte header
            for (size_t i = 0; i < len && _headerIdx < 4; i++)
            {
                _headerBuf[_headerIdx++] = data[i];
            }
            if (_headerIdx == 4)
            {
                // Đã có header → parse size
                d->jpegSize = (uint32_t)_headerBuf[0] | ((uint32_t)_headerBuf[1] << 8) | ((uint32_t)_headerBuf[2] << 16) | ((uint32_t)_headerBuf[3] << 24);

                // Giới hạn an toàn
                if (d->jpegSize == 0 || d->jpegSize > 32 * 1024)
                {
                    Serial.printf("[BLE] JPEG size invalid: %lu\n", (unsigned long)d->jpegSize);
                    _state = WAIT_HEADER;
                    _headerIdx = 0;
                    d->jpegSize = 0;
                    return;
                }

                // Cấp phát bộ đệm PSRAM
                if (d->jpegBuffer)
                    free(d->jpegBuffer);
                d->jpegBuffer = (uint8_t *)ps_malloc(d->jpegSize);
                if (!d->jpegBuffer)
                {
                    Serial.println("[BLE] Failed to alloc PSRAM for JPEG");
                    d->jpegSize = 0;
                    _state = WAIT_HEADER;
                    _headerIdx = 0;
                    return;
                }

                d->jpegReceived = 0;
                _state = RECV_DATA;
                Serial.printf("[BLE] JPEG header: size=%lu\n", (unsigned long)d->jpegSize);

                // Nếu dữ liệu header đọc thêm (cả JPEG data trong gói header)
                size_t extra = len - (4 - (_headerIdx - len));
                // Thực tế headerIdx=4 rồi, tính extra data trong gói này
                if (len > 4)
                {
                    size_t dataStart = len - (len - 4);
                    // Đơn giản: xử lý phần còn lại
                    size_t remaining = len - 4;
                    // Nhưng header_idx đã là 4, nên phần còn lại của gói là dữ liệu JPEG
                    // extra data:
                    size_t toCopy = remaining;
                    if (toCopy + d->jpegReceived > d->jpegSize)
                        toCopy = d->jpegSize - d->jpegReceived;
                    memcpy(d->jpegBuffer + d->jpegReceived, data + 4, toCopy);
                    d->jpegReceived += toCopy;
                }
            }
        }
        else
        { // RECV_DATA
            size_t toCopy = len;
            if (toCopy + d->jpegReceived > d->jpegSize)
                toCopy = d->jpegSize - d->jpegReceived;
            memcpy(d->jpegBuffer + d->jpegReceived, data, toCopy);
            d->jpegReceived += toCopy;
        }

        // Kiểm tra nếu đã nhận đủ
        if (_state == RECV_DATA && d->jpegReceived >= d->jpegSize)
        {
            Serial.printf("[BLE] JPEG complete: %lu bytes\n", (unsigned long)d->jpegReceived);
            d->newJpegReady = true;
            _state = WAIT_HEADER;
            _headerIdx = 0;
        }
    }
};

// ===================================================================
// BleManager implementation
// ===================================================================

void BleManager::begin(const char *deviceName)
{
    // Khởi tạo BLE (Bluedroid)
    BLEDevice::init(deviceName);
    BLEDevice::setPower(ESP_PWR_LVL_P9); // +9 dBm

    BLEDevice::setMTU(512); // Yêu cầu MTU tối đa, app có thể thương lượng

    _server = BLEDevice::createServer();
    _server->setCallbacks(new ServerCallbacks(this));

    BLEService *service = _server->createService(SERVICE_UUID);

    // --- CHA_NAV (App → ESP) ---
    BLECharacteristic *navChar = service->createCharacteristic(
        CHA_NAV_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    navChar->setCallbacks(new NavCharCallbacks(this));

    // --- CHA_NAV_TBT_ICON (App → ESP) ---
    BLECharacteristic *iconChar = service->createCharacteristic(
        CHA_NAV_TBT_ICON_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    iconChar->setCallbacks(new TbtIconCharCallbacks(this));

    // --- CHA_GPS_SPEED (App → ESP) ---
    BLECharacteristic *speedChar = service->createCharacteristic(
        CHA_GPS_SPEED_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    speedChar->setCallbacks(new SpeedCharCallbacks(this));

    // --- CHA_SETTINGS (App → ESP) ---
    BLECharacteristic *settingsChar = service->createCharacteristic(
        CHA_SETTINGS_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    settingsChar->setCallbacks(new SettingsCharCallbacks(this));

    // --- CHA_TIME (App → ESP) ---
    BLECharacteristic *timeChar = service->createCharacteristic(
        CHA_TIME_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    timeChar->setCallbacks(new TimeCharCallbacks(this));

    // --- CHA_WEATHER (App → ESP) ---
    BLECharacteristic *weatherChar = service->createCharacteristic(
        CHA_WEATHER_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    weatherChar->setCallbacks(new WeatherCharCallbacks(this));

    // --- CHA_MAP_IMAGE (App → ESP) ---
    BLECharacteristic *mapChar = service->createCharacteristic(
        CHA_MAP_IMAGE_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    mapChar->setCallbacks(new MapImageCharCallbacks(this));

    // --- CHA_DEVICE_CTRL (ESP → App) ---
    _ctrlChar = service->createCharacteristic(
        CHA_DEVICE_CTRL_UUID,
        BLECharacteristic::PROPERTY_NOTIFY);

    // --- CHA_DEVICE_STATUS (ESP → App) ---
    _statusChar = service->createCharacteristic(
        CHA_DEVICE_STATUS_UUID,
        BLECharacteristic::PROPERTY_NOTIFY);

    // Bắt đầu service
    service->start();

    // Advertising
    BLEAdvertising *adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->setScanResponse(true);
    adv->setMinPreferred(0x06);
    adv->setMinPreferred(0x12);
    adv->start();

    Serial.printf("[BLE] Device '%s' advertising...\n", deviceName);
}

void BleManager::sendCtrl(uint8_t byte)
{
    if (_ctrlChar && isConnected())
    {
        _ctrlChar->setValue(&byte, 1);
        _ctrlChar->notify();
        Serial.printf("[BLE] CTRL sent: 0x%02X\n", byte);
    }
}

void BleManager::sendStatus(const String &status)
{
    if (_statusChar && isConnected())
    {
        _statusChar->setValue(status.c_str());
        _statusChar->notify();
    }
}

bool BleManager::isConnected()
{
    return _server && _server->getConnectedCount() > 0;
}
