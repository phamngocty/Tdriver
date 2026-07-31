# Modern Smartwatch Notification UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the notification interface on both ESP32-S3 firmware (`NOTIF_MODE`) and the Android companion app (`NotificationsFragment`) to deliver a modern smartwatch UI (Apple Watch / Galaxy Watch style).

**Architecture:** 
- ESP32-S3: Update `gui.h` / `gui.cpp` (`drawNOTIF()`) to feature dynamic app brand accent colors, pill badge header, glassmorphic message card, page dot indicator, and clear button action line.
- Android App: Update `fragment_notifications.xml`, `NotificationsFragment.kt`, and `item_notification_app.xml` to include notification listener permission status card, quick test simulator card, and sleek app forwarding manager.

**Tech Stack:** C++ / TFT_eSPI / LovyanGFX custom font rendering (ESP32-S3 GC9A01), Kotlin / Material 3 / ViewBinding / RecyclerView (Android).

---

### Task 1: ESP32-S3 Firmware `NOTIF_MODE` UI Redesign

**Files:**
- Modify: `firmware/esp32_s3_gc9a01/src/gui.h`
- Modify: `firmware/esp32_s3_gc9a01/src/gui.cpp`

- [ ] **Step 1: Declare `getAppAccentColor` helper in `gui.h`**

Add header declaration in `gui.h`:
```cpp
uint16_t getAppAccentColor(const String& appName);
```

- [ ] **Step 2: Implement `getAppAccentColor` and redesign `drawNOTIF()` in `gui.cpp`**

