/*
 * BLINK + SERIAL — test tối thiểu, không dùng TFT
 */

#include <Arduino.h>

#define PIN_LED_STATUS GPIO_NUM_21
#define PIN_BL GPIO_NUM_2

// ===================================================================
// Setup
// ===================================================================
void setup()
{
    pinMode(PIN_LED_STATUS, OUTPUT);
    pinMode(PIN_BL, OUTPUT);

    // Nháy LED + Backlight ngay — chứng tỏ firmware chạy
    digitalWrite(PIN_LED_STATUS, HIGH);
    digitalWrite(PIN_BL, HIGH);
    delay(200);
    digitalWrite(PIN_LED_STATUS, LOW);
    digitalWrite(PIN_BL, LOW);
    delay(200);
    digitalWrite(PIN_LED_STATUS, HIGH);
    digitalWrite(PIN_BL, HIGH);

    Serial.begin(115200);
    delay(1500);

    Serial.println("\n*** BLINK TEST ***");
    Serial.println("setup() OK — board ESP32-S3 running");
    Serial.flush();
}

// ===================================================================
// Loop
// ===================================================================
void loop()
{
    static int count = 0;
    count++;

    Serial.printf("Loop #%d\n", count);

    digitalWrite(PIN_LED_STATUS, !digitalRead(PIN_LED_STATUS));
    digitalWrite(PIN_BL, !digitalRead(PIN_BL));
    delay(500);
}
