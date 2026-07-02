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
    val isSelected: Boolean = false
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
}