In `firmware/esp32_s3_gc9a01/src/gui.cpp`:
```cpp
uint16_t getAppAccentColor(const String& appName)
{
    String lower = appName;
    lower.toLowerCase();
    if (lower.indexOf("zalo") >= 0) return 0x1C9F;      // Bright Zalo Blue
    if (lower.indexOf("messenger") >= 0 || lower.indexOf("facebook") >= 0) return 0xD81F; // Purple/Magenta
    if (lower.indexOf("sms") >= 0 || lower.indexOf("tin nhắn") >= 0 || lower.indexOf("message") >= 0) return 0x07E0; // Green
    if (lower.indexOf("phone") >= 0 || lower.indexOf("call") >= 0 || lower.indexOf("cuộc gọi") >= 0) return 0xF800; // Red
    if (lower.indexOf("maps") >= 0 || lower.indexOf("bản đồ") >= 0) return 0x07FF; // Sky Blue
    return 0x07FF; // Default Cyan
}

void drawNOTIF()
{
    canvasSprite.fillSprite(TFT_BLACK);

    if (notifCount == 0)
    {
        // Draw Empty State Glass Card
        canvasSprite.fillRoundRect(20, 50, 200, 140, 16, 0x18E3);
        canvasSprite.drawRoundRect(20, 50, 200, 140, 16, 0x39E7);

        int cx = 120, cy = 105;
        canvasSprite.drawRect(cx - 18, cy - 14, 36, 26, 0x7BEF);
        canvasSprite.drawLine(cx - 18, cy - 14, cx, cy, 0x7BEF);
        canvasSprite.drawLine(cx + 18, cy - 14, cx, cy, 0x7BEF);

        String emptyStr = "Không có thông báo mới";
        myFont.set_font(FONT_STATUS_INFO);
        uint16_t emptyLen = myFont.getLength(emptyStr);
        myFont.print(120 - emptyLen / 2, 145, emptyStr, 0xBDF7, 0x18E3);
    }
    else
    {
        String appName = notifList[notifViewIndex].app;
        if (appName.length() == 0) appName = "Thông báo";
        uint16_t accent = getAppAccentColor(appName);

        // 1. Top App Pill Badge (y = 14)
        myFont.set_font(FONT_NOTIF_TITLE);
        String badgeText = appName;
        badgeText.toUpperCase();
        if (badgeText.length() > 12) badgeText = badgeText.substring(0, 10) + "..";
        uint16_t badgeTextLen = myFont.getLength(badgeText);
        int badgeW = badgeTextLen + 20;
        if (badgeW < 70) badgeW = 70;
        if (badgeW > 150) badgeW = 150;

        canvasSprite.fillRoundRect(120 - badgeW / 2, 12, badgeW, 22, 11, accent);
        myFont.print(120 - badgeTextLen / 2, 16, badgeText, TFT_BLACK, accent);

        // 2. Message Glass Container Card (y = 40..186)
        canvasSprite.fillRoundRect(15, 40, 210, 146, 16, 0x18E3);
        canvasSprite.drawRoundRect(15, 40, 210, 146, 16, accent);

        // 3. Notification Title
        String notifTitle = notifList[notifViewIndex].title;
        if (notifTitle.length() == 0) notifTitle = appName;
        myFont.set_font(FONT_NOTIF_TITLE);
        uint16_t titleLen = myFont.getLength(notifTitle);
        if (titleLen > 180)
        {
            notifTitle = notifTitle.substring(0, 20) + "...";
            titleLen = myFont.getLength(notifTitle);
        }
        myFont.print(120 - titleLen / 2, 48, notifTitle, TFT_WHITE, 0x18E3);

        // Separator Line inside card
        canvasSprite.fastHLine(25, 68, 190, accent);

        // 4. Notification Body Message (Wrapped text)
        printWrappedText(74, notifList[notifViewIndex].msg, 0xE79C, 0x18E3, 190);

        // 5. Page Dot Indicator (y = 194)
        int totalDots = notifCount;
        int dotSpacing = 14;
        int startX = 120 - ((totalDots - 1) * dotSpacing) / 2;
        for (int i = 0; i < totalDots; i++)
        {
            int dx = startX + i * dotSpacing;
            if (i == notifViewIndex)
            {
                canvasSprite.fillCircle(dx, 194, 4, accent);
                canvasSprite.drawCircle(dx, 194, 5, TFT_WHITE);
            }
            else
            {
                canvasSprite.fillCircle(dx, 194, 2, 0x5AD6);
            }
        }

        // 6. Action Button Guidance Footer (y = 212)
        String footerStr = isNotifPopupTransient ? "Bấm nút: Đóng" : "Bấm nút: Tiếp theo";
        myFont.set_font(FONT_HUD_INFO);
        uint16_t footerLen = myFont.getLength(footerStr);
        myFont.print(120 - footerLen / 2, 212, footerStr, 0x7BEF, TFT_BLACK);
    }

    canvasSprite.pushSprite(0, 0);
}
```

- [ ] **Step 3: Build & verify firmware compilation**

Command:
```powershell
pio run -e esp32_s3_gc9a01
```

---

### Task 2: Android App Tab Notifications UI Redesign

**Files:**
- Modify: `app/src/main/res/layout/fragment_notifications.xml`
- Modify: `app/src/main/res/layout/item_notification_app.xml`
- Modify: `app/src/main/java/com/example/tymap/ui/NotificationsFragment.kt`

- [ ] **Step 1: Redesign `fragment_notifications.xml` with Material 3 Cards**

Update `app/src/main/res/layout/fragment_notifications.xml` to include:
- Permission Status Card banner (Green/Red indicator + action button)
- Quick Test Simulator Card with App preset selector Chips & Material TextInputs
- Improved Allowed Apps section with RecyclerView

- [ ] **Step 2: Update `NotificationsFragment.kt` logic**

In `NotificationsFragment.kt`:
- Add check for `isNotificationServiceEnabled()` and update permission banner state.
- Handle permission button click to open `ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- Handle simulator chips to send test notifications with preset app tags.

- [ ] **Step 3: Build & verify Android project compilation**

Command:
```powershell
.\gradlew.bat assembleDebug
```

---

### Task 3: Final Verification & Walkthrough

- [ ] **Step 1: Run full verification build for firmware & app**
- [ ] **Step 2: Create Walkthrough summary artifact**
