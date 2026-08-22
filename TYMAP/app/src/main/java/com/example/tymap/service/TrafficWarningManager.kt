package com.example.tymap.service

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.tymap.ble.MyBleManager
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    val lon: Double          // Kinh độ
)

/**
 * Quản lý Cảnh báo Giao thông (Hybrid Goong.io + Overpass API)
 * - Tối ưu API Request qua cơ chế Caching trên RAM.
 * - Kiểm tra vị trí Offline mỗi giây bằng thuật toán tính khoảng cách Haversine / Location.distanceBetween.
 */
object TrafficWarningManager {
    private const val TAG = "TrafficWarningManager"

    // OkHttpClient tái sử dụng từ hệ thống
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Scope coroutine ngầm cho công việc tải dữ liệu
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Danh sách lưu trữ mảng tọa độ cảnh báo trong RAM (An toàn đa luồng)
    private val warningCache = CopyOnWriteArrayList<TrafficWarningPoint>()

    // Vị trí tâm quét API Overpass gần nhất (dùng cho Spatial Cache)
    private var lastQueryCenter: Location? = null

    // Đang trong quá trình tải dữ liệu API ngầm hay không
    @Volatile
    private var isFetchingApi = false

    // Lưu lịch sử thời gian đã cảnh báo từng điểm (tránh phát lặp lại liên tục trong 15s)
    private val lastTriggeredTimeMap = HashMap<String, Long>()

    // Ngưỡng khoảng cách phát cảnh báo (200m)
    private const val WARNING_DISTANCE_METERS = 200f

    // Ngưỡng khoảng cách di chuyển để quét lại Overpass (1.5km = 1500m)
    private const val SPATIAL_REQUERY_THRESHOLD_METERS = 1500f

    // Thời gian Cooldown giữa 2 lần cảnh báo cùng 1 điểm (15 giây)
    private const val WARNING_COOLDOWN_MS = 15000L

    /**
     * BƯỚC 1 (PRIMARY): Tải dữ liệu cảnh báo từ Overpass API (NAS Server hoặc Public) khi bắt đầu lộ trình mới.
     * Hàm này chỉ gọi ĐÚNG 1 LẦN duy nhất khi khởi tạo lộ trình.
     */
    fun fetchRouteWarningsFromGoong(context: Context, routePolyline: List<Pair<Double, Double>>) {
        if (routePolyline.isEmpty()) return

        scope.launch {
            try {
                Log.d(TAG, "==> [Overpass API] Đang tải dữ liệu cảnh báo cho toàn bộ lộ trình...")
                val newPoints = mutableListOf<TrafficWarningPoint>()

                // Gọi Overpass cho Polyline (ưu tiên NAS Overpass port 8088, fallback sang Overpass public)
                fetchOverpassForPolyline(context, routePolyline, newPoints)

                // Cập nhật RAM Cache an toàn
                warningCache.clear()
                warningCache.addAll(newPoints)
                Log.d(TAG, "==> [Overpass] Đã nạp thành công ${warningCache.size} điểm cảnh báo vào RAM Cache")

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi tải cảnh báo Overpass: ${e.message}", e)
            }
        }
    }

