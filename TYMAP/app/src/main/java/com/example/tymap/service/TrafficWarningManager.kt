package com.example.tymap.service

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.tymap.ble.MyBleManager
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.utils.NasConnectionManager
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Loại cảnh báo giao thông
 */
object WarningType {
    const val CAMERA: Byte = 0x01      // Camera phạt nguội
    const val SPEED_LIMIT: Byte = 0x02 // Biển giới hạn tốc độ
}

/**
 * Cấu trúc thông tin điểm cảnh báo giao thông lưu trữ trong RAM Cache
 */
data class TrafficWarningPoint(
    val id: String,          // Định danh duy nhất của điểm
    val type: Byte,          // Loại cảnh báo (0x01: Camera, 0x02: Speed limit)
    val speedLimit: Int,     // Giới hạn tốc độ (km/h), = 0 nếu chỉ là Camera
    val lat: Double,         // Vĩ độ
    val lon: Double,         // Kinh độ
    val description: String = "" // Mô tả / tên đường
)

/**
 * Quản lý Cảnh báo Giao thông (Fusion Engine Node.js & Overpass Cloud Fallback)
 * - Tải dữ liệu lọc theo Polyline từ Fusion Engine trên NAS (Port 8088).
 * - Tự động Fallback sang cụm Overpass API công cộng khi mất kết nối NAS hoặc ngoài đường (4G).
 * - Lưu toàn bộ mảng cảnh báo vào RAM Cache trên điện thoại.
 * - Kiểm tra vị trí Offline mỗi giây bằng thuật toán tính khoảng cách Haversine / Location.distanceBetween (không phụ thuộc 4G).
 */
object TrafficWarningManager {
    private const val TAG = "TrafficWarningManager"

    // Scope coroutine ngầm cho công việc tải dữ liệu
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Danh sách lưu trữ mảng tọa độ cảnh báo trong RAM (An toàn đa luồng)
    private val warningCache = CopyOnWriteArrayList<TrafficWarningPoint>()

    // Vị trí tâm quét API gần nhất (dùng cho Spatial Cache)
    private var lastQueryCenter: Location? = null

    // Đang trong quá trình tải dữ liệu API ngầm hay không
    @Volatile
    private var isFetchingApi = false

    // Lưu lịch sử thời gian đã cảnh báo từng điểm (tránh phát lặp lại liên tục trong 15s)
    private val lastTriggeredTimeMap = HashMap<String, Long>()

    // Ngưỡng khoảng cách phát cảnh báo (200m)
    private const val WARNING_DISTANCE_METERS = 200f

    // Ngưỡng khoảng cách di chuyển để quét lại (1.5km = 1500m)
    private const val SPATIAL_REQUERY_THRESHOLD_METERS = 1500f

    // Thời gian Cooldown giữa 2 lần cảnh báo cùng 1 điểm (15 giây)
    private const val WARNING_COOLDOWN_MS = 15000L

    /**
     * BƯỚC 1 (PRIMARY): Tải dữ liệu cảnh báo từ NAS Fusion Engine khi bắt đầu lộ trình mới.
     * Tự động Fallback sang Overpass công cộng khi mất kết nối NAS.
     */
    fun fetchRouteWarningsFromGoong(context: Context, routePolyline: List<Pair<Double, Double>>) {
        fetchRouteWarnings(context, routePolyline)
    }

