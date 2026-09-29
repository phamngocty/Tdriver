#include "esp_ota_ops.h"
#include "esp_partition.h"
#include <Arduino.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <U8g2lib.h>
#include <Update.h>
#include <WebServer.h>
#include <WiFi.h>
#include <Wire.h>

// Hardware Pins
#ifndef OLED_SDA
#define OLED_SDA 6
#endif
#ifndef OLED_SCL
#define OLED_SCL 7
#endif

// Wi-Fi Access Point Config
static const char *AP_SSID = "TYMAP Factory Portal";
static const char *AP_PSK = "12345678";

// NVS Settings
static const char *NVS_NS = "tymap_cfg";
static const char *KEY_OS = "target_os"; // "android" | "ios"

// Web & DNS Server
static WebServer server(80);
static DNSServer dnsServer;

// OLED SH1106 128x64 I2C Hardware
U8G2_SH1106_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, /* reset=*/U8X8_PIN_NONE);

static uint32_t portalStartTime = 0;
static size_t otaWrittenBytes = 0;
static size_t otaTotalBytes = 0;
static const esp_partition_t *otaTargetPartition = nullptr;

// ---------------- Display Helpers ----------------
static void drawPortalOled(const char *status, const char *detail,
                           int timeoutSec = -1) {
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(0, 10, "=== TYMAP FACTORY ===");

  u8g2.drawStr(0, 24, "Wi-Fi: TYMAP Portal");
  u8g2.drawStr(0, 36, "Pass:  12345678");
  u8g2.drawStr(0, 48, "IP:    192.168.4.1");

  if (timeoutSec >= 0) {
    char tBuf[32];
    snprintf(tBuf, sizeof(tBuf), "Auto-boot: %ds", timeoutSec);
    u8g2.drawStr(0, 60, tBuf);
  } else if (status && strlen(status) > 0) {
    u8g2.drawStr(0, 60, status);
  }
  u8g2.sendBuffer();
}

static void drawUploadProgress(size_t written, size_t total) {
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_7x14_tf);
  u8g2.drawStr(10, 16, "DANG NAP OTA...");

  char pBuf[32];
  int percent = (total > 0) ? (written * 100 / total) : 0;
  snprintf(pBuf, sizeof(pBuf), "%d KB (%d%%)", (int)(written / 1024), percent);
  u8g2.drawStr(10, 36, pBuf);

  u8g2.drawFrame(10, 44, 108, 10);
  int barWidth = (percent * 104) / 100;
  if (barWidth > 0)
    u8g2.drawBox(12, 46, barWidth, 6);

  u8g2.sendBuffer();
}

// ---------------- OTA / Boot Partitions ----------------
static bool setBootPartitionByLabel(const char *label) {
  const esp_partition_t *part = esp_partition_find_first(
      ESP_PARTITION_TYPE_APP, ESP_PARTITION_SUBTYPE_ANY, label);
  if (!part) {
    Serial.printf("[OTA] Khong tim thay partition: %s\n", label);
    return false;
  }
  esp_err_t err = esp_ota_set_boot_partition(part);
  if (err == ESP_OK) {
    Serial.printf("[OTA] Da dat boot partition thanh cong: %s\n", label);
    return true;
  }
  Serial.printf("[OTA] Loi dat boot partition: 0x%X\n", err);
  return false;
}

static void rebootDevice(int delayMs = 300) {
  delay(delayMs);
  esp_restart();
}

