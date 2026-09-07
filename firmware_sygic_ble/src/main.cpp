#include <Arduino.h>
#include <Wire.h>
#include <U8g2lib.h>
#include <NimBLEDevice.h>
#include <OneButton.h>
#include <Preferences.h>
#include <ESP32Time.h>
#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// Hardware Pins (ESP32-C3)
#ifndef OLED_SDA
#define OLED_SDA 6
#endif
#ifndef OLED_SCL
#define OLED_SCL 7
#endif
#define BOOT_BTN 9
#define BAT_ADC  3

// Sygic BLE Service & Characteristic UUIDs
#define SYGIC_SERVICE_UUID        "DD3F0AD1-6239-4E1F-81F1-91F6C9F01D86"
#define SYGIC_CHAR_INDICATE_UUID  "DD3F0AD2-6239-4E1F-81F1-91F6C9F01D86"
#define SYGIC_CHAR_WRITE_UUID     "DD3F0AD3-6239-4E1F-81F1-91F6C9F01D86"

// Sygic Direction Codes
enum SygicDirection
{
    DIR_NONE = 0,
    DIR_START = 1,
    DIR_EASY_LEFT = 2,
    DIR_EASY_RIGHT = 3,
    DIR_END = 4,
    DIR_VIA = 5,
    DIR_KEEP_LEFT = 6,
    DIR_KEEP_RIGHT = 7,
    DIR_LEFT = 8,
    DIR_OUT_OF_ROUTE = 9,
    DIR_RIGHT = 10,
    DIR_SHARP_LEFT = 11,
    DIR_SHARP_RIGHT = 12,
    DIR_STRAIGHT = 13,
    DIR_UTURN_LEFT = 14,
    DIR_UTURN_RIGHT = 15,
    DIR_FERRY = 16,
    DIR_FOLLOW = 18,
    DIR_ROUNDABOUT_FIRST = 23,
    DIR_ROUNDABOUT_LAST = 38
};

// OLED Display U8g2 SH1106 I2C 128x64
U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, /* reset=*/ U8X8_PIN_NONE);

// OneButton for Boot button (GPIO9)
OneButton btnBoot(BOOT_BTN, true);

// Real-Time Clock
ESP32Time rtc;

// BLE Server & Characteristics
NimBLEServer *pServer = nullptr;
NimBLECharacteristic *pCharIndicate = nullptr;
NimBLECharacteristic *pCharWrite = nullptr;

// State Variables
volatile bool deviceConnected = false;
volatile bool isNavigating = false;
uint8_t currentDirection = 0;
uint8_t currentSpeedLimit = 0;
String currentDistance = "";
uint32_t lastNavDataTime = 0;
uint32_t lastActivityTime = 0;
uint32_t bootTimestamp = 0;
bool powerCycleArmed = false;

// Battery Voltmeter Variables
float batteryVoltage = 12.6f;
uint16_t autoSampleIntervalMs = 25;

// ---------------- Helper: Switch to Factory Portal ----------------
static void switchToFactoryPortal(const char *reason = "CHUYEN FACTORY...")
{
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_6x10_tf);
    u8g2.drawStr(0, 20, "=== TYMAP DUAL-BOOT ===");
    u8g2.drawStr(0, 36, reason);
    u8g2.drawStr(0, 52, "Dang khoi dong lai...");
    u8g2.sendBuffer();

    delay(500);

    const esp_partition_t *fact = esp_partition_find_first(
        ESP_PARTITION_TYPE_APP, ESP_PARTITION_SUBTYPE_APP_FACTORY, "factory");
    if (fact)
    {
        esp_ota_set_boot_partition(fact);
    }
    delay(200);
    esp_restart();
}

// ---------------- Power-Cycle 3 times Handler ----------------
static void checkPowerCycleTrigger()
{
    Preferences p;
    p.begin("bootasst", false);
    uint8_t count = p.getUChar("pc_count", 0);
    count++;
    p.putUChar("pc_count", count);
    p.end();

    Serial.printf("[PowerCycle] Khoi dong lan: %d\n", count);

    if (count >= 3)
    {
        Serial.println("[PowerCycle] Phat hien bat/tat khoa 3 lan -> Chuyen ve Factory Web Portal!");
        Preferences p2;
        p2.begin("bootasst", false);
        p2.putUChar("pc_count", 0);
        p2.end();
        switchToFactoryPortal("BAT/TAT 3 LAN -> PORTAL");
    }
}

