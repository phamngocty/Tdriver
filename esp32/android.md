You are an expert Android developer. Build the complete Android app for a smart motorcycle navigation system. The app pairs with an ESP32‑S3 device via BLE to provide a secondary display, while itself offering a full Google‑Maps‑like experience for searching, route selection, and live navigation. Use only free APIs (OSRM, OpenStreetMap tiles, Nominatim, Open‑Meteo, and optionally GraphHopper with a free API key). Write everything in Kotlin.

## 1. SYSTEM CONTEXT

The phone app:

- Connects to an ESP32‑S3 over BLE and sends navigation HUD data, speed, time, weather, and a 240×240 JPEG map image (when requested by the ESP32).
- Reads Google Maps notifications to optionally provide better HUD data.
- Receives control commands from the ESP32 (mode changes, zoom requests).
- Works even when the phone screen is off or the app is in the background (via a Foreground Service).

## 2. BLE GATT PROFILE (FIXED)

All characteristics belong to a single custom service. Use these exact UUIDs and formats:

| Characteristic   | UUID                                 | Direction | Format & Behavior                                                                                                                        |
| ---------------- | ------------------------------------ | --------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| CHA_NAV          | 0b11deef-1563-447f-aece-d3dfeb1c1f20 | App → ESP | Text: `key=value` per line, separated by `\n`. (e.g. `nextRd=Main St\ndistToNext=200m\neta=14:30`)                                       |
| CHA_NAV_TBT_ICON | d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad | App → ESP | 1 byte (0–20) representing a maneuver type. App maps OSRM/GraphHopper maneuver to an index.                                              |
| CHA_GPS_SPEED    | 98b6073a-5cf3-4e73-b6d3-f8e05fa018a9 | App → ESP | Text: integer km/h as string (e.g. "60")                                                                                                 |
| CHA_SETTINGS     | 9d37a346-63d3-4df6-8eee-f0242949f59f | App → ESP | Text: key=value lines (brightness, theme, speedLimit). Sent once on connection.                                                          |
| CHA_TIME         | a1b2c3d4-e5f6-4789-a012-3456789abcde | App → ESP | 4 bytes little‑endian uint32 (Unix timestamp). Sent on connect & every 5 min.                                                            |
| CHA_WEATHER      | b2c3d4e5-f6a7-4890-b123-456789abcdef | App → ESP | JSON: `{"t":30,"i":"01d"}` (temperature in Celsius, weather icon code). Sent every 10 min.                                               |
| CHA_MAP_IMAGE    | c3d4e5f6-a7b8-4901-c234-567890abcdef | App → ESP | First 4 bytes: little‑endian uint32 JPEG size, then JPEG byte stream. Sent only when ESP32 Map mode active.                              |
| CHA_DEVICE_CTRL  | d4e5f6a7-b8c9-4012-d345-678901bcdef0 | ESP → App | 1 byte bitfield: bit0=zoom in, bit1=zoom out, bit2=Map mode on/off, bit3=request immediate map refresh. App subscribes to notifications. |

## 3. APP ARCHITECTURE

- Single Activity (`MainActivity`) with `ViewPager2` + `BottomNavigationView` (3 tabs).
- Fragments: `ConnectionFragment`, `MapFragment`, `SettingsFragment`.
- Foreground Service: `NavigationService` (runs even when app is backgrounded).
- A singleton `NavigationRepository` holds `SharedFlow` / `StateFlow` for real‑time data (location, route polyline, current step, etc.).
- `ShareReceiverActivity` handles shared destinations from Google Maps.
- `NotificationListenerService` (`GMapsNotificationListener`) parses Google Maps notifications.

## 4. DEPENDENCIES

- org.osmdroid:osmdroid-android
- no.nordicsemi:ble (Nordic BLE) or custom BLE manager
- okhttp3, gson
- androidx.security:security-crypto (for EncryptedSharedPreferences)
- Material Design, ViewPager2, ViewBinding
- Kotlin Coroutines, lifecycle-runtime-ktx

