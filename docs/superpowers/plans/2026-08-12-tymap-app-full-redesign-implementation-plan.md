# Full App Redesign Implementation Plan (Cockpit Dark Glassmorphism)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign all screens in the TYMAP Android App (`MapFragment`, `SettingsFragment`, `NotificationsFragment`, `RenderFragment`) using the Cockpit Dark Glassmorphism design system.

**Architecture:** Apply cohesive `#090D16` deep space background, `#D9131B2E` glassmorphism card panels with `1px solid rgba(255,255,255,0.08)`, `#38BDF8` electric cyan navigation highlights, `#8B5CF6` cyber purple active accents, and an interactive ESP32 Live Mirror Widget.

**Tech Stack:** Android XML Layouts, Material Design 3 Cards, Vector Drawables, ConstraintLayout, CoordinatorLayout, Gradle (`./gradlew assembleDebug`).

---

### Task 1: Create Shared Glassmorphism Drawables

**Files:**
- Create: `TYMAP/app/src/main/res/drawable/bg_glass_card.xml`
- Create: `TYMAP/app/src/main/res/drawable/bg_glass_search.xml`
- Create: `TYMAP/app/src/main/res/drawable/bg_esp32_mirror_widget.xml`

- [ ] **Step 1: Create `bg_glass_card.xml`**

Create `TYMAP/app/src/main/res/drawable/bg_glass_card.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/colorSurfaceGlass" />
    <corners android:radius="18dp" />
    <stroke
        android:width="1dp"
        android:color="@color/colorSurfaceGlassBorder" />
</shape>
```

- [ ] **Step 2: Create `bg_glass_search.xml`**

Create `TYMAP/app/src/main/res/drawable/bg_glass_search.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#D91E293B" />
    <corners android:radius="24dp" />
    <stroke
        android:width="1dp"
        android:color="#4D38BDF8" />
</shape>
```

- [ ] **Step 3: Create `bg_esp32_mirror_widget.xml`**

Create `TYMAP/app/src/main/res/drawable/bg_esp32_mirror_widget.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <gradient
        android:angle="135"
        android:endColor="#090D16"
        android:startColor="#1E1B4B"
        android:type="radial"
        android:gradientRadius="100dp" />
    <stroke
        android:width="2dp"
        android:color="@color/colorAccentCyan" />
</shape>
```

- [ ] **Step 4: Verify build**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add TYMAP/app/src/main/res/drawable/bg_glass_card.xml TYMAP/app/src/main/res/drawable/bg_glass_search.xml TYMAP/app/src/main/res/drawable/bg_esp32_mirror_widget.xml
git commit -m "feat(ui): add glassmorphism card and search drawables"
```

---

### Task 2: Redesign Map & Navigation Screen (`fragment_map.xml` & `bottom_sheet_navigation.xml`)

**Files:**
- Modify: `TYMAP/app/src/main/res/layout/fragment_map.xml`
- Modify: `TYMAP/app/src/main/res/layout/bottom_sheet_navigation.xml`

- [ ] **Step 1: Redesign `fragment_map.xml`**

Update `searchCard`, `cardGpsSpeedometer`, and floating FABs in `TYMAP/app/src/main/res/layout/fragment_map.xml` to use Cockpit Glassmorphism drawables and styling.

- [ ] **Step 2: Redesign `bottom_sheet_navigation.xml`**

Update `TYMAP/app/src/main/res/layout/bottom_sheet_navigation.xml` to wrap turn guidance, street name, and action buttons ("Chỉ đường", "Bắt đầu") in Glassmorphic cards with rounded corners ($20\text{dp}$) and gradient accents.

- [ ] **Step 3: Verify build**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add TYMAP/app/src/main/res/layout/fragment_map.xml TYMAP/app/src/main/res/layout/bottom_sheet_navigation.xml
git commit -m "feat(ui): redesign MapFragment and navigation bottom sheet with Cockpit Glassmorphism"
```

---

### Task 3: Redesign Settings & BLE Connection Screens (`fragment_settings.xml` & `fragment_connection.xml`)

**Files:**
- Modify: `TYMAP/app/src/main/res/layout/fragment_settings.xml`
- Modify: `TYMAP/app/src/main/res/layout/fragment_connection.xml`

- [ ] **Step 1: Redesign `fragment_settings.xml`**

Update categories (Display, Map, Routing, Voice, Data Sending, System) in `TYMAP/app/src/main/res/layout/fragment_settings.xml` with dark glass cards (`@drawable/bg_glass_card`), cyan/purple section titles, and clean neon switches.

- [ ] **Step 2: Redesign `fragment_connection.xml`**

Update BLE connection status, paired devices list, and controls in `TYMAP/app/src/main/res/layout/fragment_connection.xml` with dark glass cards and live RSSI status badge styling.

- [ ] **Step 3: Verify build**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add TYMAP/app/src/main/res/layout/fragment_settings.xml TYMAP/app/src/main/res/layout/fragment_connection.xml
git commit -m "feat(ui): redesign SettingsFragment and ConnectionFragment with Cockpit Glassmorphism"
```

---

### Task 4: Redesign Notifications & Render Screens (`fragment_notifications.xml`, `item_notification_app.xml`, `fragment_render.xml`)

**Files:**
- Modify: `TYMAP/app/src/main/res/layout/fragment_notifications.xml`
- Modify: `TYMAP/app/src/main/res/layout/item_notification_app.xml`
- Modify: `TYMAP/app/src/main/res/layout/fragment_render.xml`

- [ ] **Step 1: Redesign `fragment_notifications.xml` & `item_notification_app.xml`**

Update notification forwarding cards, app list items, and toggle switches in `TYMAP/app/src/main/res/layout/fragment_notifications.xml` and `item_notification_app.xml` with dark glass cards (`@drawable/bg_glass_card`).

- [ ] **Step 2: Redesign `fragment_render.xml`**

Update render canvas preview, crop configuration, and color filter cards in `TYMAP/app/src/main/res/layout/fragment_render.xml` with Cockpit Dark styling.

- [ ] **Step 3: Verify build**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add TYMAP/app/src/main/res/layout/fragment_notifications.xml TYMAP/app/src/main/res/layout/item_notification_app.xml TYMAP/app/src/main/res/layout/fragment_render.xml
git commit -m "feat(ui): redesign NotificationsFragment and RenderFragment with Cockpit Glassmorphism"
```

---

### Task 5: Final Build & Complete Verification

- [ ] **Step 1: Execute complete debug build**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL (APK generated at `TYMAP/app/build/outputs/apk/debug/app-debug.apk`)

- [ ] **Step 2: Commit plan completion**

```bash
git commit --allow-empty -m "chore: complete full TYMAP Android App Cockpit Dark Glassmorphism redesign"
```