// ---------------- Đo Điện Áp Bình Ắc Quy Xe Chống Nhiễu Bugi ----------------
void updateBatteryVoltage()
{
    static unsigned long lastSampleTime = 0;
    unsigned long now = millis();
    if (now - lastSampleTime < autoSampleIntervalMs && lastSampleTime != 0)
    {
        return;
    }
    lastSampleTime = now;

    // Đảm bảo ngắt pull-up/pull-down nội trên chân ADC GPIO3
    gpio_pullup_dis((gpio_num_t)BAT_ADC);
    gpio_pulldown_dis((gpio_num_t)BAT_ADC);

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

    // Cầu phân áp R1 = 10k (xuống GND), R2 = 100k (lên Vin 12V)
    // Hiệu chuẩn tuyến tính đơn nhất (Monotonic Calibration)
    float rawV = (rawMv / 1000.0f) * 11.0f;
    float instantVoltage = 0.0f;
    if (rawV > 3.7025f)
    {
        instantVoltage = (rawV - 3.7025f) / 0.86625f;
    }
    else
    {
        instantVoltage = 0.0f;
    }

    if (batteryVoltage <= 0.5f)
    {
        batteryVoltage = instantVoltage;
        return;
    }

    // Lọc mượt mà số đo hiển thị
    batteryVoltage = batteryVoltage + 0.12f * (instantVoltage - batteryVoltage);
}

// ---------------- BLE Callbacks ----------------
class ServerCallbacks : public NimBLEServerCallbacks
{
    void onConnect(NimBLEServer *pServer) override
    {
        deviceConnected = true;
        Serial.println("[Sygic BLE] iPhone da ket noi!");
        lastActivityTime = millis();
    }

    void onConnect(NimBLEServer *pServer, ble_gap_conn_desc *desc) override
    {
        deviceConnected = true;
        Serial.println("[Sygic BLE] iPhone da ket noi (gap)!");
        lastActivityTime = millis();
    }

    void onDisconnect(NimBLEServer *pServer) override
    {
        deviceConnected = false;
        isNavigating = false;
        Serial.println("[Sygic BLE] iPhone da ngat ket noi! Bat lai quang ba...");
        NimBLEDevice::startAdvertising();
    }

    void onDisconnect(NimBLEServer *pServer, ble_gap_conn_desc *desc) override
    {
        deviceConnected = false;
        isNavigating = false;
        Serial.println("[Sygic BLE] iPhone da ngat ket noi (gap)! Bat lai quang ba...");
        NimBLEDevice::startAdvertising();
    }
};

class WriteCallbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pCharacteristic) override
    {
        std::string value = pCharacteristic->getValue();
        if (value.length() == 0) return;

        lastActivityTime = millis();

        // Gói tin Sygic BLE: 20 bytes Turn-by-Turn
        uint8_t *data = (uint8_t *)value.data();
        size_t len = value.length();

        Serial.printf("[Sygic BLE Data] Len = %d bytes\n", len);

        if (len >= 6)
        {
            uint8_t direction = data[0];
            uint8_t speedLimit = data[2];

            char distStr[16] = {0};
            size_t copyLen = (len - 4 < 15) ? (len - 4) : 15;
            memcpy(distStr, &data[4], copyLen);
            distStr[copyLen] = '\0';

            currentDirection = direction;
            currentSpeedLimit = speedLimit;
            currentDistance = String(distStr);
            currentDistance.trim();

            if (currentDirection != DIR_NONE || currentDistance.length() > 0)
            {
                isNavigating = true;
                lastNavDataTime = millis();
            }

            Serial.printf("[Sygic HUD] Dir: %d | Dist: %s | SpeedLimit: %d\n",
                          currentDirection, currentDistance.c_str(), currentSpeedLimit);
        }
    }
};