## 5. TAB 1 – CONNECTION & API KEYS (ConnectionFragment)

- BLE scanning: scan and display **all nearby devices** (no name filter). On device selected, connect and save MAC to SharedPreferences.
- Connection status indicator (green/red), device name.
- Disconnect button.
- Log RecyclerView (timestamp + event). Log can be cleared.
- **API Keys section**:
  - EditText for OpenRouteService key, EditText for **GraphHopper key**.
  - "Test" button next to each key: call a minimal API to verify validity; show Toast result.
  - "Register" button opens browser to the respective registration page (GraphHopper: https://www.graphhopper.com/).
  - Keys saved to EncryptedSharedPreferences.

## 6. TAB 2 – MAP (MapFragment) – FULL GOOGLE MAPS‑LIKE EXPERIENCE

This tab should look and behave like Google Maps as much as possible.

### 6.1. Map View

- OSMdroid `MapView` with a configurable tile source (default CartoDB Positron).
- Full gesture support: pan, pinch‑zoom, rotate.
- Compass appears when rotated; tap to reset north.

### 6.2. Floating Buttons

- **My Location** (top‑right): tap to center on GPS. In navigation, tap to toggle between “follow with bearing” (button turns blue) and free mode.
- **Layers** (below My Location): cycles through enabled tile sources in this order: CartoDB → OSM Mapnik → Custom (MapCN) if a custom tile URL has been configured in Settings. If no custom URL is set, skip it.
- **Zoom ±** (bottom‑right; can be hidden if gesture zoom is used).
- **Share** button (appears when a place is selected) – shares a geo‑URL.

### 6.3. Search Bar

- Floating `CardView` at the top, placeholder “Search here”.
- On typing, call Nominatim (`https://nominatim.openstreetmap.org/search?q=<query>&format=json&limit=5`). Show suggestions in a dropdown below the bar.
- On suggestion select: place a red marker, move camera, show a small info bubble, and display the **Place Bottom Sheet**.

### 6.4. Place Bottom Sheet (pre‑navigation)

- Slides up from the bottom (collapsed state shows place name, address, “Directions” button, “Start” button).
- **“Directions”**: fetches routes from the selected routing engine (see Settings). Draws **one optimal route in dark blue**, and **1–2 alternative routes in lighter colors** (if returned). User can **tap on an alternative route** to select it; the sheet updates with new trip info, and the selected route becomes the primary one (just like Google Maps).
- **“Start”**: launches `NavigationService` with destination coordinates and the selected route; the bottom sheet transforms into the **Navigation Bottom Sheet**.

### 6.5. Navigation Bottom Sheet (during navigation)

- Replaces the Place Bottom Sheet automatically when navigation starts.
- **Header**: turn icon (left), “Turn left onto Street Name”, “in 200 m”, mute button (toggle voice), close button.
- Pull up to see full trip info: ETA, remaining time, total distance, and an **End** button (red).
- **Behavior**:
  - When user pans the map, the sheet collapses to a thin header, and a **“Recenter”** button appears at bottom‑right.
  - Tapping “Recenter” resumes follow‑mode and expands the sheet.
  - On arrival, the sheet shows “You have arrived” and stops navigation.

### 6.6. Real‑Time Navigation on Map

- When `NavigationService` is running and the user opens the app (or is already on MapFragment), the map displays:
  - Vehicle marker (arrow rotated by bearing) moving smoothly.
  - Selected route polyline.
  - Destination marker.
  - Camera follows the vehicle with bearing (unless user pans away).

### 6.7. Speed Warning

- If enabled in Settings and GPS speed exceeds the set threshold (default 60 km/h), show a red speedometer icon at bottom‑left and vibrate briefly.

### 6.8. Night Mode

- When system dark theme is active (or Settings set to Dark), switch to a dark tile source (e.g., CartoDB Dark Matter). Adjust UI colors accordingly.

### 6.9. Share Handling

- `ShareReceiverActivity` receives `ACTION_SEND` with `text/plain` from Google Maps. It parses the URL (expanding short links if needed), extracts destination lat/lng, and passes it to `MapFragment` to show the Place Bottom Sheet.

## 7. TAB 3 – SETTINGS (SettingsFragment)

All settings are stored in SharedPreferences (plain, except API keys which are encrypted). Add the following options:

- **HUD Data Source** (priority for sending navigation text to ESP32):
  - “Auto” (default): use Google Maps notification if available, otherwise fallback to OSRM steps.
  - “Google Maps only”
  - “OSRM only”
- **Keep screen on during background navigation**: Toggle (default off). When on, if the app's MapFragment is visible, the screen will stay on.
- **Speed warning**: Toggle + threshold input (km/h, default 60).
- **Routing**:
  - Preferred engine: dropdown (OSRM demo, OpenRouteService, **GraphHopper**). The system will **automatically fallback** to the next available engine if the preferred one fails.
  - Off‑route detection distance (meters, default 20).
- **Map**:
  - **Tile source**: dropdown with:
    - “CartoDB Positron” (default)
    - “OSM Mapnik”
    - “Custom (MapCN)” – when this is selected, show an additional EditText labeled “Tile URL template”. The URL must contain `{z}`, `{x}`, `{y}` placeholders (e.g. `https://your-server.com/tiles/{z}/{x}/{y}.png`).
  - Default zoom level: slider 10–18.
  - Map image send FPS (1 or 2).
  - Movement threshold (pixels) before sending new map image.
  - **JPEG quality for ESP32**: slider 50% – 100% (default 70%).
- **Voice Guidance**:
  - Enable/disable.
  - Language: default is device locale; allow selecting from a list (e.g., English, Vietnamese, etc.).
- **Display**: Theme (Light/Dark/System default), Units (km/m).

## 8. NAVIGATIONSERVICE (FOREGROUND)

This is a foreground service with a persistent notification (“Navigation running…”, with a “Stop” action that stops the service).

### 8.1. BleManager

- Connect to saved MAC address. **Auto‑reconnect** on disconnection (retry every 5 seconds indefinitely).
- When connected, write initial settings (CHA_SETTINGS), start time sync.
- Write methods for each characteristic.
- For `writeMapImage`: send 4‑byte size header (little‑endian), then JPEG data in chunks of (MTU‑3) bytes using Write Without Response.
- Listen for CHA_DEVICE_CTRL notifications: parse bitfield, control `MapRenderer` (enable/disable, zoom) and update internal state.

### 8.2. GpsManager

- Use `LocationManager.requestLocationUpdates` with interval ~1 second, minimum distance 5 meters.
- Expose current `Location` via Flow.

### 8.3. RoutingEngine

- **API call**: Choose the active engine based on Settings (“preferred engine”). Implement three connectors:
  - **OSRM demo**: `GET https://router.project-osrm.org/route/v1/driving/{lng1},{lat1};{lng2},{lat2}?steps=true&geometries=polyline&overview=full&alternatives=true`
  - **OpenRouteService**: `POST https://api.openrouteservice.org/v2/directions/driving-car` with JSON body and Authorization header containing the ORS key.
  - **GraphHopper**: `GET https://graphhopper.com/api/1/route?point={lat1},{lng1}&point={lat2},{lng2}&vehicle=car&locale=en&key={GRAPHOPPER_KEY}&steps=true&points_encoded=true&algorithm=alternative_route` (or use `alternative_route.max_paths=3`). Parse the JSON response (path points, instructions).
- **Fallback logic**: If the preferred engine fails (network error, invalid key, no routes), automatically try the next available engine (order: OSRM demo → ORS → GraphHopper, or as configured). The app should prefer engines with valid keys.
- **Route selection**: store all returned routes (main + alternatives). The first route is the optimal one. User can select an alternative (via MapFragment), which updates the active route.
- **Steps & HUD**:
  - For the active route, extract steps (distance, duration, name, maneuver type). Map maneuver to icon index (0–20).
  - Determine current step based on traveled distance.
  - **HUD data source logic** (based on Settings):
    - “Auto”: listen for broadcast from `GMapsNotificationListener`. If a valid notification is received within the last 3 seconds, use its data; otherwise fallback to the current step from the routing engine.
    - “Google Maps only”: only send HUD if notification data is available.
    - “OSRM only”: always use the routing engine's step (regardless of engine, not just OSRM).
  - Format HUD string: `nextRd=<street>\ndistToNext=<distance>m\neta=<HH:MM>\nete=<minutes>\ntotalDist=<km>`. Send via CHA_NAV every 2s or when step changes. Send icon index when step changes.
- **Off‑route detection**: decode active polyline, compute distance from current GPS to nearest point; if > threshold, re‑route with current position using the same engine.

### 8.4. MapRenderer (hidden, generates 240×240 JPEG for ESP32)

- **Activation**: only when CHA_DEVICE_CTRL bit2 = 1.
- Create a headless `MapView` (240×240 px) programmatically. Use `measure()` and `layout()` to allow drawing.
- Set tile source according to Settings (CartoDB, OSM, or the custom URL template). If a custom URL is provided, create an `OnlineTileSourceBase` with that URL.
- Zoom level from Settings (adjusted on zoom commands).
- Draw overlays: active route polyline, vehicle marker (arrow rotated by bearing), destination marker.
- **Capture**: draw to a `Bitmap` (240×240, RGB_565), compress to JPEG with the quality defined in Settings (50‑100%).
- Send via `writeMapImage()`.
- **Rate control**: respect max FPS setting; only send if vehicle has moved more than the configured pixel threshold since last send, but send at least once every 5 seconds if stationary.
- When deactivated, stop sending and release resources.

### 8.5. WeatherProvider

- Every 10 minutes, fetch from Open‑Meteo: `https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&current_weather=true`. Extract temperature and weathercode. Map code to icon string (e.g., 0→”01d”). Send JSON via CHA_WEATHER.

### 8.6. TimeSync

- Send Unix timestamp (System.currentTimeMillis()/1000) on BLE connect and every 5 minutes.

### 8.7. TextToSpeechManager

- Initialize with language selected in Settings (default device locale).
- When distance to next turn drops below 100m and again below 30m, speak: “In <distance>, <maneuver> onto <street>”.

## 9. NOTIFICATIONLISTENERSERVICE (GMapsNotificationListener)

- Extend `NotificationListenerService`. Filter notifications from `com.google.android.apps.maps`.
- Parse the notification extras to extract: next road name, distance, ETA, icon (if possible). Broadcast this data with an action like `ACTION_GMAPS_NOTIFICATION`. The NavigationService receives it to update HUD (if in Auto/Google‑only mode).

## 10. SHARERECEIVERACTIVITY

- Handles `ACTION_SEND` of `text/plain`.
- Parse Google Maps URL (short or long) to get destination coordinates. Start MainActivity with extras to trigger MapFragment's Place Bottom Sheet.

## 11. COMMUNICATION: NavigationRepository

- Singleton object holding:
  - `gpsLocation: SharedFlow<Location>`
  - `routes: StateFlow<List<RouteInfo>>` (each RouteInfo has polyline, distance, duration, isSelected)
  - `currentStep: SharedFlow<StepInfo>`
  - `navigationState: StateFlow<Boolean>` (running/stopped)
- Service updates flows; fragments collect them to update UI.

## 12. PERMISSIONS & MANIFEST

- Declare: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (optional), `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `BIND_NOTIFICATION_LISTENER_SERVICE`.
- Request runtime permissions for Location, Bluetooth, and Notification Listener.

## 13. CODE QUALITY REQUIREMENTS

- Write complete, compilable Kotlin code with clear comments.
- Use ViewBinding.
- Follow Material Design guidelines; support dark mode throughout.
- Handle edge cases: BLE disconnection, GPS loss, API failures, empty notifications.
- Ensure smooth performance and no memory leaks (especially in MapRenderer).

Start by creating the project structure, then implement each file. Output a ready‑to‑compile Android Studio project.
