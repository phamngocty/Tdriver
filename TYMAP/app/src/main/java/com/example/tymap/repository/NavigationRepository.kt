package com.example.tymap.repository

import android.location.Location
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class RouteInfo(
    val polyline: List<Pair<Double, Double>>,
    val distance: Double,
    val duration: Double,
    val steps: List<StepInfo> = emptyList(),
    val isSelected: Boolean = false,
    val engineName: String = "OSRM"
)

data class StepInfo(
    val instruction: String,
    val distance: Double,
    val duration: Double,
    val maneuverIcon: Int, 
    val roadName: String,
    val location: Pair<Double, Double>
)

object NavigationRepository {
    // Current GPS Location - using StateFlow to ensure latest value is always available
    private val _gpsLocation = MutableStateFlow<Location?>(null)
    val gpsLocation: StateFlow<Location?> = _gpsLocation.asStateFlow()

    private val _currentSpeedKmh = MutableStateFlow(0)
    val currentSpeedKmh: StateFlow<Int> = _currentSpeedKmh.asStateFlow()

    data class TrafficAlert(
        val type: Byte,
        val speedLimit: Int,
        val distanceMeters: Int,
        val message: String
    )

    private val _currentSpeedLimit = MutableStateFlow<Int>(60)
    val currentSpeedLimit: StateFlow<Int> = _currentSpeedLimit.asStateFlow()

    private val _currentRoadName = MutableStateFlow<String>("")
    val currentRoadName: StateFlow<String> = _currentRoadName.asStateFlow()

    private val _trafficWarningPoints = MutableStateFlow<List<com.example.tymap.service.TrafficWarningPoint>>(emptyList())
    val trafficWarningPoints: StateFlow<List<com.example.tymap.service.TrafficWarningPoint>> = _trafficWarningPoints.asStateFlow()

    private val _activeTrafficAlert = MutableStateFlow<TrafficAlert?>(null)
    val activeTrafficAlert: StateFlow<TrafficAlert?> = _activeTrafficAlert.asStateFlow()

    fun updateSpeedLimit(speedLimit: Int, roadName: String = "") {
        if (speedLimit > 0) {
            _currentSpeedLimit.value = speedLimit
            _currentRoadName.value = roadName
        }
    }

    fun updateTrafficWarningPoints(points: List<com.example.tymap.service.TrafficWarningPoint>) {
        _trafficWarningPoints.value = points
    }

    fun triggerTrafficAlert(alert: TrafficAlert?) {
        _activeTrafficAlert.value = alert
    }

    private val _routes = MutableStateFlow<List<RouteInfo>>(emptyList())
    val routes = _routes.asStateFlow()

    private val _currentStep = MutableSharedFlow<StepInfo>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val currentStep = _currentStep.asSharedFlow()

    data class HudData(
        val active: Boolean = true,
        val isNavigation: Boolean = true,
        val hasIcon: Boolean = false,
        val distance: String = "",
        val duration: String = "",
        val eta: String = "",
        val title: String = "",       
        val directions: String = "",  
        val speed: String = "",
        val iconIndex: Int = 0,         // index từ mapManeuverToIcon()
        val icon1bpp: ByteArray? = null,
        val bitmapIcon: android.graphics.Bitmap? = null
    )
    private val _hudPreviewData = MutableStateFlow<HudData?>(null)
    val hudPreviewData = _hudPreviewData.asStateFlow()

    enum class BleConnectionState {
        Disconnected, Connecting, Connected, Ready
    }
    private val _bleConnectionState = MutableStateFlow(BleConnectionState.Disconnected)
    val bleConnectionState = _bleConnectionState.asStateFlow()

    private val _navigationState = MutableStateFlow(false)
    val navigationState = _navigationState.asStateFlow()

    private val _mapModeState = MutableStateFlow(false)
    val mapModeState = _mapModeState.asStateFlow()

    private val _isTrackUpMode = MutableStateFlow(false)
    val isTrackUpMode = _isTrackUpMode.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    // Map State Persistence
    var lastMapCenter: Pair<Double, Double> = 10.762622 to 106.660172
    var lastMapZoom: Double = 15.0
    var lastTileSourceIndex: Int = 0

    private val _deviceStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceStatus = _deviceStatus.asStateFlow()

    private val _remoteZoomCommand = MutableSharedFlow<Boolean>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val remoteZoomCommand: SharedFlow<Boolean> = _remoteZoomCommand.asSharedFlow()

    private val _iconRequest = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val iconRequest: SharedFlow<String> = _iconRequest.asSharedFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning = _isServiceRunning.asStateFlow()

    fun updateLocation(location: Location) {
        _gpsLocation.value = location
        val speedKmh = (location.speed * 3.6f).toInt().coerceAtLeast(0)
        _currentSpeedKmh.value = speedKmh
    }

    fun updateSpeed(speedKmh: Int) {
        _currentSpeedKmh.value = speedKmh.coerceAtLeast(0)
    }