// ---------------- Vẽ Mũi Tên Rẽ Vector Lớn (48x48) ----------------
static void drawLargeTurnIcon(int cx, int cy, uint8_t dir)
{
    switch (dir)
    {
    case DIR_LEFT:
    case DIR_SHARP_LEFT:
    case DIR_EASY_LEFT:
    case DIR_KEEP_LEFT:
        // Mũi tên rẽ trái lớn nét đậm
        u8g2.drawBox(cx - 2, cy + 6, 8, 16);
        u8g2.drawBox(cx - 16, cy - 2, 22, 8);
        u8g2.drawTriangle(cx - 24, cy + 2, cx - 12, cy - 9, cx - 12, cy + 13);
        break;

    case DIR_RIGHT:
    case DIR_SHARP_RIGHT:
    case DIR_EASY_RIGHT:
    case DIR_KEEP_RIGHT:
        // Mũi tên rẽ phải lớn nét đậm
        u8g2.drawBox(cx - 6, cy + 6, 8, 16);
        u8g2.drawBox(cx - 6, cy - 2, 22, 8);
        u8g2.drawTriangle(cx + 24, cy + 2, cx + 12, cy - 9, cx + 12, cy + 13);
        break;

    case DIR_STRAIGHT:
    case DIR_START:
    case DIR_FOLLOW:
        // Đi thẳng
        u8g2.drawBox(cx - 4, cy - 4, 8, 26);
        u8g2.drawTriangle(cx, cy - 20, cx - 15, cy - 4, cx + 15, cy - 4);
        break;

    case DIR_UTURN_LEFT:
    case DIR_UTURN_RIGHT:
        // Quay đầu U-turn
        u8g2.drawCircle(cx, cy - 4, 13, U8G2_DRAW_UPPER_LEFT | U8G2_DRAW_UPPER_RIGHT);
        u8g2.drawCircle(cx, cy - 4, 12, U8G2_DRAW_UPPER_LEFT | U8G2_DRAW_UPPER_RIGHT);
        u8g2.drawBox(cx + 6, cy - 4, 7, 24);
        u8g2.drawBox(cx - 13, cy - 4, 7, 16);
        u8g2.drawTriangle(cx - 10, cy + 20, cx - 20, cy + 9, cx, cy + 9);
        break;

    case DIR_END:
        // Đích đến (Cờ ca-rô / Biểu tượng đích)
        u8g2.drawCircle(cx, cy, 18);
        u8g2.drawDisc(cx, cy, 8);
        break;

    default:
        if (dir >= DIR_ROUNDABOUT_FIRST && dir <= DIR_ROUNDABOUT_LAST)
        {
            // Vòng xuyến: Vòng tròn đôi + Số lối ra to ở trung tâm
            u8g2.drawCircle(cx, cy, 19);
            u8g2.drawCircle(cx, cy, 18);
            u8g2.drawCircle(cx, cy, 12);
            int exitNum = (dir - DIR_ROUNDABOUT_FIRST) % 8 + 1;
            char eBuf[4];
            snprintf(eBuf, sizeof(eBuf), "%d", exitNum);
            u8g2.setFont(u8g2_font_helvB14_tf);
            int w = u8g2.getStrWidth(eBuf);
            u8g2.drawStr(cx - w / 2, cy + 5, eBuf);
        }
        else
        {
            // Mặc định: Đi theo đường
            u8g2.drawBox(cx - 3, cy - 6, 6, 26);
            u8g2.drawTriangle(cx, cy - 18, cx - 12, cy - 6, cx + 12, cy - 6);
        }
        break;
    }
}

