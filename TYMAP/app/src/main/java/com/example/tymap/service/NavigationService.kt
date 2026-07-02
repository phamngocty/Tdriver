package com.example.tymap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import androidx.core.app.NotificationCompat
import com.example.tymap.MainActivity
import com.example.tymap.R
import com.example.tymap.ble.MyBleManager
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.repository.StepInfo
import com.example.tymap.model.OledFilter
import com.example.tymap.utils.PrefsHelper
import com.google.gson.reflect.TypeToken
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import java.io.ByteArrayOutputStream
import java.util.Locale
import android.app.usage.UsageStatsManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.example.tymap.utils.ScreenCaptureManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.zip.CRC32
import kotlinx.coroutines.flow.collectLatest

class NavigationService : Service() {
    companion object {
        private var instance: NavigationService? = null
        val bleManager: MyBleManager? get() = instance?.bleManager
        val screenCaptureManager: ScreenCaptureManager? get() = instance?.screenCaptureManager
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var bleManager: MyBleManager
    private lateinit var gpsManager: GpsManager
    private lateinit var ttsManager: TtsManager
    private val httpClient = OkHttpClient()
    private val gson = GsonBuilder().setStrictness(Strictness.LENIENT).create()
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "NavigationChannel"

    // Icon Hash Cache to avoid re-sending same icon data
    private var lastSentIconHash: Long = -1

    private var headlessMapView: MapView? = null
    private var isMapModeActive = false
    private var isGmapsActive = false
    private var screenCaptureManager: ScreenCaptureManager? = null
    private var mediaProjection: MediaProjection? = null
    private var lastMapUpdateLocation: android.location.Location? = null
    private var lastMapUpdateTime: Long = 0
    
    // Tracking for Turn Screenshots
    private var lastTriggeredStepIndex: Int = -1
    private var isPopupActive: Boolean = false
    private var lastPopupTriggerDist: Int = -1 // 500 or 200

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || 
                             status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                if (bleManager.isConnected) {
                    bleManager.writePhoneBattery(batteryPct, isCharging)
                }
            }
        }
    }

    private val gmapsHudReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val active = intent?.getBooleanExtra("active", true) ?: true
            if (!active) {
                handleGmapsStop()
                return
            }

            val distance = intent?.getStringExtra("distance") ?: ""
            val instruction = intent?.getStringExtra("instruction") ?: ""
            val roadName = intent?.getStringExtra("roadName") ?: ""
            val eta = intent?.getStringExtra("eta") ?: ""
            val ete = intent?.getStringExtra("ete") ?: ""
            val iconIndex = intent?.getIntExtra("iconIndex", 0) ?: 0
            val icon1bpp = intent?.getByteArrayExtra("icon1bpp")
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("bitmap", android.graphics.Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("bitmap")
            }
            
            handleGmapsUpdate(distance, instruction, roadName, eta, ete, iconIndex, icon1bpp, bitmap)
        }
    }

    private fun handleGmapsStop() {
        isGmapsActive = false
        android.util.Log.d("NavigationService", "Google Maps navigation stopped, resetting HUD mode")
        NavigationRepository.updateHudPreview(null)
        lastSentIconHash = -1

        if (bleManager.isConnected) {
            // 1. Send navigation stopped state to ESP32
            val bleData = "active=0\nnav=0\ndist=\ntitle=\ndir=\neta="
            bleManager.writeNavigationData(bleData)

            // 2. Command ESP32 to switch to STATUS_MODE (0x12)
            bleManager.sendRemoteCommand(0x12.toByte())
        }
    }

    private fun handleGmapsUpdate(
        distance: String, 
        instruction: String, 
        roadName: String, 
        eta: String, 
        ete: String,
        iconIndex: Int, 
        icon1bpp: ByteArray?, 
        bitmap: android.graphics.Bitmap?
    ) {
        val iconHash = icon1bpp?.let { calculateCRC32(it) } ?: -1L
        
        val captureMode = PrefsHelper.getInt(this, "map_capture_mode", 0)
        
        // Tự động chuyển đổi chế độ màn hình khi Google Maps bắt đầu dẫn đường
        if (!isGmapsActive) {
            isGmapsActive = true
            if (bleManager.isConnected) {
                if (captureMode == 1) {
                    android.util.Log.d("NavigationService", "Google Maps started, switching ESP32 to MAP_MODE")
                    bleManager.sendRemoteCommand(0x11.toByte())
                } else if (captureMode == 2) {
                    android.util.Log.d("NavigationService", "Google Maps started, switching ESP32 to HUD_MODE")
                    bleManager.sendRemoteCommand(0x10.toByte())
                }
            }
        }

        if (captureMode == 1) {
            if (!isMapModeActive && bleManager.isConnected) {
                android.util.Log.d("NavigationService", "Google Maps Continuous active, switching ESP32 to MAP_MODE")
                bleManager.sendRemoteCommand(0x11.toByte())
            }
        } else if (captureMode == 2) {
            if (isMapModeActive && bleManager.isConnected) {
                android.util.Log.d("NavigationService", "Google Maps Turn Screenshot active, switching ESP32 to HUD_MODE")
                bleManager.sendRemoteCommand(0x10.toByte())
            }
        }

        // Parse distance to meters for popup trigger
        val distInMeters = parseDistance(distance)
        checkAndTriggerPopup(distInMeters)

        NavigationRepository.updateHudPreview(NavigationRepository.HudData(
            active = true,
            isNavigation = true,
            hasIcon = icon1bpp != null,
            distance = distance,
            eta = eta,
            duration = ete,
            title = instruction, 
            directions = roadName,
            icon1bpp = icon1bpp,
            bitmapIcon = bitmap
        ))

        // 1. Send Navigation Text Data
        val bleData = "active=1\nnav=1\ndist=$distance\ntitle=$instruction\ndir=$roadName\neta=$eta\nete=$ete"
        bleManager.writeNavigationData(bleData)
        
        // 2. Send Icon Data (Bitmap only, no index fallback)
        if (icon1bpp != null) {
            // Priority: Bitmap/Hash Icon
            if (iconHash != lastSentIconHash) {
                lastSentIconHash = iconHash
                val hashHex = String.format("%08X", iconHash)
                NavigationRepository.addLog("BLE: Sending icon hash = $hashHex")
                bleManager.writeNavIconHash(hashHex)
            } else {
                NavigationRepository.addLog("BLE: Icon hash matches lastSentIconHash ($iconHash), skip sending hash")
            }
        } else {
            NavigationRepository.addLog("BLE: No icon1bpp available, clearing lastSentIconHash")
            // If no bitmap available from notification, clear last hash
            lastSentIconHash = -1
        }
    }

    private fun calculateCRC32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    private fun parseDistance(distStr: String): Int {
        return try {
            val clean = distStr.lowercase().replace(",", ".")
            if (clean.contains("km")) {
                (clean.replace("km", "").trim().toFloat() * 1000).toInt()
            } else {
                clean.replace("m", "").trim().toInt()
            }
        } catch (e: Exception) { -1 }
    }

    private fun checkAndTriggerPopup(distMeters: Int) {
        if (distMeters <= 0) return
        val captureMode = PrefsHelper.getInt(this, "map_capture_mode", 0)
        // Chỉ kích hoạt khi bật chế độ "Google Maps Turn Screenshot" (captureMode == 2)
        if (captureMode != 2) return

        // Read dynamic trigger distances from slider settings
        val trigger1 = PrefsHelper.getInt(this, "popup_trigger_1", 500)
        val trigger2 = PrefsHelper.getInt(this, "popup_trigger_2", 200)
        val triggers = listOf(trigger1, trigger2)

        var matchedTrigger = -1
        for (t in triggers) {
            if (distMeters in (t - 10)..(t + 10)) {
                matchedTrigger = t
                break
            }
        }

        if (matchedTrigger != -1 && matchedTrigger != lastPopupTriggerDist) {
            lastPopupTriggerDist = matchedTrigger
            serviceScope.launch(Dispatchers.IO) {
                if (isGoogleMapsForeground()) {
                    val quality = PrefsHelper.getFloat(this@NavigationService, "jpeg_quality", 70f).toInt()
                    
                    val jpeg = screenCaptureManager?.captureAndProcess(quality, "gmaps_")
                    if (jpeg != null) {
                        val deviceDisplay = NavigationRepository.deviceStatus.value["display"] ?: "GC9A01"
                        val isOled = deviceDisplay.contains("OLED")
                        
                        if (isOled) {
                            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            if (bitmap != null) {
                                bleManager.writeOledImage(convertToOled1Bit(bitmap))
                            }
                        } else {
                            bleManager.writeMapImage(jpeg)
                        }
                        android.util.Log.d("NavigationService", "Sent Turn Screenshot at $matchedTrigger m")
                    }
                }
            }
        } else if (distMeters > (triggers.maxOrNull() ?: 0) + 50) {
            lastPopupTriggerDist = -1
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("NavigationService", "onCreate: Initializing BLE manager")
        instance = this
        NavigationRepository.setServiceRunning(true)
        bleManager = MyBleManager(this)
        gpsManager = GpsManager(this)
        ttsManager = TtsManager(this)
        NavigationRepository.addLog("Dịch vụ BLE đã khởi tạo.")
        createNotificationChannel()
        setupHeadlessMap()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gmapsHudReceiver, IntentFilter("com.example.tymap.ACTION_GMAPS_HUD"), RECEIVER_EXPORTED)
        } else {
            registerReceiver(gmapsHudReceiver, IntentFilter("com.example.tymap.ACTION_GMAPS_HUD"))
        }
        
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun handleHudUpdate(
        title: String, 
        details: String, 
        isGmaps: Boolean, 
        forcedIconIndex: Int? = null, 
        bitmap: android.graphics.Bitmap? = null,
        osmRoadName: String = "",
        osmEta: String = "",
        osmEte: String = ""
    ) {
        val source = PrefsHelper.getInt(this, "hud_source", 0) 
        val shouldSend = when (source) {
            0 -> true 
            1 -> isGmaps
            2 -> !isGmaps
            else -> true
        }

        if (shouldSend) {
            val finalDist: String
            val finalInstruction: String
            var eta = ""
            var ete = ""
            var road = ""
            
            if (isGmaps) {
                val parts = details.split("·")
                var rawDistance = parts.getOrNull(0)?.trim() ?: details
                val remainingInfo = parts.getOrNull(1)?.trim() ?: ""
                
                val distRegex = Regex("""^(\d+([.,]\d+)?\s?(m|km))""", RegexOption.IGNORE_CASE)
                val matchResult = distRegex.find(rawDistance)
                
                if (matchResult != null) {
                    finalDist = matchResult.groups[1]?.value ?: ""
                    val restOfTitle = rawDistance.substring(matchResult.range.last + 1).trim()
                    finalInstruction = if (restOfTitle.isNotEmpty()) restOfTitle else remainingInfo
                } else {
                    finalDist = rawDistance
                    finalInstruction = remainingInfo
                }
                
                val timeInfo = if (parts.size > 2) parts.last().trim() else ""
                if (timeInfo.contains("·")) {
                    val subParts = timeInfo.split("·")
                    ete = subParts.getOrNull(0)?.trim() ?: ""
                    eta = subParts.getOrNull(1)?.trim() ?: ""
                }
                road = title
            } else {
                finalDist = details
                finalInstruction = title
                road = osmRoadName
                eta = osmEta
                ete = osmEte
            }
            
            val iconIndex = forcedIconIndex ?: (if (isGmaps) guessIconFromTitle(title) else 0)
            
            NavigationRepository.updateHudPreview(NavigationRepository.HudData(
                active = true,
                isNavigation = true,
                hasIcon = true,
                distance = finalDist, 
                title = finalInstruction,
                directions = road,
                eta = eta, 
                duration = ete,
                speed = "",
                bitmapIcon = bitmap
            ))

            // Send to ESP32 in a structured format
            val bleData = "dist=$finalDist\ninst=$finalInstruction\nroad=$road\neta=$eta\nete=$ete"
            bleManager.writeNavigationData(bleData)
        }
    }

    private fun guessIconFromTitle(instruction: String): Int {
        val t = instruction.lowercase()
        return when {
            t.contains("đến") || t.contains("arrive") || t.contains("đích") -> 10
            t.contains("khởi hành") || t.contains("depart") -> 11
            t.contains("quay đầu") || t.contains("u-turn") || t.contains("uturn") -> if (t.contains("phải")) 8 else 7
            t.contains("vòng xuyến") || t.contains("roundabout") -> 9
            
            t.contains("gắt") || t.contains("sharp") -> if (t.contains("trái") || t.contains("left")) 3 else 6
            t.contains("chếch") || t.contains("slight") || t.contains("nhẹ") -> if (t.contains("trái") || t.contains("left")) 1 else 4
            t.contains("trái") || t.contains("left") -> 2
            t.contains("phải") || t.contains("right") -> 5
            
            t.contains("nhập làn") || t.contains("merge") -> 14
            t.contains("nhánh") || t.contains("fork") -> if (t.contains("trái") || t.contains("left")) 15 else 16
            t.contains("sát") || t.contains("keep") -> if (t.contains("trái") || t.contains("left")) 12 else 13
            
            t.contains("thẳng") || t.contains("straight") || t.contains("continue") -> 0
            else -> 0
        }
    }

    private fun setupHeadlessMap() {
        headlessMapView = MapView(this)
        headlessMapView?.setTileSource(TileSourceFactory.MAPNIK)
        headlessMapView?.layoutParams = ViewGroup.LayoutParams(240, 240)
        headlessMapView?.measure(
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        )
        headlessMapView?.layout(0, 0, 240, 240)
    }

    private var currentDestination: Pair<Double, Double>? = null
    private var lastRerouteTime: Long = 0
    private var lastOffRouteCheckTime: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("NavigationService", "onStartCommand")
        val notification = createNotification()
        
        // Xác định các loại dịch vụ có thể chạy dựa trên quyền đã được cấp
        var foregroundServiceType = 0
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            foregroundServiceType = foregroundServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                foregroundServiceType = foregroundServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        } else {
            foregroundServiceType = foregroundServiceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (foregroundServiceType != 0) {
                    startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
                } else {
                    // Fallback nếu không có quyền gì, chỉ chạy thông báo cơ bản
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("NavigationService", "Failed to start foreground service: ${e.message}")
            // Nếu vẫn lỗi, thử start cơ bản không type
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                stopSelf() // Không thể chạy service nếu không có notification
            }
        }

        // Navigation state is initially FALSE. 
        // Only set to TRUE when we have a real destination.
        gpsManager.startLocationUpdates()
        startMapRenderingLoop()
        startWeatherSync()
        startTimeSync()
        startNavigationLogic()
        observeIconRequests()
        observeMapMode()
        observeConnectionState()
        observeNavigationState()
        startAutoReconnectLoop()

        // Handle explicit or auto connection BLE
        val connectMac = intent?.getStringExtra("CONNECT_MAC")
        if (connectMac != null) {
            connectToMac(connectMac, useAutoConnect = false)
        } else {
            autoConnectBle()
        }

        if (intent?.action == "ACTION_START_CAPTURE") {
            val projectionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("PROJECTION_INTENT", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("PROJECTION_INTENT")
            }
            
            if (projectionIntent != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(android.app.Activity.RESULT_OK, projectionIntent)
                screenCaptureManager = ScreenCaptureManager(this)
                mediaProjection?.let { screenCaptureManager?.startCapture(it) }
            }
        }

        // Handle specific destination if passed via intent
        val destLat = intent?.getDoubleExtra("DEST_LAT", 0.0) ?: 0.0
        val destLon = intent?.getDoubleExtra("DEST_LON", 0.0) ?: 0.0
        if (destLat != 0.0 && destLon != 0.0) {
            currentDestination = Pair(destLat, destLon)
            NavigationRepository.setNavigationRunning(true)
            fetchInitialRoute(destLat, destLon)
        }
        
        return START_STICKY
    }

    private fun fetchInitialRoute(lat: Double, lon: Double) {
        serviceScope.launch(Dispatchers.IO) {
            val startLoc = NavigationRepository.gpsLocation.value ?: return@launch
            val routingEngine = RoutingEngine(httpClient)
            
            val preferredIdx = PrefsHelper.getInt(this@NavigationService, "routing_engine", 0)
            val preferredEngine = when(preferredIdx) { 
                1 -> "OpenRouteService"
                2 -> "GraphHopper"
                3 -> "Valhalla"
                4 -> "Mapbox"
                else -> "OSRM" 
            }
            
            // Build priority list from settings
            val savedPriority = PrefsHelper.getString(this@NavigationService, "routing_priority", "Mapbox,GraphHopper,Valhalla,OSRM")
            val priorityList = mutableListOf(preferredEngine)
            savedPriority.split(",").forEach { 
                if (it.isNotEmpty() && !priorityList.contains(it)) priorityList.add(it) 
            }
            val others = listOf("Mapbox", "GraphHopper", "Valhalla", "OSRM", "OpenRouteService")
            others.forEach { if (!priorityList.contains(it)) priorityList.add(it) }

            val routes = routingEngine.fetchRouteWithFallback(this@NavigationService, startLoc.latitude, startLoc.longitude, lat, lon, priorityList)
            if (routes != null && routes.isNotEmpty()) {
                NavigationRepository.updateRoutes(routes)
            }
        }
    }

    private fun startNavigationLogic() {
        serviceScope.launch {
            NavigationRepository.gpsLocation.collectLatest { location: Location? ->
                if (location == null) return@collectLatest
                
                if (PrefsHelper.getBoolean(this@NavigationService, "speed_warning", false)) {
                    val threshold = PrefsHelper.getInt(this@NavigationService, "speed_threshold", 60)
                    if (location.speed * 3.6 > threshold) {
                        if (PrefsHelper.getBoolean(this@NavigationService, "voice_speed_warning", true)) {
                            ttsManager.speak("Bạn đang chạy quá tốc độ cho phép")
                        }
                    }
                }
                bleManager.writeSpeed((location.speed * 3.6).toInt())

                // Process Route tracking only if navigation is running
                if (NavigationRepository.navigationState.value) {
                    val routes = NavigationRepository.routes.value
                    val selectedRoute = routes.find { it.isSelected } ?: routes.firstOrNull()
                    if (selectedRoute != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastOffRouteCheckTime >= 2000) {
                            lastOffRouteCheckTime = now
                            val distanceToRoute = computeDistanceToPolyline(location, selectedRoute.polyline)
                            val offRouteThreshold = PrefsHelper.getFloat(this@NavigationService, "off_route_dist", 30f)
                            
                            if (distanceToRoute > offRouteThreshold) {
                                // Reroute after 10s cooldown to avoid spamming
                                if (now - lastRerouteTime > 10000) {
                                    lastRerouteTime = now
                                    if (PrefsHelper.getBoolean(this@NavigationService, "voice_off_route", true)) {
                                        ttsManager.speak("Bạn đã đi chệch hướng, đang tìm lại đường")
                                    }
                                    currentDestination?.let { fetchInitialRoute(it.first, it.second) }
                                }
                            }
                        }

                        val source = PrefsHelper.getInt(this@NavigationService, "hud_source", 0)
                        if (source == 2 || source == 0) {
                            updateHudFromSteps(location, selectedRoute.steps)
                        }
                    }
                }
            }
        }
    }

    private suspend fun computeDistanceToPolyline(location: android.location.Location, polyline: List<Pair<Double, Double>>): Double = withContext(Dispatchers.Default) {
        polyline.minOfOrNull { pt ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(location.latitude, location.longitude, pt.first, pt.second, results)
            results[0].toDouble()
        } ?: Double.MAX_VALUE
    }

    private fun updateHudFromSteps(location: android.location.Location, steps: List<com.example.tymap.repository.StepInfo>) {
        if (steps.isEmpty()) return
        val nextStep = steps.firstOrNull { step ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(location.latitude, location.longitude, step.location.first, step.location.second, results)
            results[0] > 10
        }
        if (nextStep != null) {
            val distResults = FloatArray(1)
            android.location.Location.distanceBetween(location.latitude, location.longitude, nextStep.location.first, nextStep.location.second, distResults)
            val dist = distResults[0]
            val distanceStr = if (dist > 1000) String.format(Locale.getDefault(), "%.1f km", dist / 1000) else "${dist.toInt()} m"
            
            // Tính toán quãng đường và thời gian còn lại (remaining) từ bước này đến cuối lộ trình
            var remainingDist = dist.toDouble()
            var remainingDur = nextStep.duration * (dist / nextStep.distance.coerceAtLeast(1.0))
            val nextStepIdx = steps.indexOf(nextStep)
            if (nextStepIdx >= 0 && nextStepIdx < steps.size - 1) {
                for (i in (nextStepIdx + 1) until steps.size) {
                    remainingDist += steps[i].distance
                    remainingDur += steps[i].duration
                }
            }
            
            // Định dạng ETE và ETA cho OSM navigation
            val hours = (remainingDur / 3600).toInt()
            val minutes = ((remainingDur % 3600) / 60).toInt()
            val eteStr = if (hours > 0) "${hours}h${minutes}p" else "${minutes}p"
            
            val cal = Calendar.getInstance()
            cal.add(Calendar.SECOND, remainingDur.toInt())
            val etaStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
            
            handleHudUpdate(
                title = nextStep.instruction, 
                details = distanceStr, 
                isGmaps = false, 
                forcedIconIndex = nextStep.maneuverIcon,
                osmRoadName = nextStep.roadName,
                osmEta = etaStr,
                osmEte = eteStr
            )
        }
    }

    private fun startWeatherSync() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                NavigationRepository.gpsLocation.collect { location ->
                if (location == null) return@collect
                try {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current_weather=true"
                        val request = Request.Builder().url(url).build()
                        httpClient.newCall(request).execute().use { response ->
                            val body = response.body.string()
                            if (response.isSuccessful && body.startsWith("{")) {
                                val json = gson.fromJson(body, WeatherResponse::class.java)
                                bleManager.writeWeather(gson.toJson(mapOf(
                                    "t" to json.current_weather.temperature,
                                    "i" to mapWeatherCode(json.current_weather.weathercode)
                                )))
                            } else {
                                android.util.Log.e("NavigationService", "Weather API Error: ${response.code} - $body")
                            }
                        }
                    } catch (e: Exception) { }
                    delay(600_000)
                }
            }
        }
    }

    private fun startTimeSync() {
        serviceScope.launch {
            while (isActive) {
                bleManager.writeTime(System.currentTimeMillis())
                delay(300_000)
            }
        }
    }

    private fun mapWeatherCode(code: Int): String {
        return when (code) { 0 -> "01d"; 1, 2, 3 -> "02d"; else -> "03d" }
    }

    data class WeatherResponse(val current_weather: CurrentWeather)
    data class CurrentWeather(val temperature: Float, val weathercode: Int)

    private fun isGoogleMapsForeground(): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 5, time)
        if (stats != null) {
            val latest = stats.maxByOrNull { it.lastTimeUsed }
            return latest?.packageName == "com.google.android.apps.maps"
        }
        return false
    }

    private fun connectToMac(mac: String, useAutoConnect: Boolean = false) {
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(mac)
            
            val state = NavigationRepository.bleConnectionState.value
            if (bleManager.bluetoothDevice == device && state != NavigationRepository.BleConnectionState.Disconnected) {
                return
            }
            
            if (bleManager.bluetoothDevice != null && bleManager.bluetoothDevice != device) {
                NavigationRepository.addLog("Đang kết nối thiết bị khác. Ngắt kết nối...")
                bleManager.disconnect().enqueue()
            }
            
            bleManager.connect(device)
                .retry(3, 1000)
                .useAutoConnect(useAutoConnect)
                .enqueue()
            val deviceName = try { device.name ?: "Thiết bị không tên" } catch (e: SecurityException) { "Thiết bị" }
            NavigationRepository.addLog("Đang kết nối tới $deviceName ($mac)...")
        } catch (e: Exception) {
            NavigationRepository.addLog("Lỗi kết nối: ${e.message}")
        }
    }

    private fun autoConnectBle() {
        val state = NavigationRepository.bleConnectionState.value
        if (state != NavigationRepository.BleConnectionState.Disconnected) return
        val history = PrefsHelper.getPairedHistory(this)
        if (history.isNotEmpty()) {
            val lastDevice = history.last() // Định dạng "Name (MAC)"
            val mac = lastDevice.substringAfter("(").substringBefore(")")
            if (mac.length == 17) {
                connectToMac(mac, useAutoConnect = true)
            }
        }
    }

    private fun startMapRenderingLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val captureMode = PrefsHelper.getInt(this@NavigationService, "map_capture_mode", 0)
                if (isMapModeActive || (isPopupActive && captureMode != 2)) {
                    val quality = PrefsHelper.getFloat(this@NavigationService, "jpeg_quality", 70f).toInt()
                    val fpsVal = PrefsHelper.getInt(this@NavigationService, "map_fps", 0)
                    val fps = when (fpsVal) {
                        1 -> 2
                        2 -> 3
                        3 -> 5
                        else -> 1
                    }
                    
                    var imageBytes: ByteArray? = null
                    val deviceDisplay = NavigationRepository.deviceStatus.value["display"] ?: ""
                    val isOled = deviceDisplay.contains("OLED")

                    if (captureMode == 1 && isGoogleMapsForeground()) {
                        imageBytes = screenCaptureManager?.captureAndProcess(quality, "gmaps_")
                    } 
                    
                    // Fallback to OSM Headless
                    if (imageBytes == null) {
                        imageBytes = renderOsmMap(quality)
                    }

                    if (imageBytes != null) {
                        if (isOled) {
                            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            bleManager.writeOledImage(convertToOled1Bit(bitmap))
                        } else {
                            bleManager.writeMapImage(imageBytes)
                        }
                    }
                    
                    // Nếu là popup thì chỉ gửi 1 ảnh hoặc delay lâu hơn
                    if (isPopupActive && !isMapModeActive) {
                        delay(PrefsHelper.getInt(this@NavigationService, "popup_duration", 5) * 1000L)
                        isPopupActive = false
                    } else {
                        delay(1000L / fps)
                    }
                } else { delay(2000) }
            }
        }
    }

    private suspend fun renderOsmMap(quality: Int): ByteArray? {
        val location = NavigationRepository.gpsLocation.value
        
        // Capture size is 240x240 for ESP32
        // We render a larger area then crop if configured
        val renderWidth = 480
        val renderHeight = 800 // standard-ish aspect ratio
        
        val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        withContext(Dispatchers.Main) {
            location?.let {
                headlessMapView?.controller?.setCenter(org.osmdroid.util.GeoPoint(it.latitude, it.longitude))
                headlessMapView?.mapOrientation = -it.bearing
            }
            // Resize headless map view for rendering
            headlessMapView?.layout(0, 0, renderWidth, renderHeight)
            headlessMapView?.draw(canvas)
        }

        // Apply crop configuration for Map Tab
        val prefs = getSharedPreferences("tymap_settings", Context.MODE_PRIVATE)
        val normX = prefs.getFloat("map_tab_crop_x_norm", 0f)
        val normY = prefs.getFloat("map_tab_crop_y_norm", 0f)
        val normSize = prefs.getFloat("map_tab_crop_size_norm", 0.4f)

        val absX = (normX * renderWidth).toInt()
        val absY = (normY * renderHeight).toInt()
        val absSize = (normSize * renderWidth).toInt()

        val safeX = absX.coerceIn(0, (renderWidth - absSize).coerceAtLeast(0))
        val safeY = absY.coerceIn(0, (renderHeight - absSize).coerceAtLeast(0))
        
        val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, absSize, absSize)
        val scaled = Bitmap.createScaledBitmap(cropped, 240, 240, true)

        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        
        bitmap.recycle()
        cropped.recycle()
        scaled.recycle()

        return bytes
    }

    private fun convertToOled1Bit(bitmap: Bitmap): ByteArray {
        val resized = Bitmap.createScaledBitmap(bitmap, 128, 64, true)
        val buffer = ByteArray(1024)
        
        val threshold = PrefsHelper.getInt(this, "oled_threshold", 128)
        val invert = PrefsHelper.getBoolean(this, "oled_invert", false)
        val dithering = PrefsHelper.getBoolean(this, "oled_dithering", true)
        
        val filterEnabled = PrefsHelper.getBoolean(this, "oled_color_filter", false)
        val activeFilters = if (filterEnabled) {
            val filtersJson = PrefsHelper.getColorFilters(this)
            val filterType = object : TypeToken<List<OledFilter>>() {}.type
            val filters: List<OledFilter> = gson.fromJson(filtersJson, filterType) ?: emptyList()
            filters.filter { it.isActive }
        } else {
            emptyList()
        }
        
        val filterInvert = PrefsHelper.getBoolean(this, "oled_filter_invert", false)

        val pixels = Array(64) { FloatArray(128) }
        
        for (y in 0 until 64) {
            for (x in 0 until 128) {
                val pixel = resized.getPixel(x, y)
                if (activeFilters.isNotEmpty()) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    
                    var bestMatchValue = 0f
                    for (f in activeFilters) {
                        val fR = (f.color shr 16) and 0xFF
                        val fG = (f.color shr 8) and 0xFF
                        val fB = f.color and 0xFF
                        
                        val distance = Math.sqrt(((r-fR)*(r-fR) + (g-fG)*(g-fG) + (b-fB)*(b-fB)).toDouble())
                        val diffPercent = (distance / 441.67) * 100.0
                        
                        if (diffPercent <= f.tolerance) {
                            val matchValue = ((1.0 - (diffPercent / f.tolerance)) * 255).toFloat()
                            if (matchValue > bestMatchValue) {
                                bestMatchValue = matchValue
                            }
                        }
                    }
                    pixels[y][x] = if (filterInvert) 255f - bestMatchValue else bestMatchValue
                } else {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val gray = (0.299 * r + 0.587 * g + 0.114 * b).toFloat()
                    pixels[y][x] = gray
                }
            }
        }

        if (dithering) {
            for (y in 0 until 64) {
                for (x in 0 until 128) {
                    val oldVal = pixels[y][x]
                    val newVal = if (oldVal > threshold) 255f else 0f
                    pixels[y][x] = newVal
                    
                    val err = oldVal - newVal
                    
                    if (x + 1 < 128) pixels[y][x + 1] += err * 7f / 16f
                    if (y + 1 < 64) {
                        if (x - 1 >= 0) pixels[y + 1][x - 1] += err * 3f / 16f
                        pixels[y + 1][x] += err * 5f / 16f
                        if (x + 1 < 128) pixels[y + 1][x + 1] += err * 1f / 16f
                    }
                }
            }
        }

        var bitIndex = 0
        for (y in 0 until 64) {
            for (x in 0 until 128) {
                val valToThreshold = pixels[y][x]
                var isOn = valToThreshold > threshold
                
                if (invert) {
                    isOn = !isOn
                }
                
                if (isOn) {
                    val byteIdx = bitIndex / 8
                    val bitPos = 7 - (bitIndex % 8)
                    buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl bitPos)).toByte()
                }
                bitIndex++
            }
        }
        resized.recycle()
        return buffer
    }

    private fun observeIconRequests() {
        serviceScope.launch {
            NavigationRepository.iconRequest.collect { hashHex ->
                android.util.Log.d("BleManager", "observeIconRequests: Received icon_req for hash = $hashHex")
                NavigationRepository.addLog("BLE: Received icon_req for hash = $hashHex")
                val currentHud = NavigationRepository.hudPreviewData.value
                val icon1bpp = currentHud?.icon1bpp
                if (icon1bpp != null) {
                    val currentHash = calculateCRC32(icon1bpp)
                    val currentHashHex = String.format("%08X", currentHash)
                    android.util.Log.d("BleManager", "observeIconRequests: Current hash = $currentHashHex, requested = $hashHex")
                    NavigationRepository.addLog("BLE: Current icon hash = $currentHashHex, requested = $hashHex")
                    if (currentHashHex == hashHex) {
                        android.util.Log.d("BleManager", "observeIconRequests: Writing icon bitmap data (288 bytes)")
                        NavigationRepository.addLog("BLE: Writing icon bitmap data (288 bytes) to CHA_ICON_DATA")
                        bleManager.writeIconData(currentHash, icon1bpp)
                    } else {
                        android.util.Log.w("BleManager", "observeIconRequests: WARNING - Hash mismatch!")
                        NavigationRepository.addLog("BLE: WARNING - Hash mismatch! current=$currentHashHex, requested=$hashHex")
                    }
                } else {
                    android.util.Log.e("BleManager", "observeIconRequests: ERROR - current icon1bpp is NULL")
                    NavigationRepository.addLog("BLE: ERROR - Received icon_req but current icon1bpp is NULL")
                }
            }
        }
    }

    private fun observeMapMode() {
        serviceScope.launch {
            NavigationRepository.mapModeState.collect { active ->
                isMapModeActive = active
                android.util.Log.d("NavigationService", "Map Mode Active: $active")
            }
        }
    }

    private fun observeNavigationState() {
        serviceScope.launch {
            NavigationRepository.navigationState.collect { running ->
                val captureMode = PrefsHelper.getInt(this@NavigationService, "map_capture_mode", 0)
                if (running) {
                    if (captureMode == 0) { // Chỉ tự động chuyển sang MAP_MODE khi truyền ảnh OSM liên tục
                        if (bleManager.isConnected) {
                            android.util.Log.d("NavigationService", "OSM Navigation started, switching ESP32 to MAP_MODE")
                            bleManager.sendRemoteCommand(0x11.toByte())
                        }
                        NavigationRepository.setMapModeActive(true)
                    }
                } else {
                    if (bleManager.isConnected) {
                        android.util.Log.d("NavigationService", "OSM Navigation stopped, switching ESP32 to STATUS_MODE")
                        val bleData = "active=0\nnav=0\ndist=\ntitle=\ndir=\neta="
                        bleManager.writeNavigationData(bleData)
                        bleManager.sendRemoteCommand(0x12.toByte())
                    }
                    NavigationRepository.setMapModeActive(false)
                }
            }
        }
    }

    private fun observeConnectionState() {
        serviceScope.launch {
            NavigationRepository.bleConnectionState.collectLatest { state ->
                if (state == NavigationRepository.BleConnectionState.Ready) {
                    // Reset cache icon khi có kết nối mới để đảm bảo ESP32 nhận được icon hiện tại
                    lastSentIconHash = -1

                    // Send time immediately upon ready
                    bleManager.writeTime(System.currentTimeMillis())
                    
                    // Trigger manual sticky battery update
                    val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    if (batteryIntent != null) {
                        val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                        val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                        val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || 
                                         status == android.os.BatteryManager.BATTERY_STATUS_FULL
                        if (level >= 0 && scale > 0) {
                            val batteryPct = (level * 100 / scale.toFloat()).toInt()
                            bleManager.writePhoneBattery(batteryPct, isCharging)
                        }
                    }
                } else if (state == NavigationRepository.BleConnectionState.Disconnected) {
                    // Tự động kết nối lại sau 5s nếu bị mất kết nối đột ngột
                    delay(5000)
                    autoConnectBle()
                }
            }
        }
    }

    private fun startAutoReconnectLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(30000) // 30 seconds
                if (NavigationRepository.bleConnectionState.value == NavigationRepository.BleConnectionState.Disconnected) {
                    val lastMac = PrefsHelper.getString(this@NavigationService, "last_device_mac", "")
                    if (lastMac.isNotEmpty()) {
                        android.util.Log.d("NavigationService", "Auto-reconnect loop triggering for $lastMac")
                        connectToMac(lastMac, useAutoConnect = true)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(gmapsHudReceiver)
        unregisterReceiver(batteryReceiver)
        NavigationRepository.updateHudPreview(null)
        NavigationRepository.setServiceRunning(false)
        serviceScope.cancel()
        gpsManager.stopLocationUpdates()
        bleManager.disconnect().enqueue()
        ttsManager.shutdown()
        NavigationRepository.setNavigationRunning(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Navigation Service Channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Navigation Running")
            .setContentText("Tap to return to the app")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }
}