// ---------------- Web Handlers ----------------
static String getModernHtml(const String &bodyContent,
                            const String &title = "TYMAP Factory Portal") {
  String html =
      F("<!DOCTYPE html><html lang='vi'><head><meta charset='UTF-8'>"
        "<meta name='viewport' content='width=device-width,initial-scale=1.0'>"
        "<title>");
  html += title;
  html += F(
      "</title><style>"
      ":root{--bg:#090d16;--card:#131b2e;--accent:#00e5ff;--accent2:#f59e0b;--"
      "text:#f8fafc;--muted:#94a3b8;--border:#1e293b;}"
      "*{box-sizing:border-box;margin:0;padding:0;font-family:-apple-system,"
      "BlinkMacSystemFont,Segoe UI,Roboto,sans-serif}"
      "body{background:var(--bg);color:var(--text);padding:16px;min-height:"
      "100vh;display:flex;flex-direction:column;align-items:center;}"
      ".wrap{width:100%;max-width:440px;margin:auto}"
      ".header{text-align:center;margin-bottom:20px}"
      ".logo{font-size:24px;font-weight:800;background:linear-gradient(135deg,"
      "var(--accent),#3b82f6);-webkit-background-clip:text;-webkit-text-fill-"
      "color:transparent;letter-spacing:1px}"
      ".sub{font-size:12px;color:var(--muted);margin-top:4px}"
      ".card{background:var(--card);border:1px solid "
      "var(--border);border-radius:20px;padding:20px;box-shadow:0 10px 30px "
      "rgba(0,0,0,0.5);margin-bottom:16px}"
      ".card-title{font-size:14px;font-weight:700;color:var(--accent);margin-"
      "bottom:12px;display:flex;align-items:center;gap:8px}"
      ".btn-os{display:flex;align-items:center;gap:12px;width:100%;padding:"
      "14px;margin-bottom:12px;background:#1e293b;border:2px solid "
      "transparent;border-radius:14px;color:var(--text);cursor:pointer;text-"
      "align:left;transition:all 0.2s}"
      ".btn-os:hover,.btn-os.active{border-color:var(--accent);background:#"
      "243248}"
      ".btn-os h3{font-size:15px;font-weight:700}"
      ".btn-os p{font-size:11px;color:var(--muted);margin-top:2px}"
      ".btn-submit{width:100%;padding:14px;background:linear-gradient(135deg,"
      "var(--accent),#2563eb);border:none;border-radius:14px;color:#000;font-"
      "size:15px;font-weight:800;cursor:pointer;box-shadow:0 4px 15px "
      "rgba(0,229,255,0.3)}"
      ".file-input{width:100%;padding:10px;background:#1e293b;border:1px solid "
      "var(--border);border-radius:12px;color:var(--text);margin-bottom:12px}"
      ".select{width:100%;padding:12px;background:#1e293b;border:1px solid "
      "var(--border);border-radius:12px;color:var(--text);margin-bottom:12px;"
      "font-size:14px}"
      ".btn-guide{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;padding:13px;background:rgba(0,229,255,0.08);border:1px solid var(--accent);border-radius:14px;color:var(--accent);text-decoration:none;font-size:13px;font-weight:700;transition:all 0.2s}"
      ".btn-guide:hover{background:rgba(0,229,255,0.2)}"
      ".footer{text-align:center;font-size:11px;color:var(--muted);margin-top:"
      "20px}"
      "</style></head><body><div class='wrap'><div class='header'><div "
      "class='logo'>⚡ TYMAP DUAL-BOOT</div>"
      "<div class='sub'>Hệ thống chuyển đổi Hệ điều hành ESP32-C3</div></div>");
  html += bodyContent;
  html += F("<div class='footer'><a href='https://tulamdientu.carrd.co/' "
            "target='_blank' style='color:var(--accent);text-decoration:none;"
            "font-weight:600'>📖 Hướng dẫn sử dụng: tulamdientu.carrd.co</a><br><br>"
            "TYMAP Hardware • TỰ LÀM ĐIỆN TỬ • Dual OS System</div></div></body></html>");
  return html;
}