    fun updateRoutes(routes: List<RouteInfo>) {
        _routes.value = routes
    }

    fun updateStep(step: StepInfo) {
        _currentStep.tryEmit(step)
    }

    fun updateHudPreview(data: HudData?) {
        _hudPreviewData.value = data
    }

    fun updateBleConnectionState(state: BleConnectionState) {
        _bleConnectionState.value = state
    }

    fun setNavigationRunning(running: Boolean) {
        _navigationState.value = running
    }

    fun setMapModeActive(active: Boolean) {
        _mapModeState.value = active
    }

    fun setTrackUpMode(active: Boolean) {
        _isTrackUpMode.value = active
    }

    fun updateDeviceStatus(status: Map<String, String>) {
        _deviceStatus.value = status
    }

    fun triggerRemoteZoom(zoomIn: Boolean) {
        _remoteZoomCommand.tryEmit(zoomIn)
    }

    fun requestIconData(hashHex: String) {
        _iconRequest.tryEmit(hashHex)
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun addLog(message: String) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val time = sdf.format(java.util.Date())
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, "[$time] $message")
        if (currentLogs.size > 100) currentLogs.removeAt(currentLogs.size - 1)
        _logs.value = currentLogs
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private val _compassHeading = MutableStateFlow(0f)
    val compassHeading = _compassHeading.asStateFlow()

    fun updateCompassHeading(heading: Float) {
        _compassHeading.value = heading
    }

    private val _isOfflineSelectionMode = MutableStateFlow(false)
    val isOfflineSelectionMode = _isOfflineSelectionMode.asStateFlow()

    fun setOfflineSelectionMode(active: Boolean) {
        _isOfflineSelectionMode.value = active
    }

    private val _lastSentMapImage = MutableStateFlow<android.graphics.Bitmap?>(null)
    val lastSentMapImage = _lastSentMapImage.asStateFlow()

    private val _preparedBleData = MutableStateFlow<String>("")
    val preparedBleData = _preparedBleData.asStateFlow()

    fun updateLastSentMapImage(bitmap: android.graphics.Bitmap?) {
        _lastSentMapImage.value = bitmap
    }

    fun updatePreparedBleData(data: String) {
        _preparedBleData.value = data
    }

    data class MapPreviewInfo(
        val fullMap: android.graphics.Bitmap? = null,
        val cropX: Int = 0,
        val cropY: Int = 0,
        val cropSize: Int = 0,
        val croppedMap: android.graphics.Bitmap? = null
    )

    private val _mapPreviewInfo = MutableStateFlow<MapPreviewInfo?>(null)
    val mapPreviewInfo = _mapPreviewInfo.asStateFlow()

    fun updateMapPreviewInfo(info: MapPreviewInfo?) {
        _mapPreviewInfo.value = info
    }

    /**
     * Trạng thái bản đồ cuốn chiếu (Rolling Map Tile Streaming).
     * Mỗi tile được lưu theo key "tileX:tileY:z" → bitmap đã render.
     * [centerTileX/Y/Z]: tile trung tâm mà ESP32 đang hiển thị.
     * [vehiclePxInTile / vehiclePyInTile]: vị trí pixel xe trong tile trung tâm (0-255).
     */
    data class TileStreamingState(
        val tiles: Map<String, android.graphics.Bitmap> = emptyMap(),
        val centerTileX: Int = 0,
        val centerTileY: Int = 0,
        val centerTileZ: Int = 0,
        val vehiclePxInTile: Int = 128,
        val vehiclePyInTile: Int = 128
    )

    private val _tileStreamingState = MutableStateFlow(TileStreamingState())
    val tileStreamingState = _tileStreamingState.asStateFlow()

    fun updateTileStreamingCenter(tileX: Int, tileY: Int, tileZ: Int, px: Int, py: Int) {
        _tileStreamingState.value = _tileStreamingState.value.copy(
            centerTileX = tileX, centerTileY = tileY, centerTileZ = tileZ,
            vehiclePxInTile = px, vehiclePyInTile = py
        )
    }

    fun addStreamedTile(tileX: Int, tileY: Int, tileZ: Int, bitmap: android.graphics.Bitmap) {
        val key = "$tileX:$tileY:$tileZ"
        val current = _tileStreamingState.value.tiles.toMutableMap()
        // Giữ tối đa 25 tile trong memory để tránh OOM
        if (current.size >= 25) {
            val oldest = current.keys.first()
            current[oldest]?.recycle()
            current.remove(oldest)
        }
        current[key] = bitmap
        _tileStreamingState.value = _tileStreamingState.value.copy(tiles = current)
    }

    fun clearStreamedTiles() {
        val current = _tileStreamingState.value.tiles
        current.values.forEach { if (!it.isRecycled) it.recycle() }
        _tileStreamingState.value = TileStreamingState()
    }
}