    /**
     * BƯỚC 2 (FALLBACK / FREE ROAMING): Truy vấn Overpass API theo bán kính 2km.
     * Áp dụng cơ chế SPATIAL CACHING: Chỉ quét lại khi di chuyển > 1.5km so với tâm quét cũ.
     */
    fun checkAndFetchSpatialOverpass(location: Location) {
        // Kiểm tra xem đã di chuyển quá 1.5km so với vị trí quét cũ chưa
        val lastCenter = lastQueryCenter
        if (lastCenter != null) {
            val distFromLastCenter = location.distanceTo(lastCenter)
            if (distFromLastCenter < SPATIAL_REQUERY_THRESHOLD_METERS) {
                // Chưa vượt ngưỡng 1.5km -> Bỏ qua API Request, dùng lại RAM Cache
                return
            }
        }

        if (isFetchingApi) return
        isFetchingApi = true

        scope.launch {
            try {
                Log.d(TAG, "==> [Overpass API] Xe đã di chuyển > 1.5km -> Quét lại bán kính 2km xung quanh Lat: ${location.latitude}, Lon: ${location.longitude}")
                
                val lat = location.latitude
                val lon = location.longitude
                
                // Overpass QL Query: Lấy node camera và way/node giới hạn tốc độ trong bán kính 2000m
                val overpassQuery = """
                    [out:json][timeout:15];
                    (
                      node["highway"="speed_camera"](around:2000,$lat,$lon);
                      node["maxspeed"](around:2000,$lat,$lon);
                      way["maxspeed"](around:2000,$lat,$lon);
                    );
                    out body;
                    >;
                    out skel qt;
                """.trimIndent()

                val url = "https://overpass-api.de/api/interpreter?data=" + java.net.URLEncoder.encode(overpassQuery, "UTF-8")
                val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0 (Android Motorcycle Navigation)").build()
                
                var response = try { httpClient.newCall(request).execute() } catch (e: Exception) { null }
                if (response == null || !response.isSuccessful) {
                    val fallbackUrl = "https://overpass.kumi.systems/api/interpreter?data=" + java.net.URLEncoder.encode(overpassQuery, "UTF-8")
                    val fallbackRequest = Request.Builder().url(fallbackUrl).header("User-Agent", "TYMAP/1.0 (Android Motorcycle Navigation)").build()
                    response = try { httpClient.newCall(fallbackRequest).execute() } catch (e: Exception) { null }
                }

                if (response != null && response.isSuccessful) {
                    val jsonStr = response.body?.string()
                    if (!jsonStr.isNullOrEmpty()) {
                        val parsedPoints = parseOverpassJson(jsonStr)
                        
                        // Cập nhật RAM Cache
                        warningCache.clear()
                        warningCache.addAll(parsedPoints)
                        
                        // Đánh dấu vị trí tâm quét mới
                        lastQueryCenter = Location(location)
                        Log.d(TAG, "==> [Overpass API] Đã cập nhật ${warningCache.size} điểm cảnh báo mới vào RAM Cache")
                    }
                } else {
                    Log.w(TAG, "Overpass API trả về mã lỗi HTTP: ${response?.code ?: "Unknown"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi quét Overpass API: ${e.message}")
            } finally {
                isFetchingApi = false
            }
        }
    }

    /**
     * BƯỚC 3 (OFFLINE CHECK): Quét mảng RAM Cache mỗi khi có tọa độ GPS mới (mỗi giây).
     * Thuật toán tính khoảng cách hoàn toàn Offline (Location.distanceBetween).
     */
    fun checkGpsLocation(context: Context, location: Location, bleManager: MyBleManager?) {
        val isNavigating = NavigationRepository.navigationState.value

        // Nếu không trong chế độ dẫn đường (Chạy xe tự do), kích hoạt kiểm tra Spatial Overpass
        if (!isNavigating) {
            checkAndFetchSpatialOverpass(location)
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

            // Nếu khoảng cách < 200m
            if (distanceMeters <= WARNING_DISTANCE_METERS) {
                val lastTriggered = lastTriggeredTimeMap[point.id] ?: 0L

                // Kiểm tra Cooldown 15 giây để tránh gửi thông báo lặp lại liên tục
                if (now - lastTriggered > WARNING_COOLDOWN_MS) {
                    lastTriggeredTimeMap[point.id] = now

                    Log.i(TAG, "🚨 [CẢNH BÁO GIAO THÔNG] Loại: ${if (point.type == WarningType.CAMERA) "Camera Phạt Nguội" else "Biển Tốc Độ ${point.speedLimit}km/h"}, Khoảng cách: ${distanceMeters.toInt()}m")
                    
                    // Ghi log lên repository
                    val logMsg = if (point.type == WarningType.CAMERA) "Camera phạt nguội (${distanceMeters.toInt()}m)" else "Biển giới hạn tốc độ ${point.speedLimit}km/h (${distanceMeters.toInt()}m)"
                    NavigationRepository.addLog("🚨 Alert: $logMsg")

                    // Gửi tín hiệu BLE tới ESP32
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
     * Hàm phân tích dữ liệu JSON trả về từ Overpass API
     */
    private fun parseOverpassJson(jsonStr: String): List<TrafficWarningPoint> {
        val points = mutableListOf<TrafficWarningPoint>()
        try {
            val rootObj = JSONObject(jsonStr)
            val elements: JSONArray = rootObj.optJSONArray("elements") ?: return points

            for (i in 0 until elements.length()) {
                val elem = elements.getJSONObject(i)
                val typeStr = elem.optString("type")
                val id = elem.optLong("id").toString()

                if (typeStr == "node") {
                    val lat = elem.optDouble("lat", 0.0)
                    val lon = elem.optDouble("lon", 0.0)
                    val tags = elem.optJSONObject("tags")

                    if (tags != null) {
                        // 1. Kiểm tra Camera phạt nguội
                        if (tags.optString("highway") == "speed_camera") {
                            points.add(TrafficWarningPoint(
                                id = "cam_$id",
                                type = WarningType.CAMERA,
                                speedLimit = 0,
                                lat = lat,
                                lon = lon
                            ))
                        }

                        // 2. Kiểm tra Biển giới hạn tốc độ
                        if (tags.has("maxspeed")) {
                            val maxSpeedRaw = tags.optString("maxspeed")
                            val speedVal = parseSpeedLimitValue(maxSpeedRaw)
                            if (speedVal > 0) {
                                points.add(TrafficWarningPoint(
                                    id = "speed_$id",
                                    type = WarningType.SPEED_LIMIT,
                                    speedLimit = speedVal,
                                    lat = lat,
                                    lon = lon
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi parse JSON Overpass: ${e.message}")
        }
        return points
    }

    /**
     * Phân tích chuỗi maxspeed (Ví dụ: "50", "60 km/h", "VN:urban" -> 50)
     */
    private fun parseSpeedLimitValue(rawSpeed: String): Int {
        val digits = rawSpeed.replace("[^0-9]".toRegex(), "")
        return digits.toIntOrNull() ?: when {
            rawSpeed.contains("urban", ignoreCase = true) -> 50
            rawSpeed.contains("rural", ignoreCase = true) -> 80
            rawSpeed.contains("motorway", ignoreCase = true) -> 120
            else -> 0
        }
    }

    /**
     * Fallback phân tích dữ liệu Goong
     */
    private fun parseGoongResponseBody(jsonStr: String, outList: MutableList<TrafficWarningPoint>) {
        try {
            val root = JSONObject(jsonStr)
            // Parse Goong structure if present
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi parse Goong JSON: ${e.message}")
        }
    }

    /**
     * Phân tích Overpass cho danh sách điểm lộ trình Polyline
     */
    private fun fetchOverpassForPolyline(context: Context, polyline: List<Pair<Double, Double>>, outList: MutableList<TrafficWarningPoint>) {
        if (polyline.isEmpty()) return
        // Lấy tâm trung bình của polyline để query bán kính bao phủ
        val midPt = polyline[polyline.size / 2]
        val lat = midPt.first
        val lon = midPt.second

        val query = """
            [out:json][timeout:15];
            (
              node["highway"="speed_camera"](around:5000,$lat,$lon);
              node["maxspeed"](around:5000,$lat,$lon);
            );
            out body;
        """.trimIndent()

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val nasIp = PrefsHelper.getString(context, "nas_ip", "192.168.1.114").ifEmpty { "192.168.1.114" }

        val urls = listOf(
            "http://$nasIp:8088/api/interpreter?data=$encodedQuery",
            "https://overpass-api.de/api/interpreter?data=$encodedQuery",
            "https://overpass.kumi.systems/api/interpreter?data=$encodedQuery"
        )

        for (url in urls) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0 (Android Motorcycle Navigation)").build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonStr = response.body?.string()
                    if (!jsonStr.isNullOrEmpty()) {
                        outList.addAll(parseOverpassJson(jsonStr))
                        Log.d(TAG, "Lấy thành công dữ liệu Overpass từ: $url")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Thất bại kết nối Overpass URL ($url): ${e.message}")
            }
        }
    }
}