    fun fetchRouteWarnings(context: Context, routePolyline: List<Pair<Double, Double>>) {
        if (routePolyline.isEmpty()) return

        scope.launch {
            var loaded = false

            // 1. Thử NAS Fusion Engine (Port 8088 hoặc https://alert.domain) nếu NAS online
            if (NasConnectionManager.isNasPotentiallyAvailable(context)) {
                try {
                    Log.d(TAG, "==> [Fusion Engine] Đang tải dữ liệu cảnh báo dọc lộ trình từ NAS...")
                    val fusionBaseUrl = NasConnectionManager.getFusionEngineBaseUrl(context)

                    val polyArray = JSONArray()
                    for (pt in routePolyline) {
                        val coord = JSONArray()
                        coord.put(pt.first)
                        coord.put(pt.second)
                        polyArray.put(coord)
                    }
                    val requestJson = JSONObject().apply {
                        put("polyline", polyArray)
                        put("bufferMeters", 50)
                    }

                    val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val url = "$fusionBaseUrl/api/warnings/route"
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)")
                        .build()

                    val response = NasConnectionManager.nasHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val jsonStr = response.body?.string()
                        if (!jsonStr.isNullOrEmpty()) {
                            val parsedPoints = parseFusionEngineJson(jsonStr)
                            if (parsedPoints.isNotEmpty()) {
                                warningCache.clear()
                                warningCache.addAll(parsedPoints)
                                NavigationRepository.updateTrafficWarningPoints(warningCache.toList())
                                NasConnectionManager.markNasSuccess()
                                loaded = true
                                Log.d(TAG, "==> [Fusion Engine] Đã nạp thành công ${warningCache.size} điểm cảnh báo vào RAM Cache")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (NasConnectionManager.isConnectionFailure(e)) {
                        NasConnectionManager.markNasFailed(e.message)
                    }
                    Log.w(TAG, "Lỗi kết nối Fusion Engine NAS: ${e.message}")
                }
            }

            // 2. Fallback sang Overpass API công cộng dọc lộ trình nếu NAS offline hoặc không có dữ liệu
            if (!loaded && routePolyline.isNotEmpty()) {
                fetchRouteWarningsFromPublicOverpass(routePolyline)
            }
        }
    }

    /**
     * Quét cảnh báo Overpass API công cộng dọc theo lộ trình
     */
    private fun fetchRouteWarningsFromPublicOverpass(routePolyline: List<Pair<Double, Double>>) {
        try {
            // Lấy các điểm mấu chốt dọc tuyến (đầu, giữa, cuối) để quét bán kính 3000m
            val sampledPoints = mutableListOf<Pair<Double, Double>>()
            val step = (routePolyline.size / 4).coerceAtLeast(1)
            for (i in routePolyline.indices step step) {
                sampledPoints.add(routePolyline[i])
                if (sampledPoints.size >= 5) break
            }
            if (!sampledPoints.contains(routePolyline.last())) {
                sampledPoints.add(routePolyline.last())
            }

            val queryBuilder = StringBuilder("[out:json][timeout:8];(")
            for (pt in sampledPoints) {
                queryBuilder.append("node[\"highway\"=\"speed_camera\"](around:3000,${pt.first},${pt.second});")
                queryBuilder.append("node[\"maxspeed\"](around:3000,${pt.first},${pt.second});")
            }
            queryBuilder.append(");out body;")

            val endpoints = listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter",
                "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
            )

            for (endpoint in endpoints) {
                try {
                    val formBody = okhttp3.FormBody.Builder().add("data", queryBuilder.toString()).build()
                    val request = Request.Builder().url(endpoint).post(formBody).header("User-Agent", "TYMAP-Android/1.0").build()
                    NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string() ?: return@use
                            val json = JSONObject(bodyStr)
                            val elements = json.optJSONArray("elements")
                            if (elements != null && elements.length() > 0) {
                                val parsed = parseOverpassElements(elements)
                                if (parsed.isNotEmpty()) {
                                    warningCache.clear()
                                    warningCache.addAll(parsed)
                                    NavigationRepository.updateTrafficWarningPoints(warningCache.toList())
                                    Log.d(TAG, "==> [Overpass Cloud] Đã tải ${parsed.size} điểm cảnh báo dọc tuyến từ $endpoint")
                                    return
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Overpass fallback $endpoint failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi fetchRouteWarningsFromPublicOverpass: ${e.message}")
        }
    }

    /**
     * BƯỚC 2 (FREE ROAMING): Quét bán kính xung quanh khi xe di chuyển tự do.
     * Chỉ gửi request khi đã di chuyển > 1.5km so với tâm cũ.
     */
    fun checkAndFetchSpatialOverpass(context: Context, location: Location) {
        val lastCenter = lastQueryCenter
        if (lastCenter != null) {
            val distFromLastCenter = location.distanceTo(lastCenter)
            if (distFromLastCenter < SPATIAL_REQUERY_THRESHOLD_METERS) {
                return
            }
        }

        if (isFetchingApi) return
        isFetchingApi = true

        scope.launch {
            try {
                var loaded = false
                val lat = location.latitude
                val lon = location.longitude

                // 1. Thử NAS Fusion Engine trước nếu NAS online
                if (NasConnectionManager.isNasPotentiallyAvailable(context)) {
                    val fusionBaseUrl = NasConnectionManager.getFusionEngineBaseUrl(context)
                    val url = "$fusionBaseUrl/api/warnings/nearby?lat=$lat&lon=$lon&radius=2000"
                    try {
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)")
                            .build()

                        val response = NasConnectionManager.nasHttpClient.newCall(request).execute()
                        if (response.isSuccessful) {
                            val jsonStr = response.body?.string()
                            if (!jsonStr.isNullOrEmpty()) {
                                val parsedPoints = parseFusionEngineJson(jsonStr)
                                if (parsedPoints.isNotEmpty()) {
                                    warningCache.clear()
                                    warningCache.addAll(parsedPoints)
                                    NavigationRepository.updateTrafficWarningPoints(warningCache.toList())
                                    lastQueryCenter = Location(location)
                                    NasConnectionManager.markNasSuccess()
                                    loaded = true
                                    Log.d(TAG, "==> [Fusion Engine Nearby] Đã cập nhật ${warningCache.size} điểm cảnh báo vào RAM Cache")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (NasConnectionManager.isConnectionFailure(e)) {
                            NasConnectionManager.markNasFailed(e.message)
                        }
                    }
                }

                // 2. Fallback sang Overpass Cloud nếu NAS offline
                if (!loaded) {
                    val query = "[out:json][timeout:8];(node[\"highway\"=\"speed_camera\"](around:2500,$lat,$lon);node[\"maxspeed\"](around:2500,$lat,$lon););out body;"
                    val endpoints = listOf(
                        "https://overpass-api.de/api/interpreter",
                        "https://overpass.kumi.systems/api/interpreter"
                    )

                    for (endpoint in endpoints) {
                        try {
                            val formBody = okhttp3.FormBody.Builder().add("data", query).build()
                            val request = Request.Builder().url(endpoint).post(formBody).header("User-Agent", "TYMAP-Android/1.0").build()
                            NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val bodyStr = response.body?.string() ?: return@use
                                    val json = JSONObject(bodyStr)
                                    val elements = json.optJSONArray("elements")
                                    if (elements != null && elements.length() > 0) {
                                        val parsed = parseOverpassElements(elements)
                                        warningCache.clear()
                                        warningCache.addAll(parsed)
                                        NavigationRepository.updateTrafficWarningPoints(warningCache.toList())
                                        lastQueryCenter = Location(location)
                                        loaded = true
                                        Log.d(TAG, "==> [Overpass Nearby Cloud] Đã nạp ${parsed.size} điểm cảnh báo lân cận")
                                        return@launch
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi quét cảnh báo lân cận: ${e.message}")
            } finally {
                isFetchingApi = false
            }
        }
    }

    /**
     * BƯỚC 3 (OFFLINE CHECK): Quét mảng RAM Cache mỗi khi có tọa độ GPS mới (mỗi giây).
     * Thuật toán tính khoảng cách hoàn toàn Offline (Location.distanceBetween) không phụ thuộc mạng 4G.
     */
    fun checkGpsLocation(context: Context, location: Location, bleManager: MyBleManager?) {
        val isNavigating = NavigationRepository.navigationState.value

        // Nếu không trong chế độ dẫn đường hoặc RAM Cache đang trống, kích hoạt quét lân cận
        if (!isNavigating || warningCache.isEmpty()) {
            checkAndFetchSpatialOverpass(context, location)
        }

        if (warningCache.isEmpty()) return

        val now = System.currentTimeMillis()
        val results = FloatArray(1)

        // Duyệt qua từng điểm cảnh báo trong mảng RAM Cache (Offline)
        for (point in warningCache) {
            // Tính khoảng cách từ GPS hiện tại tới điểm cảnh báo trong RAM
            Location.distanceBetween(
                location.latitude, location.longitude,
                point.lat, point.lon,
                results
            )

            val distanceMeters = results[0]

            // Nếu khoảng cách <= 200m
            if (distanceMeters <= WARNING_DISTANCE_METERS) {
                val lastTriggered = lastTriggeredTimeMap[point.id] ?: 0L

                // Kiểm tra Cooldown 15 giây để tránh gửi thông báo lặp lại liên tục
                if (now - lastTriggered > WARNING_COOLDOWN_MS) {
                    lastTriggeredTimeMap[point.id] = now

                    val alertName = if (point.type == WarningType.CAMERA) {
                        "Camera Phạt Nguội"
                    } else {
                        "Biển Tốc Độ ${point.speedLimit}km/h"
                    }
                    Log.i(TAG, "🚨 [CẢNH BÁO GIAO THÔNG] Loại: $alertName, Khoảng cách: ${distanceMeters.toInt()}m")
                    
                    val logMsg = "$alertName (${distanceMeters.toInt()}m)"
                    NavigationRepository.addLog("🚨 Alert: $logMsg")
                    NavigationRepository.triggerTrafficAlert(
                        NavigationRepository.TrafficAlert(
                            type = point.type,
                            speedLimit = point.speedLimit,
                            distanceMeters = distanceMeters.toInt(),
                            message = logMsg
                        )
                    )

                    // Cập nhật giới hạn tốc độ và biển báo vào Repository nếu là biển tốc độ
                    if (point.type == WarningType.SPEED_LIMIT && point.speedLimit > 0) {
                        NavigationRepository.updateSpeedLimit(point.speedLimit, point.description)
                        PrefsHelper.putInt(context, "speed_threshold", point.speedLimit)
                    }

                    // Gửi tín hiệu BLE Hex nhị phân tới ESP32
                    if (bleManager != null && bleManager.isConnected) {
                        bleManager.sendTrafficWarning(point.type, point.speedLimit.toByte())
                    }
                    
                    // Chỉ kích hoạt 1 cảnh báo gần nhất tại 1 thời điểm
                    break
                }
            }
        }
    }

    /**
     * Phân tích JSON trả về từ Fusion Engine
     */
    private fun parseFusionEngineJson(jsonStr: String): List<TrafficWarningPoint> {
        val points = mutableListOf<TrafficWarningPoint>()
        try {
            val root = JSONObject(jsonStr)
            val array = root.optJSONArray("points") ?: return points
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "pt_$i")
                val typeInt = obj.optInt("type", 1)
                val typeByte = if (typeInt == 2) WarningType.SPEED_LIMIT else WarningType.CAMERA
                val speedLimit = obj.optInt("speedLimit", 0)
                val lat = obj.optDouble("lat", 0.0)
                val lon = obj.optDouble("lon", 0.0)
                val desc = obj.optString("description", "")

                if (lat != 0.0 && lon != 0.0) {
                    points.add(TrafficWarningPoint(
                        id = id,
                        type = typeByte,
                        speedLimit = speedLimit,
                        lat = lat,
                        lon = lon,
                        description = desc
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi parse JSON Fusion Engine: ${e.message}")
        }
        return points
    }

    /**
     * Phân tích JSON trả về từ Overpass API sang TrafficWarningPoint
     */
    private fun parseOverpassElements(elements: JSONArray): List<TrafficWarningPoint> {
        val points = mutableListOf<TrafficWarningPoint>()
        for (i in 0 until elements.length()) {
            val node = elements.getJSONObject(i)
            val lat = node.optDouble("lat", 0.0)
            val lon = node.optDouble("lon", 0.0)
            val tags = node.optJSONObject("tags") ?: JSONObject()
            val isCam = tags.optString("highway") == "speed_camera" || tags.optString("traffic_signals") == "camera"
            val maxspeedStr = tags.optString("maxspeed", "")
            val speedLimit = SpeedLimitEngine.parseSpeedString(maxspeedStr)

            if (lat != 0.0 && lon != 0.0) {
                val type = if (isCam) WarningType.CAMERA else WarningType.SPEED_LIMIT
                val desc = if (isCam) "Camera Phạt Nguội" else "Biển $speedLimit km/h"
                points.add(TrafficWarningPoint(
                    id = "osm_${node.optLong("id", i.toLong())}",
                    type = type,
                    speedLimit = speedLimit,
                    lat = lat,
                    lon = lon,
                    description = desc
                ))
            }
        }
        return points
    }
}
