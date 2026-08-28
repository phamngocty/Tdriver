package com.example.tymap.service

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.tymap.utils.NasConnectionManager
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class SpeedLimitResult(
    val speedLimit: Int,
    val source: String,
    val roadName: String = "",
    val hasCamera: Boolean = false
)

object SpeedLimitEngine {
    private const val TAG = "SpeedLimitEngine"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Spatial Grid Cache: Key là cặp (latGrid, lonGrid) với độ phân giải ~100m (0.001 độ)
    private data class GridKey(val latIndex: Int, val lonIndex: Int)
    private val ramGridCache = ConcurrentHashMap<GridKey, SpeedLimitResult>()

    private var lastQueryLocation: Location? = null
    private const val MIN_REQUERY_DISTANCE_METERS = 100f // 100m mới phát HTTP request mới

    @Volatile
    private var isFetching = false

    /**
     * Lấy tốc độ giới hạn cho vị trí hiện tại với cơ chế Fallback 3 Tầng & Caching Thông Minh
     */
    fun fetchSpeedLimit(context: Context, location: Location, onResult: (SpeedLimitResult?) -> Unit) {
        val latGrid = (location.latitude * 1000).roundToInt()
        val lonGrid = (location.longitude * 1000).roundToInt()
        val key = GridKey(latGrid, lonGrid)

        // 1. Kiểm tra RAM Cache trước (0ms delay)
        val cached = ramGridCache[key]
        if (cached != null) {
            onResult(cached)
            return
        }

        // 2. Kiểm tra khoảng cách di chuyển (Bộ lọc tiết kiệm requests)
        val lastLoc = lastQueryLocation
        if (lastLoc != null && location.distanceTo(lastLoc) < MIN_REQUERY_DISTANCE_METERS && isFetching) {
            return
        }

        isFetching = true
        lastQueryLocation = location

        scope.launch {
            try {
                // TẦNG 1: Overpass API (NAS Server & OpenStreetMap - Miễn phí)
                var result = queryOverpassApi(context, location.latitude, location.longitude)

                // TẦNG 2: HERE Location Services API (Nếu Overpass không có & có Key HERE)
                if (result == null) {
                    val hereKey = PrefsHelper.getSecureString(context, "api_key_here", "").trim()
                    if (hereKey.isNotEmpty()) {
                        result = queryHereApi(location.latitude, location.longitude, hereKey)
                    }
                }

                // TẦNG 3: TomTom API (Nếu HERE/Overpass không có & có Key TomTom)
                if (result == null) {
                    val tomtomKey = PrefsHelper.getSecureString(context, "api_key_tomtom", "").trim()
                    if (tomtomKey.isNotEmpty()) {
                        result = queryTomTomApi(location.latitude, location.longitude, tomtomKey)
                    }
                }

                if (result != null) {
                    ramGridCache[key] = result
                    Log.d(TAG, "==> [SpeedLimitEngine] Tìm thấy tốc độ giới hạn: ${result.speedLimit} km/h (Nguồn: ${result.source})")
                    com.example.tymap.repository.NavigationRepository.updateSpeedLimit(result.speedLimit, result.roadName)
                    withContext(Dispatchers.Main) {
                        onResult(result)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi fetchSpeedLimit: ${e.message}")
            } finally {
                isFetching = false
            }
        }
    }

    /**
     * Truy vấn TẦNG 1: Overpass API (NAS Server & OpenStreetMap)
     */
    private fun queryOverpassApi(context: Context, lat: Double, lon: Double): SpeedLimitResult? {
        val query = """
            [out:json][timeout:5];
            way(around:50,$lat,$lon)["maxspeed"];
            out tags;
        """.trimIndent()

        val endpoints = mutableListOf<String>()
        if (NasConnectionManager.isNasPotentiallyAvailable(context)) {
            val fusionBaseUrl = NasConnectionManager.getFusionEngineBaseUrl(context)
            endpoints.add("$fusionBaseUrl/api/interpreter?data=")
        }
        endpoints.add("https://overpass-api.de/api/interpreter?data=")
        endpoints.add("https://overpass.kumi.systems/api/interpreter?data=")

        for (endpoint in endpoints) {
            try {
                val isNas = NasConnectionManager.isNasEndpoint(endpoint)
                val client = if (isNas) NasConnectionManager.nasHttpClient else NasConnectionManager.publicHttpClient
                val url = endpoint + URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: continue

                val json = JSONObject(body)
                val elements = json.optJSONArray("elements") ?: continue
                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val tags = el.optJSONObject("tags") ?: continue
                    val maxspeedStr = tags.optString("maxspeed", "")
                    val roadName = tags.optString("name", "")

                    val parsedSpeed = parseSpeedString(maxspeedStr)
                    if (parsedSpeed > 0) {
                        if (isNas) {
                            NasConnectionManager.markNasSuccess()
                        }
                        return SpeedLimitResult(
                            speedLimit = parsedSpeed,
                            source = if (isNas) "NAS Fusion Engine" else "OpenStreetMap (Overpass)",
                            roadName = roadName
                        )
                    }
                }
            } catch (e: Exception) {
                if (NasConnectionManager.isNasEndpoint(endpoint) && NasConnectionManager.isConnectionFailure(e)) {
                    NasConnectionManager.markNasFailed(e.message)
                }
                Log.w(TAG, "Overpass endpoint $endpoint lỗi: ${e.message}")
            }
        }
        return null
    }

    /**
     * Truy vấn TẦNG 2: HERE Location Services API
     */
    private fun queryHereApi(lat: Double, lon: Double, apiKey: String): SpeedLimitResult? {
        try {
            val url = "https://revgeocode.search.hereapi.com/v1/revgeocode?at=$lat,$lon&lang=vi&apiKey=$apiKey"
            val request = Request.Builder().url(url).build()
            val response = NasConnectionManager.publicHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null

            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return null
            if (items.length() > 0) {
                val item = items.getJSONObject(0)
                val title = item.optString("title", "")
                val roadInfo = item.optJSONObject("address")?.optString("street", title) ?: title
                Log.d(TAG, "HERE API RevGeocode Road: $roadInfo")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi HERE API: ${e.message}")
        }
        return null
    }

    /**
     * Truy vấn TẦNG 3: TomTom Reverse Geocode & Speed Limits API
     */
    private fun queryTomTomApi(lat: Double, lon: Double, apiKey: String): SpeedLimitResult? {
        try {
            val url = "https://api.tomtom.com/search/2/reverseGeocode/$lat,$lon.json?key=$apiKey"
            val request = Request.Builder().url(url).build()
            val response = NasConnectionManager.publicHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null

            val json = JSONObject(body)
            val addresses = json.optJSONArray("addresses") ?: return null
            if (addresses.length() > 0) {
                val addr = addresses.getJSONObject(0).optJSONObject("address")
                val roadName = addr?.optString("street", "") ?: ""
                val speedLimitStr = addr?.optString("speedLimit", "") ?: ""

                val parsedSpeed = parseSpeedString(speedLimitStr)
                if (parsedSpeed > 0) {
                    return SpeedLimitResult(
                        speedLimit = parsedSpeed,
                        source = "TomTom API",
                        roadName = roadName
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi TomTom API: ${e.message}")
        }
        return null
    }

    /**
     * Trích xuất số tốc độ (km/h) từ chuỗi tag maxspeed (VD: "50", "60 km/h", "80", "50 mph")
     */
    fun parseSpeedString(rawStr: String): Int {
        if (rawStr.isBlank()) return 0
        val clean = rawStr.lowercase().trim()
        val numMatch = Regex("""\d+""").find(clean)?.value?.toIntOrNull() ?: return 0

        return if (clean.contains("mph")) {
            (numMatch * 1.60934).roundToInt()
        } else {
            numMatch
        }
    }
}