// ---------------- Giao Diện 1: Chế Độ Chờ (Mẫu 1: Đo Bình Bar) ----------------
static void renderIdleScreen()
{
    String timeStr = rtc.getTime("GIỜ: %H:%M");

    // 1. Header (y=0..12)
    u8g2.setFont(u8g2_font_6x10_tf);
    u8g2.drawStr(2, 9, timeStr.c_str());

    if (deviceConnected)
    {
        u8g2.drawDisc(84, 5, 2);
        u8g2.drawStr(90, 9, "IPHONE");
    }
    else
    {
        u8g2.drawCircle(84, 5, 2);
        u8g2.drawStr(90, 9, "BLE CHO");
    }
    u8g2.drawHLine(0, 12, 128);

    // 2. Điện áp số lớn trung tâm (y=14..45)
    char vBuf[16];
    if (batteryVoltage < 10.0f)
        snprintf(vBuf, sizeof(vBuf), "%.2f", batteryVoltage);
    else
        snprintf(vBuf, sizeof(vBuf), "%.1f", batteryVoltage);

    u8g2.setFont(u8g2_font_logisoso28_tn);
    int vLen = u8g2.getStrWidth(vBuf);
    int startX = (128 - vLen - 30) / 2;
    if (startX < 4) startX = 4;
    u8g2.drawStr(startX, 42, vBuf);

    u8g2.setFont(u8g2_font_7x14B_tf);
    u8g2.drawStr(startX + vLen + 4, 38, "VOLT");

    // 3. Thước đo điện áp dải 10.0V - 14.8V (y=47..63)
    u8g2.drawFrame(4, 47, 120, 8);
    int fillW = (int)((batteryVoltage - 10.0f) * 116.0f / 4.8f);
    if (fillW < 0) fillW = 0;
    if (fillW > 116) fillW = 116;
    if (fillW > 0)
    {
        u8g2.drawBox(6, 49, fillW, 4);
    }

    // Các mốc vạch nhỏ chân màn hình
    u8g2.setFont(u8g2_font_4x6_tf);
    u8g2.drawStr(4, 63, "10.0V");
    u8g2.drawStr(52, 63, "12.0V(BINH)");
    u8g2.drawStr(98, 63, "14.8V");
}

// ---------------- Giao Diện 2: Dẫn Đường HUD Classic Boxed (Biến Thể A) ----------------
static void renderHudScreen()
{
    // Cột trái (0..48): Mũi tên rẽ lớn 48x48
    drawLargeTurnIcon(24, 32, currentDirection);

    // Vạch phân cách chấm dọc tại x=50
    for (int y = 0; y < 64; y += 4)
    {
        u8g2.drawPixel(50, y);
        u8g2.drawPixel(50, y + 1);
    }

    // Cột phải (52..127):
    // 1. Khoảng cách rẽ chữ to nổi bật (Font helvB18)
    u8g2.setFont(u8g2_font_helvB18_tf);
    if (currentDistance.length() > 0)
    {
        u8g2.drawStr(54, 25, currentDistance.c_str());
    }
    else
    {
        u8g2.drawStr(54, 25, "150 M");
    }

    // 2. Biển báo tốc độ giới hạn tròn (nếu có giới hạn > 0)
    if (currentSpeedLimit > 0 && currentSpeedLimit <= 150)
    {
        u8g2.drawCircle(68, 47, 13);
        u8g2.drawCircle(68, 47, 12);
        char sBuf[8];
        snprintf(sBuf, sizeof(sBuf), "%d", currentSpeedLimit);
        u8g2.setFont(u8g2_font_helvB10_tf);
        int sw = u8g2.getStrWidth(sBuf);
        u8g2.drawStr(68 - sw / 2, 51, sBuf);

        // Thông tin phụ bên cạnh biển báo
        u8g2.setFont(u8g2_font_5x8_tf);
        u8g2.drawStr(85, 43, "GIOI HAN");
        char vSub[16];
        snprintf(vSub, sizeof(vSub), "%.1fV | OK", batteryVoltage);
        u8g2.drawStr(85, 53, vSub);
    }
    else
    {
        // Khi không có biển báo tốc độ: Hiển thị điện áp và trạng thái kết nối
        u8g2.setFont(u8g2_font_7x14B_tf);
        char vSub[16];
        snprintf(vSub, sizeof(vSub), "%.1f VOLT", batteryVoltage);
        u8g2.drawStr(54, 45, vSub);

        u8g2.setFont(u8g2_font_5x8_tf);
        u8g2.drawStr(54, 57, "SYGIC BLE [OK]");
    }
}