static void handleRoot() {
  Preferences p;
  p.begin(NVS_NS, true);
  String currentOs = p.getString(KEY_OS, "android");
  p.end();

  String body = F("<div class='card'>"
                  "<div class='card-title'>⚙️ CHỌN HỆ ĐIỀU HÀNH KHỞI ĐỘNG</div>"
                  "<form action='/switch' method='POST'>"
                  "<label class='btn-os ");
  if (currentOs == "android")
    body += "active";
  body += F("'>"
            "<input type='radio' name='os' value='android' ");
  if (currentOs == "android")
    body += "checked";
  body += F(
      " style='display:none'>"
      "<div><h3>🤖 Android (TYMAP BLE)</h3>"
      "<p>Kết nối app Android TYMAP, dẫn đường bản đồ, đồng hồ xe máy</p></div>"
      "</label>"
      "<label class='btn-os ");
  if (currentOs == "ios")
    body += "active";
  body += F("'>"
            "<input type='radio' name='os' value='ios' ");
  if (currentOs == "ios")
    body += "checked";
  body +=
      F(" style='display:none'>"
        "<div><h3>🍎 iOS (Sygic BLE HUD)</h3>"
        "<p>Kết nối trực tiếp app Sygic trên iPhone qua Bluetooth BLE</p></div>"
        "</label>"
        "<button type='submit' class='btn-submit'>LƯU & KHỞI ĐỘNG LẠI</button>"
        "</form></div>"
        "<div class='card'>"
        "<div class='card-title'>📦 NẠP FIRMWARE OTA (.BIN)</div>"
        "<form action='/upload' method='POST' enctype='multipart/form-data'>"
        "<select name='partition' class='select'>"
        "<option value='ota_0'>Phân vùng ota_0 (Android TYMAP)</option>"
        "<option value='ota_1'>Phân vùng ota_1 (iOS Sygic BLE)</option>"
        "<option value='factory'>Phân vùng factory (Web Portal)</option>"
        "</select>"
        "<input type='file' name='update' accept='.bin' class='file-input' "
        "required>"
        "<button type='submit' class='btn-submit' "
        "style='background:#f59e0b'>TIẾN HÀNH NẠP FIRMWARE</button>"
        "</form></div>"
        "<div class='card' style='text-align:center'>"
        "<div class='card-title' style='justify-content:center'>📖 HƯỚNG DẪN SỬ DỤNG</div>"
        "<p style='font-size:12px;color:var(--muted);margin-bottom:12px'>Xem sơ đồ đấu nối 2 dây xe máy, kết nối Android & mở khóa Sygic iOS</p>"
        "<a href='https://tulamdientu.carrd.co/' target='_blank' class='btn-guide'>👉 XEM HƯỚNG DẪN TẠI TỰ LÀM ĐIỆN TỬ</a>"
        "</div>");

  server.send(200, "text/html", getModernHtml(body));
}

static void handleSwitch() {
  String os = server.arg("os");
  if (os != "android" && os != "ios")
    os = "android";

  Preferences p;
  p.begin(NVS_NS, false);
  p.putString(KEY_OS, os);
  p.end();

  const char *label = (os == "android") ? "ota_0" : "ota_1";
  bool ok = setBootPartitionByLabel(label);

  String msg = "<div class='card' style='text-align:center;padding:30px 20px'>"
               "<div style='font-size:40px;margin-bottom:12px'>" +
               String(ok ? "✅" : "❌") +
               "</div>"
               "<h2>" +
               String(ok ? "CHUYỂN ĐỔI THÀNH CÔNG!" : "LỖI PHÂN VÙNG!") +
               "</h2>"
               "<p style='color:var(--muted);margin:10px 0 20px'>Hệ điều hành "
               "đã chọn: <b>" +
               (os == "android" ? "Android (TYMAP)" : "iOS (Sygic BLE)") +
               "</b></p>"
               "<p style='color:var(--accent)'>ESP32-C3 đang tự khởi động "
               "lại...</p></div>";

  server.send(200, "text/html", getModernHtml(msg, "Đang khởi động lại..."));

  drawPortalOled("REBOOTING...", label);
  rebootDevice(800);
}

// Xử lý Upload file OTA trực tiếp
static void handleUploadFile() {
  HTTPUpload &upload = server.upload();

  if (upload.status == UPLOAD_FILE_START) {
    String targetPartName = server.arg("partition");
    if (targetPartName.length() == 0)
      targetPartName = "ota_0";

    Serial.printf("[OTA Upload] Bat dau nạp vao: %s (File: %s)\n",
                  targetPartName.c_str(), upload.filename.c_str());
    otaTargetPartition = esp_partition_find_first(ESP_PARTITION_TYPE_APP,
                                                  ESP_PARTITION_SUBTYPE_ANY,
                                                  targetPartName.c_str());

    if (!otaTargetPartition) {
      Serial.println("[OTA Upload] Khong tim thay partition muc tieu!");
      return;
    }

    otaWrittenBytes = 0;
    otaTotalBytes = 0;
    drawUploadProgress(0, 100);
  } else if (upload.status == UPLOAD_FILE_WRITE) {
    if (otaTargetPartition) {
      if (otaWrittenBytes == 0) {
        // Erase partition trước khi ghi
        esp_partition_erase_range(otaTargetPartition, 0,
                                  otaTargetPartition->size);
      }

      esp_partition_write(otaTargetPartition, otaWrittenBytes, upload.buf,
                          upload.currentSize);
      otaWrittenBytes += upload.currentSize;
      otaTotalBytes += upload.currentSize;

      if (otaWrittenBytes % 8192 == 0) {
        drawUploadProgress(otaWrittenBytes, otaTotalBytes + 1024);
      }
    }
  } else if (upload.status == UPLOAD_FILE_END) {
    if (otaTargetPartition) {
      Serial.printf("[OTA Upload] Hoan tat! Tong byte: %d\n", otaWrittenBytes);
      esp_ota_set_boot_partition(otaTargetPartition);
      drawPortalOled("NAP OTA XONG!", "Rebooting...");
    }
  } else if (upload.status == UPLOAD_FILE_ABORTED) {
    Serial.println("[OTA Upload] Bi huy!");
    otaTargetPartition = nullptr;
  }
}

