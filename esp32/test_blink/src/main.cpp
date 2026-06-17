/*
 * Test blink đơn giản — kiểm tra board có chạy được không
 * Board: ESP32-S3-N16R16
 * Chân: GPIO21 (LED trạng thái)
 */

#include <Arduino.h>

void setup()
{
    Serial.begin(115200);
    pinMode(21, OUTPUT);
    Serial.println("Blink test starting...");
}

void loop()
{
    digitalWrite(21, HIGH);
    delay(500);
    digitalWrite(21, LOW);
    delay(500);
}