// ---------------- Master Render Function ----------------
static void renderDisplay()
{
    u8g2.clearBuffer();

    // Tự động kiểm tra timeout dẫn đường: Nếu quá 5 giây không có gói tin rẽ mới -> về Chế độ Chờ
    if (isNavigating && (millis() - lastNavDataTime > 5000))
    {
        isNavigating = false;
        Serial.println("[Nav State] Timeout 5s -> Chuyen ve Che do Cho (Telemetry Đo Bình)");
    }

    if (!isNavigating)
    {
        // Chế độ 1 đã chốt: Mẫu 1 (Đồng hồ & Ắc quy trực quan)
        renderIdleScreen();
    }
    else
    {
        // Chế độ 2 đã chốt: HUD Biến Thể A (Classic Boxed)
        renderHudScreen();
    }

    u8g2.sendBuffer();
}

// ---------------- Setup ----------------
void setup()
{
    Serial.begin(115200);

    // Tắt Hardware Brownout Detector để chống reset khi đề xe máy gây sụt áp
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    // Cấu hình chân đo điện áp GPIO3 (BAT_ADC) ở chế độ Floating
    gpio_reset_pin((gpio_num_t)BAT_ADC);
    pinMode(BAT_ADC, INPUT);
    gpio_set_pull_mode((gpio_num_t)BAT_ADC, GPIO_FLOATING);
    gpio_pullup_dis((gpio_num_t)BAT_ADC);
    gpio_pulldown_dis((gpio_num_t)BAT_ADC);
    analogReadResolution(12);
    analogSetPinAttenuation(BAT_ADC, ADC_11db);

    // Khởi tạo I2C và U8g2 SH1106
    Wire.begin(OLED_SDA, OLED_SCL);
    u8g2.begin();
    u8g2.setBusClock(400000);
    Wire.setClock(400000);
    u8g2.setContrast(255);

    // 1. Kiểm tra bộ đếm Power-cycle 3 lần để vào Web Portal
    checkPowerCycleTrigger();

    // 2. Cài đặt nút BOOT (GPIO9): Nhấn giữ 3s để vào Web Portal
    pinMode(BOOT_BTN, INPUT_PULLUP);
    btnBoot.attachLongPressStart([]() {
        Serial.println("[Button] Nhan giu BOOT 3s -> Chuyen ve Factory Portal!");
        switchToFactoryPortal("NUT BOOT GIU 3S");
    });
    btnBoot.setPressMs(3000);

    // 3. Khởi tạo BLE NimBLE Server cho Sygic BLE HUD
    NimBLEDevice::init("ESP32 HUD");
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);

    pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    NimBLEService *pService = pServer->createService(SYGIC_SERVICE_UUID);

    // Characteristic Indicate
    pCharIndicate = pService->createCharacteristic(
        SYGIC_CHAR_INDICATE_UUID,
        NIMBLE_PROPERTY::INDICATE);

    // Characteristic Write
    pCharWrite = pService->createCharacteristic(
        SYGIC_CHAR_WRITE_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    pCharWrite->setCallbacks(new WriteCallbacks());

    pService->start();

    NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SYGIC_SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMaxPreferred(0x12);
    pAdvertising->start();

    Serial.println("[Sygic BLE] Da bat dau quang ba Service UUID: DD3F0AD1-6239-4E1F-81F1-91F6C9F01D86");
    bootTimestamp = millis();
}

// ---------------- Loop ----------------
void loop()
{
    btnBoot.tick();

    // Cập nhật lấy mẫu điện áp bình ắc quy xe
    updateBatteryVoltage();

    // Xóa bộ đếm Power-cycle sau khi máy chạy ổn định > 3 giây
    if (!powerCycleArmed && millis() - bootTimestamp > 3000)
    {
        powerCycleArmed = true;
        Preferences p;
        p.begin("bootasst", false);
        p.putUChar("pc_count", 0);
        p.end();
        Serial.println("[PowerCycle] May chay on dinh > 3s -> Da reset pc_count = 0");
    }

    // Nếu kết nối và không có dữ liệu mới sau 4 giây, gửi Indicate nhắc iPhone Sygic gửi tiếp
    if (deviceConnected)
    {
        uint32_t now = millis();
        if (now - lastActivityTime > 4000)
        {
            lastActivityTime = now;
            if (pCharIndicate)
            {
                pCharIndicate->indicate();
            }
        }
    }

    // Chu kỳ làm mới màn hình OLED (10 FPS = 100ms)
    static uint32_t lastRender = 0;
    if (millis() - lastRender > 100)
    {
        lastRender = millis();
        renderDisplay();
    }
}