static void handleUploadResult() {
  if (otaTargetPartition) {
    String msg =
        "<div class='card' style='text-align:center;padding:30px 20px'>"
        "<div style='font-size:40px;margin-bottom:12px'>🎉</div>"
        "<h2>NẠP FIRMWARE THÀNH CÔNG!</h2>"
        "<p style='color:var(--muted);margin:10px 0 20px'>Đã nạp hoàn tất file "
        ".bin vào bộ nhớ Flash.</p>"
        "<p style='color:var(--accent)'>Đang khởi động lại ESP32...</p></div>";
    server.send(200, "text/html", getModernHtml(msg, "Nạp thành công!"));
    rebootDevice(1000);
  } else {
    String msg =
        "<div class='card' style='text-align:center;padding:30px 20px'>"
        "<div style='font-size:40px;margin-bottom:12px'>❌</div>"
        "<h2>NẠP THẤT BẠI!</h2>"
        "<p style='color:var(--muted);margin:10px 0 20px'>Đã xảy ra lỗi khi "
        "ghi dữ liệu vào Flash.</p>"
        "<a href='/' class='btn-submit' "
        "style='display:inline-block;text-decoration:none'>Thử lại</a></div>";
    server.send(500, "text/html", getModernHtml(msg, "Lỗi nạp!"));
  }
}

// ---------------- Setup & Loop ----------------
void setup() {
  Serial.begin(115200);
  Wire.begin(OLED_SDA, OLED_SCL);

  u8g2.begin();
  u8g2.setContrast(255);

  // Xóa bộ đếm Power-cycle khi đã vào Factory
  Preferences p;
  p.begin("bootasst", false);
  p.putUChar("pc_count", 0);
  p.end();

  // Khởi tạo Wi-Fi SoftAP
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PSK);
  dnsServer.start(53, "*", WiFi.softAPIP());

  drawPortalOled("READY", AP_SSID, 60);

  // Web Server Routes
  server.on("/", HTTP_GET, handleRoot);
  server.on("/switch", HTTP_POST, handleSwitch);
  server.on("/upload", HTTP_POST, handleUploadResult, handleUploadFile);
  server.onNotFound(handleRoot); // Captive Portal redirect
  server.begin();

  portalStartTime = millis();
  Serial.println("[TYMAP Factory] Portal khoi dong thanh cong!");
}

void loop() {
  dnsServer.processNextRequest();
  server.handleClient();

  // Tự động boot vào OS đã chọn nếu người dùng không thao tác sau 60 giây
  uint32_t elapsed = (millis() - portalStartTime) / 1000;
  if (elapsed <= 60) {
    static uint32_t lastSec = 999;
    uint32_t remain = 60 - elapsed;
    if (remain != lastSec) {
      lastSec = remain;
      drawPortalOled("READY", AP_SSID, remain);
    }
  } else {
    // Hết thời gian chờ 60s -> Boot vào OS mặc định
    Preferences p;
    p.begin(NVS_NS, true);
    String savedOs = p.getString(KEY_OS, "android");
    p.end();

    const char *label = (savedOs == "ios") ? "ota_1" : "ota_0";
    drawPortalOled("TIMEOUT 60s", label);
    if (setBootPartitionByLabel(label)) {
      rebootDevice(500);
    }
    portalStartTime = millis(); // Reset nếu thất bại
  }
}
