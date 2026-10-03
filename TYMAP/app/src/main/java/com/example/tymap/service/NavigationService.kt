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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.RectF
import android.location.Location
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.config.Configuration
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
import com.example.tymap.utils.IconUtils
import com.example.tymap.ui.maneuverIconRes
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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.tilesource.ITileSource
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
        val activeInstance: NavigationService? get() = instance
        val bleManager: MyBleManager? get() = instance?.bleManager
        val screenCaptureManager: ScreenCaptureManager? get() = instance?.screenCaptureManager

        fun disconnectBle() {
            NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Disconnected)
            instance?.let { service ->
                service.isManualDisconnect = true
                try {
                    service.bleManager.disconnect().enqueue()
                } catch (e: Exception) {
                    android.util.Log.e("NavigationService", "Error disconnecting: ${e.message}")
                }
                NavigationRepository.addLog("Đã ngắt kết nối BLE thủ công.")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var bleManager: MyBleManager
    private var isManualDisconnect: Boolean = false
    private lateinit var gpsManager: GpsManager
    private lateinit var ttsManager: TtsManager
    private val httpClient = OkHttpClient()
    private val gson = GsonBuilder().setStrictness(Strictness.LENIENT).create()
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "NavigationChannel"

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // Icon Hash Cache to avoid re-sending same icon data
    private var lastSentIconHash: Long = -1

    private var headlessMapView: MapView? = null
    private var headlessUserMarker: Marker? = null
    private var headlessDestMarker: Marker? = null
    private var isMapModeActive = false
    private var isGmapsActive = false
    var screenCaptureManager: ScreenCaptureManager? = null
    private var roadsOnlyMapRenderer: RoadsOnlyMapRenderer? = null
    private var mediaProjection: MediaProjection? = null
    private var lastMapUpdateLocation: android.location.Location? = null
    private var lastMapUpdateTime: Long = 0
    private var lastEspSpeedWarningTime: Long = 0L
    private var lastVoiceSpeedWarningTime: Long = 0L
    
    private var lastMapNavDataSentTime = 0L
    private var lastSentEta = ""
    private var lastSentEte = ""
    private var lastMapPreviewUpdateTime = 0L
    
    // Tracking for Turn Screenshots
    // State Machine Display Mode
    private var currentAppliedMode: Byte = 0x12.toByte() // Mặc định STATUS_MODE
    var lastManualOverrideMode: Byte? = null
    
    private var lastTurnIconHash: Long = -1L
    private var lastTurnInstruction: String = ""
    private var lastTurnIconIndex: Int = -1
    private var isNearTurnCompleted: Boolean = false
    private var currentDistanceToNextMeters: Int = -1

    // Intelligent Chaser Engine (ICE)
    private val chaserEngine = ChaserEngine(offRouteThresholdMeters = 15f, cooldownMs = 2000L)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                
                if (level >= 0 && scale > 0) {
                    val batteryPct = ((level / scale.toFloat()) * 100).toInt()
                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || 
                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    
                    if (bleManager.isConnected) {
                        bleManager.writePhoneBattery(batteryPct, isCharging)
                    }
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

    fun handleGmapsStopFromUi() {
        handleGmapsStop()
    }

    private fun handleGmapsStop() {
        isGmapsActive = false
        currentDistanceToNextMeters = -1
        android.util.Log.d("NavigationService", "Google Maps navigation stopped")
        NavigationRepository.updateHudPreview(null)
        lastSentIconHash = -1

        if (bleManager.isConnected) {
            val bleData = "active=0\nnav=0\ndist=\ntitle=\ndir=\neta="
            bleManager.writeNavigationData(bleData)
        }

        evaluateAndApplyDisplayMode()
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
        val validIcon1bpp = if (icon1bpp != null && !IconUtils.is1bppEmpty(icon1bpp)) icon1bpp else null
        val finalIcon1bpp = validIcon1bpp 
            ?: bitmap?.let { bmp ->
                val b = IconUtils.convertTo1bpp(bmp, 48, 48)
                if (!IconUtils.is1bppEmpty(b)) b else null
            }
            ?: IconUtils.getVectorDrawable1bpp(this, com.example.tymap.ui.maneuverIconRes(iconIndex), 48, 48)
        val iconHash = finalIcon1bpp?.let { calculateCRC32(it) } ?: -1L
        
        // Kích hoạt State Machine khi Google Maps bắt đầu dẫn đường
        if (!isGmapsActive) {
            isGmapsActive = true
            evaluateAndApplyDisplayMode()
        }

        val effectiveInstruction = if (instruction.isNotEmpty()) instruction else roadName
        val effectiveRoad = if (roadName.isNotEmpty()) roadName else instruction

        // Tự động nhận diện ngã rẽ mới qua ảnh bitmap mũi tên, instruction rẽ mới hoặc hướng rẽ mới
        val isNewTurn = (iconHash != -1L && iconHash != lastTurnIconHash) || 
                        (effectiveInstruction.isNotEmpty() && effectiveInstruction != lastTurnInstruction) ||
                        (iconIndex != lastTurnIconIndex)
        if (isNewTurn) {
            lastTurnIconHash = iconHash
            lastTurnInstruction = effectiveInstruction
            lastTurnIconIndex = iconIndex
            isNearTurnCompleted = false
            // Reset lastSentIconHash để đảm bảo icon của ngã rẽ mới luôn được gửi sang ESP32 ngay cả khi cùng loại icon
            lastSentIconHash = -1
            android.util.Log.d("NavigationService", "New turn arrow bitmap/instruction received ($effectiveInstruction, dir=$iconIndex)")
        }

        // Parse distance to meters
        val distInMeters = parseDistance(distance)
        currentDistanceToNextMeters = distInMeters

        NavigationRepository.updateHudPreview(NavigationRepository.HudData(
            active = true,
            isNavigation = true,
            hasIcon = finalIcon1bpp != null,
            distance = distance,
            eta = eta,
            duration = ete,
            title = effectiveInstruction, 
            directions = effectiveRoad,
            iconIndex = iconIndex,
            icon1bpp = finalIcon1bpp,
            bitmapIcon = bitmap
        ))

        // 1. Luôn gửi dữ liệu điều hướng (khoảng cách + tên đường) tức thì sang ESP32
        val cleanDist = cleanDistanceString(distance).ifEmpty { distance }
        val bleData = "active=1\nnav=1\ndist=$cleanDist\ntitle=$effectiveInstruction\nroad=$effectiveRoad\ndir=$iconIndex\neta=$eta\nete=$ete"
        bleManager.writeNavigationData(bleData)
        
        // 2. Gửi Icon Data: Gửi trực tiếp cả bitmap 292-byte và hash khi có ngã rẽ mới
        if (finalIcon1bpp != null && !IconUtils.is1bppEmpty(finalIcon1bpp)) {
            if (iconHash != lastSentIconHash) {
                lastSentIconHash = iconHash
                val hashHex = String.format("%08X", iconHash)
                NavigationRepository.addLog("BLE: Sending icon hash = $hashHex + direct bitmap (292 bytes)")
                // Gửi trực tiếp 292 bytes (4-byte hash + 288-byte bitmap) sang ESP32
                bleManager.writeIconData(iconHash, finalIcon1bpp)
                // Đồng thời gửi hash sang CHA_NAV_TBT_ICON_UUID
                bleManager.writeNavIconHash(hashHex)
            } else {
                NavigationRepository.addLog("BLE: Icon hash matches lastSentIconHash ($iconHash), skip sending hash")
            }
        } else {
            NavigationRepository.addLog("BLE: No valid icon1bpp available, clearing lastSentIconHash")
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

    private fun cleanDistanceString(distStr: String): String {
        val clean = distStr.trim()
        val regex = Regex("""^(\d+(?:[.,]\d+)?\s*(?:m|km))""", RegexOption.IGNORE_CASE)
        val match = regex.find(clean)
        return match?.groups?.get(1)?.value?.trim() ?: clean
    }

    /**
     * State Machine trung tâm điều phối chế độ màn hình ESP32 theo 5 Kịch bản:
     * - Kịch bản 1: Chỉ có Google Maps -> HUD_MODE (0x10)
     * - Kịch bản 2: Chỉ có TYMAP Map -> MAP_MODE (0x11)
     * - Kịch bản 3: Kết hợp cả Google Maps và TYMAP Map -> MAP_HUD_MODE (0x13)
     * - Kịch bản 4: Can thiệp thủ công từ UI (manualOverride) -> ép chuyển mode tương ứng
     * - Kịch bản 5: Hủy dẫn đường / Kết thúc chuyến đi -> STATUS_MODE (0x12)
     */
    @Synchronized
    fun evaluateAndApplyDisplayMode(manualOverride: Byte? = null, force: Boolean = false) {
        if (manualOverride != null) {
            lastManualOverrideMode = manualOverride
        }

        val gmaps = isGmapsActive
        val map = isMapModeActive || NavigationRepository.navigationState.value

        // Khi tất cả đều dừng dẫn đường, reset manual override để chuyến đi sau tiếp tục chạy tự động
        if (!gmaps && !map) {
            lastManualOverrideMode = null
        }

        val targetMode: Byte = if (lastManualOverrideMode != null) {
            lastManualOverrideMode!!
        } else {
            when {
                gmaps && map -> 0x13.toByte() // Kịch bản 3: Chế độ kết hợp MAP_HUD_MODE
                !gmaps && map -> 0x11.toByte() // Kịch bản 2: Dẫn đường thuần bằng TYMAP (Map)
                gmaps && !map -> 0x10.toByte() // Kịch bản 1: Dẫn đường thuần bằng Google Maps (HUD)
                else -> 0x12.toByte()          // Kịch bản 5: Hủy dẫn đường / Kết thúc -> STATUS_MODE
            }
        }

        if (force || targetMode != currentAppliedMode) {
            currentAppliedMode = targetMode
            if (bleManager.isConnected) {
                android.util.Log.d("NavigationService", "State Machine: Switching display mode to 0x${String.format("%02X", targetMode)}")
                bleManager.sendRemoteCommand(targetMode)
            }
            if (targetMode == 0x10.toByte() || targetMode == 0x13.toByte()) {
                triggerImmediateHudUpdate()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("NavigationService", "onCreate: Initializing BLE manager")
        instance = this
        NavigationRepository.setServiceRunning(true)
        bleManager = MyBleManager(this)
        gpsManager = GpsManager(this).apply {
            onSpeedUpdated = { speed ->
                if (bleManager.isConnected) {
                    bleManager.writeSpeed(speed)
                }
            }
        }
        ttsManager = TtsManager(this)
        roadsOnlyMapRenderer = RoadsOnlyMapRenderer(this)
        
        // Cấu hình Intelligent Chaser Engine (ICE)
        chaserEngine.onRouteDeviationListener = { devLocation ->
            serviceScope.launch(Dispatchers.IO) {
                val dest = currentDestination
                if (dest != null) {
                    if (PrefsHelper.getBoolean(this@NavigationService, "voice_off_route", true)) {
                        ttsManager.speak("Đang đồng bộ lại lộ trình")
                    }
                    val routingEngine = RoutingEngine(httpClient)
                    val vehicleType = PrefsHelper.getInt(this@NavigationService, "vehicle_type", 0)
                    val avoidHighways = vehicleType == 1
                    val newRoutes = routingEngine.fetchFastOsrmReroute(
                        devLocation.latitude, devLocation.longitude,
                        dest.first, dest.second,
                        avoidHighways
                    )
                    if (!newRoutes.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            NavigationRepository.updateRoutes(newRoutes)
                            NavigationRepository.addLog("ICE: Đã tự động đồng bộ lại lộ trình OSRM trong <= 2s")
                        }
                    }
                }
            }
        }

        NavigationRepository.addLog("Dịch vụ BLE đã khởi tạo.")
        createNotificationChannel()
        setupHeadlessMap()
        
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TYMAP:NavigationServiceWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24h safety timeout
            }
            android.util.Log.d("NavigationService", "Acquired PARTIAL_WAKE_LOCK for uninterrupted background navigation")
        } catch (e: Exception) {
            android.util.Log.w("NavigationService", "Could not acquire WakeLock: ${e.message}")
        }

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
            0 -> if (isGmapsActive) isGmaps else true // Chế độ Tự động (0): ƯU TIÊN Google Maps khi có thông báo Google Maps
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
            val finalIcon1bpp = bitmap?.let { bmp ->
                val b = IconUtils.convertTo1bpp(bmp, 48, 48)
                if (!IconUtils.is1bppEmpty(b)) b else null
            } ?: IconUtils.getVectorDrawable1bpp(this@NavigationService, com.example.tymap.ui.maneuverIconRes(iconIndex), 48, 48)
            
            NavigationRepository.updateHudPreview(NavigationRepository.HudData(
                active = true,
                isNavigation = true,
                hasIcon = finalIcon1bpp != null,
                distance = finalDist, 
                title = finalInstruction,
                directions = road,
                eta = eta, 
                duration = ete,
                speed = "",
                iconIndex = iconIndex,
                bitmapIcon = bitmap,
                icon1bpp = finalIcon1bpp
            ))

            // Send to ESP32 in a structured format
            val cleanDist = cleanDistanceString(finalDist)
            val bleData = "active=1\nnav=1\ndist=$cleanDist\ntitle=$finalInstruction\nroad=$road\ndir=$iconIndex\neta=$eta\nete=$ete"
            bleManager.writeNavigationData(bleData)

            if (finalIcon1bpp != null && !IconUtils.is1bppEmpty(finalIcon1bpp)) {
                val iconHash = calculateCRC32(finalIcon1bpp)
                if (iconHash != lastSentIconHash) {
                    lastSentIconHash = iconHash
                    val hashHex = String.format("%08X", iconHash)
                    NavigationRepository.addLog("BLE: Sending in-app icon hash = $hashHex + direct bitmap (292 bytes)")
                    bleManager.writeIconData(iconHash, finalIcon1bpp)
                    bleManager.writeNavIconHash(hashHex)
                }
            }
        }
    }

    private fun guessIconFromTitle(instruction: String): Int {
        val t = instruction.lowercase()
        return when {
            t.contains("đến") || t.contains("arrive") || t.contains("đích") || t.contains("đã tới") -> 14
            t.contains("khởi hành") || t.contains("depart") -> 0
            t.contains("quay đầu") || t.contains("u-turn") || t.contains("uturn") -> if (t.contains("phải")) 8 else 7
            t.contains("vòng xuyến") || t.contains("bùng binh") || t.contains("roundabout") || t.contains("rotary") -> 12
            
            t.contains("gắt") || t.contains("sharp") || t.contains("ngoặt") -> if (t.contains("trái") || t.contains("left")) 6 else 3
            t.contains("chếch") || t.contains("slight") || t.contains("nhẹ") -> if (t.contains("trái") || t.contains("left")) 4 else 1
            t.contains("trái") || t.contains("left") -> 5
            t.contains("phải") || t.contains("right") -> 2
            
            t.contains("nhập làn") || t.contains("merge") -> 15
            t.contains("nhánh") || t.contains("fork") -> if (t.contains("trái") || t.contains("left")) 4 else 1
            t.contains("sát") || t.contains("keep") -> if (t.contains("trái") || t.contains("left")) 4 else 1
            
            t.contains("thẳng") || t.contains("straight") || t.contains("continue") || t.contains("tiếp") -> 0
            else -> 0
        }
    }

    private fun setupHeadlessMap() {
        Configuration.getInstance().userAgentValue = "Mozilla/5.0 (Android; Mobile; TYMAP/1.0)"
        headlessMapView = MapView(this)
        // Rule APP-21: Bật cache tối thiểu 50MB cho headless map
        headlessMapView?.setTileSource(TileSourceFactory.MAPNIK)
        headlessMapView?.setLayerType(View.LAYER_TYPE_SOFTWARE, null) // CRITICAL: Services don't have HW acceleration
        headlessMapView?.layoutParams = ViewGroup.LayoutParams(240, 240)
        headlessMapView?.measure(
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
        )
        headlessMapView?.layout(0, 0, 240, 240)
        NavigationRepository.lastMapZoom = PrefsHelper.getFloat(this, "last_map_zoom", 15f).toDouble()

        // Fallback for satellite tiles failing in background
        val tileCallbackHandler = object : android.os.Handler(android.os.Looper.getMainLooper()) {
            override fun handleMessage(msg: android.os.Message) {
                if (msg.what == org.osmdroid.tileprovider.MapTileProviderBase.MAPTILE_FAIL_ID) {
                    val currentSource = headlessMapView?.tileProvider?.tileSource
                    if (currentSource != null && currentSource.name() == "Satellite (ESRI)") {
                        headlessMapView?.setTileSource(googleMapsDark)
                        PrefsHelper.putInt(this@NavigationService, "tile_source", 0)
                        android.util.Log.w("NavigationService", "Satellite tile loading failed, falling back to Google Maps Dark")
                    }
                }
            }
        }
        headlessMapView?.tileProvider?.tileRequestCompleteHandlers?.add(tileCallbackHandler)
    }

    private var currentDestination: Pair<Double, Double>? = null
    private var lastRerouteTime: Long = 0
    private var lastOffRouteCheckTime: Long = 0

    private var isInitialized = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("NavigationService", "onStartCommand: action=${intent?.action}, isInitialized=$isInitialized")
        val notification = createNotification()
        
        // ... (rest of type calculation)
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

        val captureMode = PrefsHelper.getInt(this, "map_capture_mode", 0)

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
        if (!isInitialized) {
            isInitialized = true

            // Kích hoạt WakeLock giữ CPU chạy ngầm liên tục khi người dùng khóa/tắt màn hình điện thoại
            try {
                if (wakeLock == null) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TYMAP:GpsWakeLock")
                    wakeLock?.setReferenceCounted(false)
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(24 * 60 * 60 * 1000L) // Giữ CPU chạy ngầm liên tục
                    android.util.Log.d("NavigationService", "Acquired WakeLock successfully for background GPS & BLE")
                }
            } catch (e: Exception) {
                android.util.Log.e("NavigationService", "Failed to acquire WakeLock: ${e.message}")
            }

            gpsManager.startLocationUpdates()
            // startMapRenderingLoop() được gọi sau khi ACTION_START_CAPTURE được xử lý
            // để screenCaptureManager sẵn sàng trước khi vòng lặp chạy
            startWeatherSync()
            startTimeSync()
            startSpeedWatchdogLoop()
            startNavigationLogic()
            observeIconRequests()
            observeMapMode()
            observeConnectionState()
            observeNavigationState()
            startAutoReconnectLoop()
            
            // Start rendering loop once
            startMapRenderingLoop()
        }

        // Handle explicit or auto connection BLE
        val connectMac = intent?.getStringExtra("CONNECT_MAC")
        if (connectMac != null) {
            connectToMac(connectMac, useAutoConnect = false)
        } else if (bleManager.bluetoothDevice == null) {
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
                try {
                    // Release old capture resources to prevent display conflicts
                    screenCaptureManager?.stopCapture()
                    mediaProjection?.stop()
                } catch (e: Exception) {
                    android.util.Log.e("NavigationService", "Error releasing old capture resources: ${e.message}")
                }
                
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(android.app.Activity.RESULT_OK, projectionIntent)
                screenCaptureManager = ScreenCaptureManager(this)
                mediaProjection?.let { screenCaptureManager?.startCapture(it) }
                android.util.Log.d("NavigationService", "ScreenCaptureManager initialized successfully")
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
            val routes = routingEngine.fetchOsrmAndValhalla(this@NavigationService, startLoc.latitude, startLoc.longitude, lat, lon)
            if (routes.isNotEmpty()) {
                NavigationRepository.updateRoutes(routes)
            }
        }
    }

    private fun startNavigationLogic() {
        serviceScope.launch {
            NavigationRepository.gpsLocation.collectLatest { location: Location? ->
                if (location == null) return@collectLatest
                
                // Tự động nhận diện tốc độ giới hạn thông minh (Overpass / HERE / TomTom + Spatial Grid Caching)
                if (PrefsHelper.getBoolean(this@NavigationService, "auto_speed_limit", true)) {
                    SpeedLimitEngine.fetchSpeedLimit(this@NavigationService, location) { result ->
                        if (result != null && result.speedLimit > 0) {
                            PrefsHelper.putInt(this@NavigationService, "speed_threshold", result.speedLimit)
                        } else {
                            val manualLimit = PrefsHelper.getInt(this@NavigationService, "manual_speed_threshold", 60)
                            PrefsHelper.putInt(this@NavigationService, "speed_threshold", manualLimit)
                        }
                    }
                }

                val currentSpeedKmh = (location.speed * 3.6).toInt()
                if (PrefsHelper.getBoolean(this@NavigationService, "speed_warning", true)) {
                    val threshold = PrefsHelper.getInt(this@NavigationService, "speed_threshold", 60)
                    val intervalSec = PrefsHelper.getInt(this@NavigationService, "speed_warning_interval", 15)
                    val intervalMs = intervalSec * 1000L

                    if (currentSpeedKmh > threshold) {
                        val now = System.currentTimeMillis()

                        if (PrefsHelper.getBoolean(this@NavigationService, "voice_speed_warning", true)) {
                            if (now - lastVoiceSpeedWarningTime > intervalMs) {
                                lastVoiceSpeedWarningTime = now
                                ttsManager.speak("Cảnh báo: Bạn đang chạy $currentSpeedKmh kilômét trên giờ, vượt quá giới hạn $threshold kilômét trên giờ")
                            }
                        }

                        if (PrefsHelper.getBoolean(this@NavigationService, "esp_speed_warning", true)) {
                            if (now - lastEspSpeedWarningTime > intervalMs) {
                                lastEspSpeedWarningTime = now
                                bleManager.sendTrafficWarning(0x02.toByte(), threshold.toByte())
                            }
                        }
                    }
                }
                // Luôn đồng bộ tốc độ GPS sang BLE ở mọi chế độ (HUD, MAP, STATUS) để phát hiện quá tốc độ và hiển thị đồng hồ
                bleManager.writeSpeed(currentSpeedKmh)

                // Process Route tracking only if navigation is running
                if (NavigationRepository.navigationState.value) {
                    val routes = NavigationRepository.routes.value
                    val selectedRoute = routes.find { it.isSelected } ?: routes.firstOrNull()
                    if (selectedRoute != null) {
                        if (selectedRoute.polyline.isNotEmpty()) {
                            currentDestination = selectedRoute.polyline.last()
                        }
                        val offRouteThreshold = PrefsHelper.getFloat(this@NavigationService, "off_route_dist", 15f)
                        chaserEngine.setOffRouteThreshold(offRouteThreshold)
                        chaserEngine.checkDeviation(location, selectedRoute.polyline)

                        val source = PrefsHelper.getInt(this@NavigationService, "hud_source", 0)
                        if (source == 2 || source == 0) {
                            updateHudFromSteps(location, selectedRoute.steps)
                        }
                    }
                } else {
                    if (!isGmapsActive) {
                        currentDistanceToNextMeters = -1
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
        val source = PrefsHelper.getInt(this, "hud_source", 0)
        // Khi dang co thong bao Google Maps va nguon HUD dang chon Tu dong (0) hoac Google Maps (1),
        // khong ghi de du lieu HUD bang OSM steps
        if (isGmapsActive && (source == 0 || source == 1)) return
        
        // 1. Tìm bước closestIndex có điểm bắt đầu gần xe nhất
        var closestIndex = 0
        var minDistance = Double.MAX_VALUE
        for (i in steps.indices) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(location.latitude, location.longitude, steps[i].location.first, steps[i].location.second, results)
            val d = results[0].toDouble()
            if (d < minDistance) {
                minDistance = d
                closestIndex = i
            }
        }

        // 2. Xác định chặng mục tiêu tiếp theo (targetIndex = closestIndex + 1)
        var targetIndex = closestIndex + 1
        if (targetIndex >= steps.size) {
            targetIndex = steps.size - 1
        } else {
            // Kiểm tra xem xe đã đến rất gần điểm kết thúc của chặng hiện tại (điểm bắt đầu của targetIndex) chưa
            val results = FloatArray(1)
            android.location.Location.distanceBetween(location.latitude, location.longitude, steps[targetIndex].location.first, steps[targetIndex].location.second, results)
            val distToTarget = results[0]
            if (distToTarget <= 15 && targetIndex < steps.size - 1) {
                // Nếu cách ngã rẽ tiếp theo <= 15m, chuyển sang ngã rẽ sau đó nữa để chuẩn bị hiển thị chỉ dẫn mới
                targetIndex++
            }
        }

        val nextStep = steps[targetIndex]
        val distResults = FloatArray(1)
        android.location.Location.distanceBetween(location.latitude, location.longitude, nextStep.location.first, nextStep.location.second, distResults)
        val dist = distResults[0]
        currentDistanceToNextMeters = dist.toInt()
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

    private fun startWeatherSync() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                fetchAndSyncWeatherNow()
                delay(300_000) // Định kỳ cập nhật mỗi 5 phút
            }
        }
    }

    fun fetchAndSyncWeatherNow() {
        serviceScope.launch(Dispatchers.IO) {
            val location = NavigationRepository.gpsLocation.value ?: return@launch
            try {
                val weatherApiKey = PrefsHelper.getSecureString(this@NavigationService, "api_key_weatherapi", "").trim()
                var tempC: Float? = null
                var iconCode: String? = null

                // 1. Thử WeatherAPI.com nếu người dùng có key
                if (weatherApiKey.isNotEmpty()) {
                    try {
                        val url = "https://api.weatherapi.com/v1/current.json?key=$weatherApiKey&q=${location.latitude},${location.longitude}&lang=vi"
                        val req = Request.Builder().url(url).build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val json = org.json.JSONObject(resp.body.string())
                                val current = json.optJSONObject("current")
                                if (current != null) {
                                    tempC = current.optDouble("temp_c", 28.0).toFloat()
                                    val code = current.optJSONObject("condition")?.optInt("code", 1000) ?: 1000
                                    iconCode = mapWeatherApiCode(code)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("NavigationService", "WeatherAPI sync failed, falling back to Open-Meteo: ${e.message}")
                    }
                }

                // 2. Fallback sang Open-Meteo Multi-Model (ECMWF & JMA)
                if (tempC == null) {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current_weather=true&models=best_match,ecmwf_ifs025,jma_gsm"
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        val body = response.body.string()
                        if (response.isSuccessful && body.startsWith("{")) {
                            val json = gson.fromJson(body, WeatherResponse::class.java)
                            tempC = json.current_weather.temperature
                            iconCode = mapWeatherCode(json.current_weather.weathercode)
                        }
                    }
                }

                if (tempC != null && iconCode != null) {
                    bleManager.writeWeather(gson.toJson(mapOf(
                        "t" to tempC,
                        "i" to iconCode
                    )))
                    android.util.Log.d("NavigationService", "BLE Weather Synced: ${tempC}°C, icon: $iconCode")
                }
            } catch (e: Exception) {
                android.util.Log.e("NavigationService", "Weather Sync Exception: ${e.message}")
            }
        }
    }

    private fun mapWeatherApiCode(code: Int): String {
        return when (code) {
            1000 -> "01d" // Nắng / quang đãng
            1003 -> "02d" // Ít mây
            1006, 1009 -> "04d" // Nhiều mây / U ám
            1030, 1135, 1147 -> "50d" // Sương mù
            1087, 1273, 1276, 1279, 1282 -> "11d" // Sấm sét / Dông bão
            1063, 1150, 1153, 1180, 1183, 1186, 1189, 1192, 1195, 1240, 1243, 1246 -> "10d" // Mưa
            1066, 1114, 1210, 1213, 1216, 1219, 1222, 1225 -> "13d" // Tuyết
            else -> "02d"
        }
    }

    private fun startTimeSync() {
        serviceScope.launch {
            while (isActive) {
                // Định kỳ 10 phút đồng bộ thời gian một lần vì ESP32 đã tích hợp thư viện ESP32Time tự đếm giờ bằng RTC phần cứng
                delay(600_000)
                if (bleManager.isConnected) {
                    bleManager.syncTime()
                }
            }
        }
    }

    private fun startSpeedWatchdogLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000L)
                if (!bleManager.isConnected) continue
                val lastLoc = NavigationRepository.gpsLocation.value
                val now = System.currentTimeMillis()
                // Nếu quá 3.5 giây không nhận được điểm GPS mới (xe dừng đèn đỏ, mất sóng GPS tạm thời), đưa tốc độ về 0
                if (lastLoc == null || (now - lastLoc.time > 3500L)) {
                    NavigationRepository.updateSpeed(0)
                    bleManager.writeSpeed(0)
                } else {
                    // Heartbeat giữ liên lạc và đảm bảo đồng hồ OLED luôn nhận được tốc độ
                    bleManager.writeSpeed(NavigationRepository.currentSpeedKmh.value)
                }
            }
        }
    }

    private fun mapWeatherCode(code: Int): String {
        return when (code) {
            0 -> "01d" // Nắng / quang đãng
            1, 2 -> "02d" // Ít mây / Mây rải rác
            3 -> "04d" // Nhiều mây / U ám
            45, 48 -> "50d" // Sương mù
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> "10d" // Mưa phùn / Mưa rào
            71, 73, 75, 85, 86 -> "13d" // Tuyết
            95, 96, 99 -> "11d" // Sấm sét / Dông bão
            else -> "02d"
        }
    }

    data class WeatherResponse(val current_weather: CurrentWeather)
    data class CurrentWeather(val temperature: Float, val weathercode: Int)

    private fun isGoogleMapsForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        val tasks = am.getRunningTasks(1)
        return tasks.isNotEmpty() && tasks[0].topActivity?.packageName == "com.google.android.apps.maps"
    }

    private fun connectToMac(mac: String, useAutoConnect: Boolean = false) {
        try {
            isManualDisconnect = false
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val device = bluetoothManager.adapter.getRemoteDevice(mac)
            
            val state = NavigationRepository.bleConnectionState.value
            // Chỉ bỏ qua nếu ĐÃ kết nối và ở trạng thái Ready với đúng thiết bị này
            if (bleManager.bluetoothDevice == device && state == NavigationRepository.BleConnectionState.Ready) {
                return
            }
            
            if (bleManager.bluetoothDevice != null) {
                try {
                    bleManager.disconnect().enqueue()
                } catch (e: Exception) {}
            }

            NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Connecting)
            
            bleManager.connect(device)
                .timeout(10000)
                .retry(2, 500)
                .useAutoConnect(useAutoConnect)
                .fail { _, status ->
                    NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Disconnected)
                    NavigationRepository.addLog("Kết nối BLE thất bại (Mã lỗi: $status)")
                }
                .enqueue()
            val deviceName = try { device.name ?: "Thiết bị không tên" } catch (e: SecurityException) { "Thiết bị" }
            NavigationRepository.addLog("Đang kết nối tới $deviceName ($mac)...")
        } catch (e: Exception) {
            NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Disconnected)
            NavigationRepository.addLog("Lỗi kết nối: ${e.message}")
        }
    }

    private fun autoConnectBle() {
        if (isManualDisconnect) return
        val state = NavigationRepository.bleConnectionState.value
        if (state != NavigationRepository.BleConnectionState.Disconnected) return
        val lastMac = PrefsHelper.getString(this, "last_device_mac", "").trim()
        val targetMac = if (lastMac.length == 17) {
            lastMac
        } else {
            val history = PrefsHelper.getPairedHistory(this)
            history.firstOrNull()?.substringAfter("(")?.substringBefore(")")?.trim() ?: ""
        }
        if (targetMac.length == 17) {
            android.util.Log.d("NavigationService", "Auto-connecting instantly to $targetMac")
            connectToMac(targetMac, useAutoConnect = false)
        }
    }

    private fun startMapRenderingLoop() {
        serviceScope.launch(Dispatchers.IO) {
            var lastSentMapImageHash = -1L
            var wasTileStreamingActive = false
            var adaptiveQualityPenalty = 0 // Tự động giảm chất lượng nếu BLE/CPU bị quá tải
            var averageWriteDurationMs = 250L // Dynamic BLE transfer duration tracker

            while (isActive) {
                val loopStartTime = System.currentTimeMillis()
                val captureMode = PrefsHelper.getInt(this@NavigationService, "map_capture_mode", 0)
                val isAppNavigating = NavigationRepository.navigationState.value
                // Stream bản đồ khi đang ở MAP_MODE (0x11) hoặc MAP_HUD_MODE (0x13), hoặc khi có yêu cầu dẫn đường Map
                val isMapActive = isMapModeActive || NavigationRepository.navigationState.value
                val isCurrentModeMap = (currentAppliedMode == 0x11.toByte() || currentAppliedMode == 0x13.toByte())
                val isOsmOnlyInMode5 = captureMode == 5 && !isGmapsActive && isAppNavigating
                val isContinuousMap = (captureMode == 0) && (isMapModeActive || (isAppNavigating && !isGmapsActive))
                if (isCurrentModeMap || isMapActive || isOsmOnlyInMode5 || (isContinuousMap && bleManager.isConnected)) {
                    val fpsVal = PrefsHelper.getInt(this@NavigationService, "map_fps", 0)
                    val loc = NavigationRepository.gpsLocation.value
                    
                    val baseFps = if (fpsVal == 0) {
                        // Smart Mode: Giới hạn an toàn tối đa 4.5 FPS khi tới ngã rẽ để đảm bảo băng thông BLE ổn định
                        val speedKmH = (loc?.speed ?: 0f) * 3.6f
                        val distToNext = currentDistanceToNextMeters
                        when {
                            speedKmH < 1f -> 1.0 // 1 FPS khi dừng xe
                            distToNext > 500 -> 1.5 // 1.5 FPS khi ở xa
                            distToNext > 200 -> 2.5 // 2.5 FPS khi sắp đến
                            else -> 4.5 // 4.5 FPS khi đang rẽ
                        }
                    } else {
                        // Max Mode (Giới hạn tối đa 6.0 FPS để không làm nghẽn hàng đợi BLE)
                        6.0
                    }

                    // Limit FPS based on measured BLE write duration (with 0.80 safety factor to avoid queue congestion)
                    val bleMaxFps = (1000.0 / averageWriteDurationMs) * 0.80
                    val fps = minOf(baseFps, bleMaxFps).coerceIn(0.2, 6.0)

                    // 1. Đọc chất lượng do người dùng cấu hình từ PrefsHelper
                    val userQuality = PrefsHelper.getFloat(this@NavigationService, "jpeg_quality", 60f).toInt()
                    
                    // 2. Tính toán chất lượng thực tế theo Cơ chế an toàn (Adaptive Load Safety)
                    val effectiveQuality = (userQuality - adaptiveQualityPenalty).coerceIn(15, 100)
                    
                    val deviceDisplay = NavigationRepository.deviceStatus.value["display"] ?: ""
                    val isOled = deviceDisplay.contains("OLED", ignoreCase = true) || 
                                 deviceDisplay.contains("SSD1306", ignoreCase = true) || 
                                 deviceDisplay.contains("SH1106", ignoreCase = true)
                    val isTileStreamingEnabled = PrefsHelper.getBoolean(this@NavigationService, "tile_streaming", false)
                    val isTileStreamingActive = isTileStreamingEnabled && !isOled && captureMode == 3

                    if (isTileStreamingActive) {
                        wasTileStreamingActive = true
                        try {
                            runTileStreamingLoop(effectiveQuality)
                        } catch (e: Exception) {
                            android.util.Log.e("NavigationService", "Tile streaming loop error: ${e.message}", e)
                        }
                        delay(2000)
                        continue
                    } else {
                        if (wasTileStreamingActive) {
                            wasTileStreamingActive = false
                            try {
                                bleManager.writeMapCtrl(0x03.toByte(), ByteArray(0))
                            } catch (e: Exception) {}
                        }
                    }
                    
                    var imageBytes: ByteArray? = null
                    if (isOled) {
                        // Cho màn hình OLED: Luôn ưu tiên cơ chế render trực tiếp từ Tab Map (OSM headless map)
                        imageBytes = renderOsmMap(effectiveQuality)
                    } else if (captureMode == 4) {
                        try {
                            val loc = NavigationRepository.gpsLocation.value
                            val speed = loc?.speed ?: 0f
                            val heading = if (speed > 1.2f) {
                                loc?.bearing ?: 0f
                            } else {
                                NavigationRepository.compassHeading.value
                            }
                            val zoom = NavigationRepository.lastMapZoom
                            imageBytes = roadsOnlyMapRenderer?.render(headlessMapView, loc, heading, zoom, effectiveQuality)
                        } catch (e: Exception) {
                            android.util.Log.e("NavigationService", "Roads Only rendering error: ${e.message}", e)
                        }
                    } else if (captureMode == 1) {
                        imageBytes = screenCaptureManager?.captureAndProcess(effectiveQuality, "gmaps_")
                        if (imageBytes == null) imageBytes = renderOsmMap(effectiveQuality)
                    } else if (captureMode == 2) {
                        if (isGoogleMapsForeground()) {
                            imageBytes = screenCaptureManager?.captureAndProcess(effectiveQuality, "gmaps_")
                        }
                        if (imageBytes == null) imageBytes = renderOsmMap(effectiveQuality)
                    } else if (captureMode == 5 && (isMapModeActive || !isGmapsActive)) {
                        val hasCropMapTab = PrefsHelper.getInt(this@NavigationService, "crop_w_maptab", 0) > 0
                        if (hasCropMapTab && screenCaptureManager?.isCapturing == true) {
                            imageBytes = screenCaptureManager?.captureAndProcess(effectiveQuality, "maptab_")
                        }
                        if (imageBytes == null) {
                            imageBytes = renderOsmMap(effectiveQuality)
                        }
                    } else {
                        // Mode 0 hoặc mặc định: Render trực tiếp Map ngầm theo dịch vụ bản đồ Tab Map
                        imageBytes = renderOsmMap(effectiveQuality)
                    }

                    if (imageBytes != null) {
                        val imageHash = calculateCRC32(imageBytes)
                        val frameSkippingEnabled = PrefsHelper.getBoolean(this@NavigationService, "frame_skipping", true) || fps >= 5
                        if (!frameSkippingEnabled || imageHash != lastSentMapImageHash) {
                            lastSentMapImageHash = imageHash
                            val writeStart = System.currentTimeMillis()
                            if (isOled) {
                                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                if (bitmap != null) {
                                    NavigationRepository.updateOledBaseMap(bitmap)
                                    bleManager.writeOledImage(convertToOled1Bit(bitmap))
                                }
                            } else {
                                bleManager.writeMapImage(imageBytes)
                            }
                            val writeDuration = System.currentTimeMillis() - writeStart
                            if (writeDuration > 10) {
                                averageWriteDurationMs = (averageWriteDurationMs * 0.85 + writeDuration * 0.15).toLong().coerceIn(100L, 2000L)
                            }
                        } else {
                            android.util.Log.d("NavigationService", "Map image is identical, skipping transmission to save bandwidth.")
                        }
                    }
                    
                    val delayMs = (1000 / fps).toLong()

                    // 3. Cơ chế an toàn Chống Quá Tải (Adaptive Load Safety):
                    val elapsedTimeMs = System.currentTimeMillis() - loopStartTime
                    val targetPeriodMs = (1000 / fps).toLong()
                    if (elapsedTimeMs > targetPeriodMs + 60) {
                        if (adaptiveQualityPenalty < 40) {
                            adaptiveQualityPenalty += 5
                            NavigationRepository.addLog("AUTOSAFE: BLE/CPU quá tải ($elapsedTimeMs ms > target $targetPeriodMs ms). Tự động hạ JPEG Quality -> $effectiveQuality%")
                        }
                    } else if (elapsedTimeMs < targetPeriodMs * 0.75 && adaptiveQualityPenalty > 0) {
                        adaptiveQualityPenalty = (adaptiveQualityPenalty - 2).coerceAtLeast(0)
                    }

                    delay(delayMs)
                } else { 
                    lastSentMapImageHash = -1L // Reset hash when map mode is inactive
                    adaptiveQualityPenalty = 0
                    delay(2000) 
                }
            }
        }
    }

    // Map Sources
    private val mapCnDark = object : XYTileSource("CartoDB Dark Matter", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/"),
        "© OpenStreetMap contributors, © CARTO") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val base = super.getTileURLString(pMapTileIndex)
            val key = try { PrefsHelper.getSecureString(this@NavigationService, "api_key_carto", "") } catch (e: Exception) { "" }
            return if (key.isNotEmpty()) "$base?api_key=$key" else base
        }
    }

    private val mapCnPositron = object : XYTileSource("CartoDB Positron", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/light_all/", "https://b.basemaps.cartocdn.com/light_all/", "https://c.basemaps.cartocdn.com/light_all/"),
        "© OpenStreetMap contributors, © CARTO") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val base = super.getTileURLString(pMapTileIndex)
            val key = try { PrefsHelper.getSecureString(this@NavigationService, "api_key_carto", "") } catch (e: Exception) { "" }
            return if (key.isNotEmpty()) "$base?api_key=$key" else base
        }
    }

    private val mapCnVoyager = object : XYTileSource("CartoDB Voyager", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/rastertiles/voyager/", "https://b.basemaps.cartocdn.com/rastertiles/voyager/", "https://c.basemaps.cartocdn.com/rastertiles/voyager/"),
        "© OpenStreetMap contributors, © CARTO") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val base = super.getTileURLString(pMapTileIndex)
            val key = try { PrefsHelper.getSecureString(this@NavigationService, "api_key_carto", "") } catch (e: Exception) { "" }
            return if (key.isNotEmpty()) "$base?api_key=$key" else base
        }
    }

    private val googleMapsDark = object : XYTileSource("Google Maps Dark", 1, 20, 256, "",
        arrayOf("https://mt1.google.com/vt/lyrs=m"),
        "© Google") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            val style = "s.t:1|s.e:g|p.c:#ff242f3e,s.t:1|s.e:l.t.f|p.c:#ff746855,s.t:1|s.e:l.t.s|p.c:#ff242f3e,s.t:3|s.e:g.f|p.c:#ff242f3e,s.t:3|s.e:l.t.f|p.c:#ff746855,s.t:4|s.e:g.f|p.c:#ff212a37,s.t:5|s.e:g.f|p.c:#ff38414e,s.t:5|s.e:g.s|p.c:#ff212a37,s.t:5|s.e:l.t.f|p.c:#ff9ca5b3,s.t:6|s.e:g.f|p.c:#ff746855,s.t:6|s.e:g.s|p.c:#ff242f3e,s.t:6|s.e:l.t.f|p.c:#ffd59563,s.t:81|s.e:g.f|p.c:#ff17263c,s.t:82|s.e:g.f|p.c:#ff1f2835,s.t:82|s.e:l.t.f|p.c:#ff515c6d,s.t:82|s.e:l.t.s|p.c:#ff1f2835"
            val encodedStyle = android.net.Uri.encode(style)
            return "https://mt1.google.com/vt/lyrs=m&x=$x&y=$y&z=$z&apistyle=$encodedStyle"
        }
    }

    private val googleMaps = object : XYTileSource("Google Maps", 1, 20, 256, "",
        arrayOf("https://mt1.google.com/vt/lyrs=m"),
        "© Google") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "https://mt1.google.com/vt/lyrs=m&x=$x&y=$y&z=$z"
        }
    }

    private val googleMapsSatellite = object : XYTileSource("Google Satellite", 1, 20, 256, "",
        arrayOf("https://mt1.google.com/vt/lyrs=s"),
        "© Google") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "https://mt1.google.com/vt/lyrs=s&x=$x&y=$y&z=$z"
        }
    }

    private val googleMapsHybrid = object : XYTileSource("Google Hybrid", 1, 20, 256, "",
        arrayOf("https://mt1.google.com/vt/lyrs=y"),
        "© Google") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "https://mt1.google.com/vt/lyrs=y&x=$x&y=$y&z=$z"
        }
    }

    private val osmStandard = XYTileSource("OpenStreetMap", 1, 19, 256, ".png",
        arrayOf("https://tile.openstreetmap.org/"),
        "© OpenStreetMap contributors")

    private val transportMap = object : XYTileSource("OSM Transport Map", 1, 18, 256, ".png",
        arrayOf("https://tile.memomaps.de/tilegen/"),
        "© OpenStreetMap, © memomaps.de") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            val tfKey = try { PrefsHelper.getSecureString(this@NavigationService, "api_key_thunderforest", "") } catch (e: Exception) { "" }
            return if (tfKey.isNotBlank()) {
                "https://tile.thunderforest.com/transport/$z/$x/$y.png?apikey=$tfKey"
            } else {
                "https://tile.memomaps.de/tilegen/$z/$x/$y.png"
            }
        }
    }

    private val osmVectorRoads = object : XYTileSource(
        "Vector OpenStreetMap (OLED) ⭐",
        1, 16, 256, ".mvt",
        arrayOf("https://vector.openstreetmap.org/shortbread_v1/"),
        "© OpenStreetMap contributors"
    ) {
        override fun getDrawable(aTileInputStream: java.io.InputStream?): android.graphics.drawable.Drawable? {
            if (aTileInputStream == null) return null
            return try {
                val bitmap = com.example.tymap.util.OsmVectorTileDecoder.decodeMvtToBitmap(aTileInputStream, 256)
                org.osmdroid.tileprovider.ReusableBitmapDrawable(bitmap)
            } catch (e: Exception) {
                null
            }
        }
    }

    private val cartoDarkNoLabels = object : XYTileSource(
        "CartoDB Dark No Labels",
        1, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/rastertiles/dark_nolabels/",
            "https://b.basemaps.cartocdn.com/rastertiles/dark_nolabels/",
            "https://c.basemaps.cartocdn.com/rastertiles/dark_nolabels/"
        ),
        "© OpenStreetMap, © CARTO"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val base = super.getTileURLString(pMapTileIndex)
            val key = try { PrefsHelper.getSecureString(this@NavigationService, "api_key_carto", "") } catch (e: Exception) { "" }
            return if (key.isNotEmpty()) "$base?api_key=$key" else base
        }
    }

    private val stamenTonerLines = object : XYTileSource(
        "Stamen Toner Lines (OSM)",
        1, 20, 256, ".png",
        arrayOf("https://tiles.stadiamaps.com/tiles/stamen_toner_lines/"),
        "© OpenStreetMap, © Stadia Maps, © Stamen Design"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val base = super.getTileURLString(pMapTileIndex)
            val key = try { PrefsHelper.getSecureString(this@NavigationService, "api_key_stadia", "") } catch (e: Exception) { "" }
            return if (key.isNotEmpty()) "$base?api_key=$key" else base
        }
    }

    private fun getTileSources(): List<ITileSource> {
        val list = mutableListOf<ITileSource>()
        list.add(mapCnDark)           // 0: CartoDB Dark Matter
        list.add(mapCnPositron)       // 1: CartoDB Positron
        list.add(mapCnVoyager)        // 2: CartoDB Voyager
        list.add(googleMaps)          // 3: Google Maps (MT)
        list.add(googleMapsDark)      // 4: Google Maps Dark (MT)
        list.add(googleMaps)          // 5: Google Maps Đảo Màu (MT Invert)
        list.add(googleMapsSatellite) // 6: Google Maps Satellite (MT)
        list.add(googleMapsHybrid)    // 7: Google Maps Hybrid (MT)
        list.add(osmStandard)         // 8: OpenStreetMap Chuẩn
        list.add(transportMap)        // 9: OSM Transport Map
        list.add(osmVectorRoads)      // 10: Vector OpenStreetMap (OLED) ⭐
        list.add(cartoDarkNoLabels)   // 11: CartoDB Dark No Labels
        list.add(stamenTonerLines)    // 12: Stamen Toner Lines (OSM)

        val customUrl = PrefsHelper.getString(this, "custom_tile_url", "")
        if (customUrl.isNotEmpty() && customUrl.contains("{z}")) {
            try {
                val baseUrl = customUrl.substringBefore("{z}")
                val ext = "." + customUrl.substringAfterLast(".")
                list.add(XYTileSource("Tùy chỉnh", 1, 20, 256, ext, arrayOf(baseUrl), "Custom"))
            } catch (e: Exception) {
                list.add(osmStandard)
            }
        } else {
            list.add(XYTileSource("Tùy chỉnh (Chưa cấu hình)", 1, 20, 256, ".png", arrayOf("https://tile.openstreetmap.org/"), "Custom"))
        }
        return list
    }

    suspend fun renderOsmMap(quality: Int): ByteArray? {
        val location = NavigationRepository.gpsLocation.value
        
        // Capture size is 240x240 for ESP32
        // We render a larger area then crop if configured
        val renderWidth = 480
        val renderHeight = 800 // standard-ish aspect ratio
        
        val bitmap: Bitmap
        try {
            bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.RGB_565)
        } catch (t: Throwable) {
            android.util.Log.e("NavigationService", "OOM creating base map bitmap: ${t.message}")
            return null
        }
        val canvas = Canvas(bitmap)
        try {
                // 1. Prepare simplified polylines on IO thread to keep Main thread responsive
            val zoom = NavigationRepository.lastMapZoom
            val tolerance = 3.0 * (360.0 / (256.0 * Math.pow(2.0, zoom)))
            val routes = NavigationRepository.routes.value
            
            val simplifiedRoutes = routes.map { route ->
                route to com.example.tymap.utils.PolylineDecoder.simplify(route.polyline, tolerance)
            }

            withContext(Dispatchers.Main) {
                // Update Tile Source based on user setting
                val deviceDisplay = NavigationRepository.deviceStatus.value["display"] ?: ""
                val isOledDevice = deviceDisplay.contains("OLED", ignoreCase = true) || 
                                   deviceDisplay.contains("SSD1306", ignoreCase = true) || 
                                   deviceDisplay.contains("SH1106", ignoreCase = true)

                val tileSourceIndex = PrefsHelper.getInt(this@NavigationService, "tile_source", 0)
                val tileSources = getTileSources()
                val selectedTileSource = if (isOledDevice && tileSourceIndex == 0) stamenTonerLines else (tileSources.getOrNull(tileSourceIndex) ?: TileSourceFactory.MAPNIK)
                if (headlessMapView?.tileProvider?.tileSource != selectedTileSource) {
                    headlessMapView?.setTileSource(selectedTileSource)
                }
                
                if (isOledDevice || selectedTileSource == stamenTonerLines) {
                    // Stamen Toner Lines Đảo Màu: Nền trắng -> Đen (0), Nét đường viền đen -> Trắng 1-pixel (255)
                    val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
                        -1.0f, 0.0f, 0.0f, 0.0f, 255f,
                        0.0f, -1.0f, 0.0f, 0.0f, 255f,
                        0.0f, 0.0f, -1.0f, 0.0f, 255f,
                        0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                    ))
                    headlessMapView?.overlayManager?.tilesOverlay?.setColorFilter(android.graphics.ColorMatrixColorFilter(colorMatrix))
                } else if (tileSourceIndex == 5) {
                    val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
                        -1.0f, 0.0f, 0.0f, 0.0f, 255f,
                        0.0f, -1.0f, 0.0f, 0.0f, 255f,
                        0.0f, 0.0f, -1.0f, 0.0f, 255f,
                        0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                    ))
                    headlessMapView?.overlayManager?.tilesOverlay?.setColorFilter(android.graphics.ColorMatrixColorFilter(colorMatrix))
                } else {
                    headlessMapView?.overlayManager?.tilesOverlay?.setColorFilter(null)
                }

                // Clear old overlays except reused markers to avoid memory bloat
                val overlays = headlessMapView?.overlays
                if (overlays != null) {
                    val toRemove = overlays.filter { it != headlessUserMarker && it != headlessDestMarker }
                    overlays.removeAll(toRemove)
                }

                if (simplifiedRoutes.isNotEmpty()) {
                    val unselected = simplifiedRoutes.filter { !it.first.isSelected }
                    val selected = simplifiedRoutes.filter { it.first.isSelected }
                    
                    (unselected + selected).forEach { (route, points) ->
                        if (points.size >= 2) {
                            val polyline = Polyline(headlessMapView).apply {
                                setPoints(points.map { org.osmdroid.util.GeoPoint(it.first, it.second) })
                                outlinePaint.isAntiAlias = true
                                outlinePaint.color = if (isOledDevice) {
                                    if (route.isSelected) Color.WHITE else Color.parseColor("#808080")
                                } else {
                                    if (route.isSelected) Color.parseColor("#007AFF") else Color.parseColor("#8E8E93")
                                }
                                outlinePaint.strokeWidth = if (isOledDevice) {
                                    if (route.isSelected) 22f else 14f
                                } else {
                                    if (route.isSelected) 18f else 12f
                                }
                                outlinePaint.alpha = if (route.isSelected) 255 else 180
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                                outlinePaint.strokeJoin = Paint.Join.ROUND
                            }
                            headlessMapView?.overlays?.add(polyline)
                        }
                    }
                }

                // 2. Update map center and orientation based on current location
                val isTrackUp = NavigationRepository.isTrackUpMode.value
                location?.let {
                    headlessMapView?.controller?.setCenter(org.osmdroid.util.GeoPoint(it.latitude, it.longitude))
                    
                    val speed = it.speed
                    val isMoving = speed > 1.5f && it.hasBearing()
                    val heading = if (isMoving) {
                        it.bearing
                    } else {
                        NavigationRepository.compassHeading.value
                    }

                    if (isTrackUp) {
                        headlessMapView?.mapOrientation = -heading
                    } else {
                        headlessMapView?.mapOrientation = 0f
                    }
                }
                
                // Set zoom level from repository (synchronized instantly from MapFragment)
                headlessMapView?.controller?.setZoom(NavigationRepository.lastMapZoom)
                
                // 3. Draw user position marker (Blue Dot or OLED Direction Arrow)
                location?.let { loc ->
                    var marker = headlessUserMarker
                    if (marker == null) {
                        marker = Marker(headlessMapView).apply {
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = if (isOledDevice) createOledUserIcon(this@NavigationService) else createUserIcon(this@NavigationService)
                            setFlat(false)
                            infoWindow = null
                        }
                        headlessUserMarker = marker
                    } else {
                        marker.icon = if (isOledDevice) createOledUserIcon(this@NavigationService) else createUserIcon(this@NavigationService)
                    }
                    marker.position = org.osmdroid.util.GeoPoint(loc.latitude, loc.longitude)
                    
                    if (isTrackUp) {
                        marker.rotation = 0f // Hướng lên trên (12h) vì bản đồ đã xoay
                    } else {
                        val speed = loc.speed
                        val isMoving = speed > 1.5f && loc.hasBearing()
                        val heading = if (isMoving) {
                            loc.bearing
                        } else {
                            NavigationRepository.compassHeading.value
                        }
                        marker.rotation = heading
                    }

                    if (headlessMapView?.overlays?.contains(marker) == false) {
                        headlessMapView?.overlays?.add(marker)
                    }
                }
                
                // 4. Draw destination marker if available
                currentDestination?.let { dest ->
                    var marker = headlessDestMarker
                    if (marker == null) {
                        marker = Marker(headlessMapView).apply {
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = ContextCompat.getDrawable(this@NavigationService, R.drawable.ic_red_pin)
                            infoWindow = null
                        }
                        headlessDestMarker = marker
                    } else {
                        marker.icon = ContextCompat.getDrawable(this@NavigationService, R.drawable.ic_red_pin)
                    }
                    marker.position = org.osmdroid.util.GeoPoint(dest.first, dest.second)
                    if (headlessMapView?.overlays?.contains(marker) == false) {
                        headlessMapView?.overlays?.add(marker)
                    }
                }

                // Resize headless map view for rendering
                headlessMapView?.layout(0, 0, renderWidth, renderHeight)
                headlessMapView?.draw(canvas)
            }

            // Apply crop configuration for Map Tab
            val prefs = getSharedPreferences("tymap_settings", Context.MODE_PRIVATE)
            val normX = prefs.getFloat("map_tab_crop_x_norm", 0f)
            val normY = prefs.getFloat("map_tab_crop_y_norm", 0f)
            val normSize = prefs.getFloat("map_tab_crop_size_norm", 0.4f).coerceAtLeast(0.01f)

            val absX = (normX * renderWidth).toInt()
            val absY = (normY * renderHeight).toInt()
            val absSize = (normSize * renderWidth).toInt().coerceAtLeast(1)

            val safeX = absX.coerceIn(0, (renderWidth - absSize).coerceAtLeast(0))
            val safeY = absY.coerceIn(0, (renderHeight - absSize).coerceAtLeast(0))
            
            val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, absSize, absSize)
            
            // Giảm tải RAM: Chỉ sao chép Bitmap gửi Preview UI tối đa 1 lần mỗi 1.5 giây để tránh OOM
            val now = System.currentTimeMillis()
            if (now - lastMapPreviewUpdateTime > 1500L) {
                lastMapPreviewUpdateTime = now
                try {
                    val fullMapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.RGB_565, false)
                    val croppedMapCopy = cropped.copy(cropped.config ?: Bitmap.Config.RGB_565, false)
                    NavigationRepository.updateMapPreviewInfo(
                        NavigationRepository.MapPreviewInfo(
                            fullMap = fullMapCopy,
                            cropX = safeX,
                            cropY = safeY,
                            cropSize = absSize,
                            croppedMap = croppedMapCopy
                        )
                    )
                } catch (e: Throwable) {
                    android.util.Log.e("NavigationService", "Error copying preview maps: ${e.message}")
                }
            }
            try {
                val scaled = Bitmap.createScaledBitmap(cropped, 240, 240, true)
                try {
                    val outputStream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    return outputStream.toByteArray()
                } finally {
                    if (scaled != cropped && scaled != bitmap && !scaled.isRecycled) scaled.recycle()
                }
            } finally {
                if (cropped != bitmap && !cropped.isRecycled) cropped.recycle()
            }
        } catch (t: Throwable) {
            android.util.Log.e("NavigationService", "Error in renderOsmMap: ${t.message}", t)
            return null
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun createOledUserIcon(context: Context): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        val size = (120 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        
        // Vòng ngoài màu đen tạo độ tương phản cao chống lẫn với polyline
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4 * density
        canvas.drawCircle(center, center, 15 * density, paint)
        
        // Vòng tròn đặc màu trắng
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawCircle(center, center, 13 * density, paint)
        
        // Mũi tên định hướng góc nhọn màu đen bên trong chỉ hướng 12h
        paint.color = Color.BLACK
        paint.style = Paint.Style.FILL
        val path = android.graphics.Path().apply {
            moveTo(center, center - 10 * density)              // Đỉnh nhọn phía trước
            lineTo(center + 7 * density, center + 8 * density) // Cánh phải
            lineTo(center, center + 4 * density)               // Khuyết giữa
            lineTo(center - 7 * density, center + 8 * density) // Cánh trái
            close()
        }
        canvas.drawPath(path, paint)
        
        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    }

    private fun createUserIcon(context: Context): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        val size = (120 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        
        // 1. Draw Orientation Fan
        val fanRadius = size * 0.45f
        val gradient = RadialGradient(center, center, fanRadius,
            intArrayOf(Color.parseColor("#B01A73E8"), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        paint.shader = gradient
        canvas.drawArc(RectF(center - fanRadius, center - fanRadius, center + fanRadius, center + fanRadius), 240f, 60f, true, paint)
        
        // 2. Draw the Blue Dot
        paint.shader = null
        val dotSize = 22 * density
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, dotSize / 2f, paint)
        
        // blue_primary color replacement: use direct Color.parseColor("#1A73E8") to avoid resource loading issues
        paint.color = Color.parseColor("#1A73E8")
        canvas.drawCircle(center, center, dotSize / 2.8f, paint)
        
        paint.color = Color.parseColor("#40000000")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1 * density
        canvas.drawCircle(center, center, dotSize / 2f, paint)
        
        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    }

    suspend fun sendImageToDevice(imageBytes: ByteArray) {
        if (!bleManager.isConnected) return
        val deviceDisplay = NavigationRepository.deviceStatus.value["display"] ?: ""
        val isOled = deviceDisplay.contains("OLED", ignoreCase = true) || 
                     deviceDisplay.contains("SSD1306", ignoreCase = true) || 
                     deviceDisplay.contains("SH1106", ignoreCase = true)
        if (isOled) {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bitmap != null) {
                NavigationRepository.updateOledBaseMap(bitmap)
                bleManager.writeOledImage(convertToOled1Bit(bitmap))
            }
        } else {
            bleManager.writeMapImage(imageBytes)
        }
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

        for (y in 0 until 64) {
            for (x in 0 until 128) {
                val valToThreshold = pixels[y][x]
                var isOn = valToThreshold > threshold
                
                if (invert) {
                    isOn = !isOn
                }
                
                if (isOn) {
                    val byteIdx = (y / 8) * 128 + x
                    val bitPos = y % 8
                    buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl bitPos)).toByte()
                }
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
                if (icon1bpp != null && !IconUtils.is1bppEmpty(icon1bpp)) {
                    val currentHash = calculateCRC32(icon1bpp)
                    val currentHashHex = String.format("%08X", currentHash)
                    android.util.Log.d("BleManager", "observeIconRequests: Responding with icon bitmap (292 bytes), hash = $currentHashHex")
                    NavigationRepository.addLog("BLE: Responding with icon bitmap (292 bytes), hash = $currentHashHex")
                    bleManager.writeIconData(currentHash, icon1bpp)
                } else {
                    android.util.Log.e("BleManager", "observeIconRequests: ERROR - current icon1bpp is NULL or empty")
                    NavigationRepository.addLog("BLE: ERROR - Received icon_req but current icon1bpp is NULL or empty")
                }
            }
        }
    }

    private fun observeMapMode() {
        serviceScope.launch {
            NavigationRepository.mapModeState.collect { active ->
                isMapModeActive = active
                android.util.Log.d("NavigationService", "Map Mode Active changed: $active")
                evaluateAndApplyDisplayMode()
            }
        }
    }

    private fun triggerImmediateHudUpdate() {
        val lastHud = NavigationRepository.hudPreviewData.value ?: return
        if (lastHud.active) {
            val bleData = "active=1\nnav=${if (lastHud.isNavigation) 1 else 0}\ndist=${lastHud.distance}\ntitle=${lastHud.title}\nroad=${lastHud.directions}\ndir=${lastHud.iconIndex}\neta=${lastHud.eta}\nete=${lastHud.duration}"
            serviceScope.launch {
                bleManager.writeNavigationData(bleData)
                // Gửi lại tốc độ GPS hiện tại ngay lập tức
                val location = NavigationRepository.gpsLocation.value
                if (location != null) {
                    bleManager.writeSpeed((location.speed * 3.6).toInt())
                }
                // Gửi lại icon rẽ nếu có
                val icon: ByteArray? = lastHud.icon1bpp
                if (icon != null) {
                    val hash = calculateCRC32(icon)
                    lastSentIconHash = hash
                    bleManager.writeNavIconHash(String.format("%08X", hash))
                }
            }
        }
    }

    private fun observeNavigationState() {
        serviceScope.launch {
            NavigationRepository.navigationState.collect { running ->
                val captureMode = PrefsHelper.getInt(this@NavigationService, "map_capture_mode", 0)
                if (running) {
                    fetchAndSyncWeatherNow()
                    if (captureMode == 0 || captureMode == 4 || (captureMode == 5 && !isGmapsActive)) {
                        NavigationRepository.setMapModeActive(true)
                    }
                } else {
                    if (bleManager.isConnected && !isGmapsActive) {
                        val bleData = "active=0\nnav=0\ndist=\ntitle=\ndir=\neta="
                        bleManager.writeNavigationData(bleData)
                    }
                    NavigationRepository.setMapModeActive(false)
                }
                evaluateAndApplyDisplayMode()
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

                    // Send weather immediately upon ready
                    fetchAndSyncWeatherNow()
                    
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

                    // Đồng bộ chế độ hiển thị hiện tại ngay khi BLE kết nối thành công
                    evaluateAndApplyDisplayMode(force = true)
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
                if (!isManualDisconnect && NavigationRepository.bleConnectionState.value == NavigationRepository.BleConnectionState.Disconnected) {
                    val lastMac = PrefsHelper.getString(this@NavigationService, "last_device_mac", "")
                    if (lastMac.isNotEmpty()) {
                        android.util.Log.d("NavigationService", "Auto-reconnect loop triggering for $lastMac")
                        connectToMac(lastMac, useAutoConnect = true)
                    }
                }
            }
        }
    }

    fun getTileX(lon: Double, zoom: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    fun getTileY(lat: Double, zoom: Int): Int = floor((1.0 - ln(tan(lat * PI / 180.0) + 1.0 / Math.cos(lat * PI / 180.0)) / PI) / 2.0 * (1 shl zoom)).toInt()
    fun getTileXDouble(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * (1 shl zoom)
    fun getTileYDouble(lat: Double, zoom: Int): Double = (1.0 - ln(tan(lat * PI / 180.0) + 1.0 / Math.cos(lat * PI / 180.0)) / PI) / 2.0 * (1 shl zoom)

    suspend fun renderOsmTile(x: Int, y: Int, z: Int, quality: Int): ByteArray? {
        val renderSize = 256
        val bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        
        val n = 1 shl z
        val lonMin = x.toDouble() / n * 360.0 - 180.0
        val lonMax = (x + 1).toDouble() / n * 360.0 - 180.0
        val lonCenter = (lonMin + lonMax) / 2.0
        
        val latRadMin = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y.toDouble() / n)))
        val latRadMax = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 1).toDouble() / n)))
        val latCenter = (latRadMin + latRadMax) / 2.0 * 180.0 / Math.PI
        
        val centerPoint = org.osmdroid.util.GeoPoint(latCenter, lonCenter)
        
        try {
            withContext(Dispatchers.Main) {
                val tileSourceIndex = PrefsHelper.getInt(this@NavigationService, "tile_source", 0)
                val tileSources = getTileSources()
                val selectedTileSource = tileSources.getOrNull(tileSourceIndex) ?: org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
                if (headlessMapView?.tileProvider?.tileSource != selectedTileSource) {
                    headlessMapView?.setTileSource(selectedTileSource)
                }

                headlessMapView?.overlays?.clear()

                // 1. Vẽ tất cả lộ trình đi qua tile này
                val routes = NavigationRepository.routes.value
                if (routes.isNotEmpty()) {
                    val zoom = z.toDouble()
                    val tolerance = 3.0 * (360.0 / (256.0 * Math.pow(2.0, zoom)))
                    
                    val unselected = routes.filter { !it.isSelected }
                    val selected = routes.filter { it.isSelected }

                    (unselected + selected).forEach { route ->
                        val simplifiedPoints = com.example.tymap.utils.PolylineDecoder.simplify(route.polyline, tolerance)
                        if (simplifiedPoints.size >= 2) {
                            val polyline = Polyline(headlessMapView).apply {
                                setPoints(simplifiedPoints.map { org.osmdroid.util.GeoPoint(it.first, it.second) })
                                outlinePaint.isAntiAlias = true
                                outlinePaint.color = if (route.isSelected) Color.parseColor("#007AFF") else Color.parseColor("#8E8E93")
                                outlinePaint.strokeWidth = if (route.isSelected) 12f else 8f
                                outlinePaint.alpha = if (route.isSelected) 255 else 180
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                                outlinePaint.strokeJoin = Paint.Join.ROUND
                            }
                            headlessMapView?.overlays?.add(polyline)
                        }
                    }
                }

                // 2. Cấu hình bản đồ
                headlessMapView?.controller?.setCenter(centerPoint)
                headlessMapView?.controller?.setZoom(z.toDouble())
                headlessMapView?.mapOrientation = 0f
                
                // 3. Layout và vẽ
                headlessMapView?.layout(0, 0, renderSize, renderSize)
                headlessMapView?.draw(canvas)
            }

            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            return outputStream.toByteArray()
        } catch (e: Exception) {
            android.util.Log.e("NavigationService", "Error in renderOsmTile: ${e.message}", e)
            return null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun CoroutineScope.runTileStreamingLoop(quality: Int) {
        var lastActiveX = -1
        var lastActiveY = -1
        var lastActiveZ = -1
        val esp32Cache = mutableSetOf<String>()
        
        while (isActive) {
            val location = NavigationRepository.gpsLocation.value
            val isTileStreamingEnabled = PrefsHelper.getBoolean(this@NavigationService, "tile_streaming", false)
            if (location == null || !isMapModeActive || !isTileStreamingEnabled) {
                delay(1500)
                continue
            }
            
            val z = PrefsHelper.getInt(this@NavigationService, "default_zoom", 15)
            val curX = getTileX(location.longitude, z)
            val curY = getTileY(location.latitude, z)
            
            val xDouble = getTileXDouble(location.longitude, z)
            val yDouble = getTileYDouble(location.latitude, z)
            
            val px = ((xDouble - curX) * 256).toInt().coerceIn(0, 255)
            val py = ((yDouble - curY) * 256).toInt().coerceIn(0, 255)
            
            val speedKmh = (location.speed * 3.6).toInt()
            val bearingDeg = location.bearing.toInt()
            bleManager.writeGpsSpeedAndPosition(speedKmh, bearingDeg, px, py)

            // Broadcast vị trí tile trung tâm cho Tab Render
            NavigationRepository.updateTileStreamingCenter(curX, curY, z, px, py)
            
            if (curX != lastActiveX || curY != lastActiveY || z != lastActiveZ) {
                val params = java.nio.ByteBuffer.allocate(9).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(curX)
                    .putInt(curY)
                    .put(z.toByte())
                    .array()
                bleManager.writeMapCtrl(0x01.toByte(), params)
                
                lastActiveX = curX
                lastActiveY = curY
                lastActiveZ = z
            }
            
            val cacheText = NavigationRepository.deviceStatus.value["cache"] ?: ""
            if (cacheText.isNotEmpty()) {
                esp32Cache.clear()
                cacheText.split(",").forEach {
                    if (it.isNotEmpty()) {
                        esp32Cache.add("$it:$z")
                    }
                }
            }
            
            val tilesToSend = mutableListOf<Pair<Int, Int>>()
            tilesToSend.add(curX to curY)
            
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx != 0 || dy != 0) {
                        tilesToSend.add((curX + dx) to (curY + dy))
                    }
                }
            }
            
            for (tile in tilesToSend) {
                val tx = tile.first
                val ty = tile.second
                val tileKey = "$tx:$ty:$z"
                if (!esp32Cache.contains(tileKey)) {
                    val jpeg = renderOsmTile(tx, ty, z, quality)
                    if (jpeg != null) {
                        // Decode bitmap để hiển thị trên Tab Render (không ảnh hưởng BLE)
                        try {
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                            if (bmp != null) NavigationRepository.addStreamedTile(tx, ty, z, bmp)
                        } catch (e: Exception) { /* bỏ qua lỗi decode preview */ }

                        val header = ByteArray(7)
                        header[0] = (tx and 0xFF).toByte()
                        header[1] = (ty and 0xFF).toByte()
                        header[2] = z.toByte()

                        val size = jpeg.size
                        java.nio.ByteBuffer.wrap(header, 3, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(size)
                        
                        bleManager.writeMapTile(header, jpeg)
                        esp32Cache.add(tileKey)
                        
                        delay(300L)
                        break
                    }
                }
            }
            
            delay(1000)
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
        
        try {
            bleManager.writeMapCtrl(0x03.toByte(), ByteArray(0))
        } catch (e: Exception) {}
        
        bleManager.disconnect().enqueue()
        ttsManager.shutdown()
        
        try {
            screenCaptureManager?.stopCapture()
            mediaProjection?.stop()
        } catch (e: Exception) {
            android.util.Log.e("NavigationService", "Error stopping capture in onDestroy: ${e.message}")
        }
        screenCaptureManager = null
        mediaProjection = null
        
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                android.util.Log.d("NavigationService", "Released WakeLock")
            }
        } catch (e: Exception) {}

        NavigationRepository.setNavigationRunning(false)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("NavigationService", "onTaskRemoved: TYMAP Service remains active in background for continuous GPS & BLE")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Navigation Service Channel", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Luồng định vị và dẫn đường chạy nền cho TYMAP & ESP32"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TYMAP đang dẫn đường & kết nối ESP32")
            .setContentText("Dữ liệu GPS & TBT đang liên tục truyền sang màn hình xe...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
    }
}
