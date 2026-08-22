package com.example.tymap.service

import android.content.Context
import com.example.tymap.repository.RouteInfo
import com.example.tymap.utils.PolylineDecoder
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class RoutingEngine(private val client: OkHttpClient) {

    /**
     * Fetches routes from both OSRM and Valhalla simultaneously.
     */
    suspend fun fetchOsrmAndValhalla(
        context: Context,
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        onProgressiveUpdate: ((List<RouteInfo>) -> Unit)? = null
    ): List<RouteInfo> {
        return fetchRouteWithFallback(context, startLat, startLng, destLat, destLng, onProgressiveUpdate = onProgressiveUpdate) ?: emptyList()
    }

    /**
     * Progressive multi-engine route calculation:
     * 1. FAST-TRACK (ƯU TIÊN 1): Gọi GraphHopper (NAS Server/Cloud) trước tiên để hiển thị NGAY LẬP TỨC (15-30ms).
     * 2. Phát callback onProgressiveUpdate ngay khi có tuyến đường đầu tiên (không cần chờ các dịch vụ khác).
     * 3. Chạy song song các engine còn lại (OSRM, Valhalla, ORS) và nạp cảnh báo ngầm không làm đơ giao diện.
     */
    suspend fun fetchRouteWithFallback(
        context: Context,
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        priorityList: List<String> = emptyList(),
        onProgressiveUpdate: ((List<RouteInfo>) -> Unit)? = null
    ): List<RouteInfo>? = coroutineScope {
        val vehicleType = PrefsHelper.getInt(context, "vehicle_type", 1)
        val accumulatedRoutes = mutableListOf<RouteInfo>()
        val emitted = java.util.concurrent.atomic.AtomicBoolean(false)

        // 1. FAST-TRACK: Ưu tiên tuyệt đối GraphHopper
        val ghKey = PrefsHelper.getSecureString(context, "api_key_gh", "")
        try {
            val fastGhRoutes = fetchGraphHopperRoute(context, startLat, startLng, destLat, destLng, ghKey, vehicleType)
            if (!fastGhRoutes.isNullOrEmpty()) {
                synchronized(accumulatedRoutes) {
                    accumulatedRoutes.addAll(fastGhRoutes)
                }
                emitted.set(true)
                // Hiển thị ngay lập tức lên bản đồ cho người dùng!
                withContext(Dispatchers.Main) {
                    onProgressiveUpdate?.invoke(fastGhRoutes.mapIndexed { i, r -> r.copy(isSelected = (i == 0)) })
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RoutingEngine", "Fast-track GraphHopper failed: ${e.message}")
        }

        // 2. Chạy song song các engine còn lại để bổ sung tuyến đường thay thế (Alternatives)
        val otherEngines = mutableListOf(
            "OSRM Backend",
            "Valhalla Routing",
            "OpenRouteService (ORS)"
        )
        val customUrl = PrefsHelper.getString(context, "custom_routing_url", "").trim()
        if (customUrl.isNotEmpty()) {
            otherEngines.add(0, "Tùy chỉnh (Self-Hosted OSRM)")
        }

        val deferreds = otherEngines.map { engine ->
            async(Dispatchers.IO) {
                val key = when {
                    engine.contains("OpenRouteService") || engine.contains("ORS") -> PrefsHelper.getSecureString(context, "api_key_ors", "")
                    else -> null
                }
                try {
                    val routes = fetchRoute(context, startLat, startLng, destLat, destLng, engine, key, vehicleType)
                    if (!routes.isNullOrEmpty()) {
                        synchronized(accumulatedRoutes) {
                            accumulatedRoutes.addAll(routes)
                        }
                        val sorted = synchronized(accumulatedRoutes) {
                            accumulatedRoutes.sortedWith(
                                compareByDescending<RouteInfo> { it.engineName.contains("NAS") || it.engineName.contains("GraphHopper") }
                                    .thenBy { it.distance }
                            ).mapIndexed { index, route -> route.copy(isSelected = (index == 0)) }
                        }
                        withContext(Dispatchers.Main) {
                            onProgressiveUpdate?.invoke(sorted)
                        }
                    }
                    routes
                } catch (e: Exception) {
                    null
                }
            }
        }

        // Chờ các engine phụ hoàn tất
        deferreds.awaitAll()

        val finalRoutes = synchronized(accumulatedRoutes) {
            if (accumulatedRoutes.isEmpty()) return@coroutineScope null
            accumulatedRoutes.sortedWith(
                compareByDescending<RouteInfo> { it.engineName.contains("NAS") || it.engineName.contains("GraphHopper") }
                    .thenBy { it.distance }
            ).mapIndexed { index, route -> route.copy(isSelected = (index == 0)) }
        }

        // 3. Tải cảnh báo giao thông ngầm (Hoàn toàn không chặn luồng hiển thị bản đồ)
        val selectedRoute = finalRoutes.firstOrNull { it.isSelected } ?: finalRoutes.firstOrNull()
        if (selectedRoute != null && selectedRoute.polyline.isNotEmpty()) {
            launch(Dispatchers.IO) {
                try {
                    TrafficWarningManager.fetchRouteWarningsFromGoong(context, selectedRoute.polyline)
                } catch (e: Exception) {
                    android.util.Log.e("RoutingEngine", "Warning fetch background error: ${e.message}")
                }
            }
        }

        finalRoutes
    }

    suspend fun fetchRoute(
        context: Context,
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        engine: String = "OSRM Backend",
        apiKey: String? = null,
        vehicleType: Int = 0
    ): List<RouteInfo>? {
        val avoidHighways = vehicleType == 1
        return when {
            engine == "Tùy chỉnh (Self-Hosted OSRM)" -> fetchCustomOsrmRoute(context, startLat, startLng, destLat, destLng, avoidHighways)
            engine.contains("OpenRouteService") || engine == "ORS" -> fetchOrsRoute(startLat, startLng, destLat, destLng, apiKey, avoidHighways)
            engine.contains("GraphHopper") -> fetchGraphHopperRoute(context, startLat, startLng, destLat, destLng, apiKey, vehicleType)
            engine.contains("Valhalla") -> fetchValhallaRoute(startLat, startLng, destLat, destLng, avoidHighways)
            else -> fetchOsrmRoute(startLat, startLng, destLat, destLng, avoidHighways)
        }
    }

    /**
     * Fast OSRM reroute for Intelligent Chaser Engine (ICE)
     */
    suspend fun fetchFastOsrmReroute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        avoidHighways: Boolean = false
    ): List<RouteInfo>? {
        return fetchOsrmRoute(startLat, startLng, destLat, destLng, avoidHighways)?.mapIndexed { i, r ->
            r.copy(engineName = "OSRM", isSelected = (i == 0))
        }
    }

    private suspend fun fetchOsrmRoute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        avoidHighways: Boolean
    ): List<RouteInfo>? {
        val url = "https://router.project-osrm.org/route/v1/driving/$startLng,$startLat;$destLng,$destLat?steps=true&geometries=polyline&overview=full&alternatives=true"
        val request = Request.Builder().url(url).build()
        
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "OSRM Error: ${response.code}")
                    return null
                }
                parseOsrmResponse(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchOrsRoute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        apiKey: String?,
        avoidHighways: Boolean
    ): List<RouteInfo>? {
        val key = apiKey?.trim() ?: return null
        if (key.isEmpty()) return null
        val url = "https://api.openrouteservice.org/v2/directions/driving-car"
        val json = JSONObject().apply {
            put("coordinates", JSONArray().apply {
                put(JSONArray(listOf(startLng, startLat)))
                put(JSONArray(listOf(destLng, destLat)))
            })
            if (avoidHighways) {
                put("options", JSONObject().apply {
                    put("avoid_features", JSONArray().apply {
                        put("highways")
                        put("motorways")
                    })
                })
            }
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", key)
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "ORS Error: ${response.code}")
                    return null
                }
                parseOrsResponse(respBody)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchGraphHopperRoute(
        context: Context,
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        apiKey: String?,
        vehicleType: Int
    ): List<RouteInfo>? {
        val key = apiKey?.trim() ?: ""
        
        // Cấu hình Profile Xe máy (scooter) hoặc Ô tô (car) theo vehicleType
        val profile = if (vehicleType == 1) "scooter" else "car"

        // Đọc cài đặt Custom Weighting từ PrefsHelper
        val avoidTolls = PrefsHelper.getBoolean(context, "avoid_tolls", false)
        val avoidFerries = PrefsHelper.getBoolean(context, "avoid_ferries", false)
        
        // Nếu không có API Key, tự động chuyển sang Server NAS tự dựng (192.168.1.114:8989)
        if (key.isEmpty()) {
            return fetchNasGraphHopperRoute(startLat, startLng, destLat, destLng, profile, avoidTolls, avoidFerries)
        }

        val url = "https://graphhopper.com/api/1/route?point=$startLat,$startLng&point=$destLat,$destLng&profile=$profile&locale=vi&key=$key&steps=true&points_encoded=true&algorithm=alternative_route"
        val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "GraphHopper Cloud Error: ${response.code}, falling back to NAS Server")
                    return fetchNasGraphHopperRoute(startLat, startLng, destLat, destLng, profile, avoidTolls, avoidFerries)
                }
                parseGraphHopperResponse(body)
            }
        } catch (e: Exception) {
            android.util.Log.e("RoutingEngine", "GraphHopper Cloud Exception: ${e.message}, falling back to NAS Server")
            fetchNasGraphHopperRoute(startLat, startLng, destLat, destLng, profile, avoidTolls, avoidFerries)
        }
    }

    /**
     * Tự kết nối đến Server GraphHopper riêng trên NAS (192.168.1.114:8989)
     * Trích xuất Tọa độ [Latitude, Longitude] & Hướng dẫn Turn-by-Turn tiếng Việt
     * Hỗ trợ Custom Weighting: Né trạm thu phí (avoid_tolls), Né phà/đò (avoid_ferries)
     */
    suspend fun fetchNasGraphHopperRoute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        profile: String = "scooter",
        avoidTolls: Boolean = false,
        avoidFerries: Boolean = false,
        nasIp: String = "192.168.1.114"
    ): List<RouteInfo>? {
        var url = "http://$nasIp:8989/route?point=$startLat,$startLng&point=$destLat,$destLng&profile=$profile&locale=vi&points_encoded=false&instructions=true"

        // Nếu bật Custom Weighting Né Trạm thu phí / Né Phà đò -> Thêm custom_model JSON và ch.disable=true
        if (avoidTolls || avoidFerries) {
            val priorityRules = JSONArray()
            if (avoidTolls) {
                val tollRule = JSONObject().apply {
                    put("if", "toll != NO")
                    put("multiply_by", "0.0")
                }
                priorityRules.put(tollRule)
            }
            if (avoidFerries) {
                val ferryRule = JSONObject().apply {
                    put("if", "road_environment == FERRY")
                    put("multiply_by", "0.0")
                }
                priorityRules.put(ferryRule)
            }
            val customModelJson = JSONObject().apply {
                put("priority", priorityRules)
            }.toString()

            val encodedCustomModel = java.net.URLEncoder.encode(customModelJson, "UTF-8")
            url += "&custom_model=$encodedCustomModel&ch.disable=true"
        }

        val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "NAS GraphHopper Error: ${response.code}")
                    return null
                }
                parseNasGraphHopperResponse(body)
            }
        } catch (e: Exception) {
            android.util.Log.e("RoutingEngine", "NAS GraphHopper Connection Failed: ${e.message}")
            null
        }
    }

    private suspend fun parseNasGraphHopperResponse(body: String): List<RouteInfo> = withContext(Dispatchers.Default) {
        val json = JSONObject(body)
        val pathsJson = json.optJSONArray("paths") ?: return@withContext emptyList<RouteInfo>()
        val result = mutableListOf<RouteInfo>()

        for (i in 0 until pathsJson.length()) {
            val path = pathsJson.getJSONObject(i)
            val distance = path.getDouble("distance")
            val duration = path.getLong("time") / 1000.0

            // 1. Chuyển đổi geometry.coordinates [Longitude, Latitude] -> LatLng(Latitude, Longitude)
            val points = mutableListOf<Pair<Double, Double>>()
            if (path.has("points")) {
                val pointsObj = path.get("points")
                if (pointsObj is JSONObject && pointsObj.has("coordinates")) {
                    val coordsArray = pointsObj.getJSONArray("coordinates")
                    for (j in 0 until coordsArray.length()) {
                        val coord = coordsArray.getJSONArray(j)
                        val lng = coord.getDouble(0)
                        val lat = coord.getDouble(1)
                        points.add(lat to lng) // LatLng(Lat, Long)
                    }
                } else if (pointsObj is String) {
                    points.addAll(PolylineDecoder.decode(pointsObj, 5).map { it.latitude to it.longitude })
                }
            }

            // 2. Đọc danh sách chỉ dẫn Turn-by-Turn (Legs/Steps instructions)
            val steps = mutableListOf<com.example.tymap.repository.StepInfo>()
            val instructions = path.optJSONArray("instructions")
            if (instructions != null) {
                for (j in 0 until instructions.length()) {
                    val instr = instructions.getJSONObject(j)
                    val interval = instr.optJSONArray("interval")
                    val stepLocation = if (interval != null && interval.length() > 0) {
                        points.getOrElse(interval.getInt(0)) { 0.0 to 0.0 }
                    } else {
                        0.0 to 0.0
                    }

                    steps.add(com.example.tymap.repository.StepInfo(
                        instruction = instr.optString("text", "Đi tiếp"),
                        distance = instr.optDouble("distance", 0.0),
                        duration = instr.optLong("time", 0L) / 1000.0,
                        maneuverIcon = mapManeuverToIcon("GraphHopper", instr.optInt("sign", 0)),
                        roadName = instr.optString("street_name", ""),
                        location = stepLocation
                    ))
                }
            }

            result.add(RouteInfo(
                polyline = points,
                distance = distance,
                duration = duration,
                steps = steps,
                isSelected = (i == 0),
                engineName = "NAS GraphHopper"
            ))
        }
        result
    }

    private suspend fun fetchValhallaRoute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        avoidHighways: Boolean
    ): List<RouteInfo>? {
        val url = "https://valhalla1.openstreetmap.de/route"
        val json = JSONObject().apply {
            put("locations", JSONArray().apply {
                put(JSONObject().apply { put("lat", startLat); put("lon", startLng) })
                put(JSONObject().apply { put("lat", destLat); put("lon", destLng) })
            })
            put("costing", "auto")
            if (avoidHighways) {
                put("costing_options", JSONObject().apply {
                    put("auto", JSONObject().apply {
                        put("use_highways", 0)
                    })
                })
            }
            put("directions_options", JSONObject().apply { 
                put("units", "kilometers")
                put("language", "vi-VN")
            })
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        return try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "Valhalla Error: ${response.code}")
                    return null
                }
                parseValhallaResponse(respBody)
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchCustomOsrmRoute(
        context: Context,
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        avoidHighways: Boolean
    ): List<RouteInfo>? {
        val customUrl = PrefsHelper.getString(context, "custom_routing_url", "").trim()
        if (customUrl.isEmpty()) {
            return fetchOsrmRoute(startLat, startLng, destLat, destLng, avoidHighways)
        }
        val baseUrl = customUrl.removeSuffix("/")
        val url = "$baseUrl/route/v1/driving/$startLng,$startLat;$destLng,$destLat?steps=true&geometries=polyline&overview=full&alternatives=true"
        val request = Request.Builder().url(url).build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "Custom OSRM Error: ${response.code}, falling back to OSRM demo")
                    return fetchOsrmRoute(startLat, startLng, destLat, destLng, avoidHighways)
                }
                parseOsrmResponse(body)
            }
        } catch (e: Exception) {
            android.util.Log.e("RoutingEngine", "Custom OSRM Exception: ${e.message}, falling back to OSRM demo")
            fetchOsrmRoute(startLat, startLng, destLat, destLng, avoidHighways)
        }
    }

    private suspend fun parseOsrmResponse(body: String): List<RouteInfo> = withContext(Dispatchers.Default) {
        val json = JSONObject(body)
        val routesJson = json.getJSONArray("routes")
        val result = mutableListOf<RouteInfo>()
        for (i in 0 until routesJson.length()) {
            val route = routesJson.getJSONObject(i)
            val geometry = route.getString("geometry")
            val points = PolylineDecoder.decode(geometry, 5).map { it.latitude to it.longitude }
            val distance = route.getDouble("distance")
            val duration = route.getDouble("duration")
            
            val steps = mutableListOf<com.example.tymap.repository.StepInfo>()
            val legs = route.getJSONArray("legs")
            if (legs.length() > 0) {
                val stepsJson = legs.getJSONObject(0).getJSONArray("steps")
                for (j in 0 until stepsJson.length()) {
                    val step = stepsJson.getJSONObject(j)
                    val maneuver = step.getJSONObject("maneuver")
                    
                    steps.add(com.example.tymap.repository.StepInfo(
                        instruction = step.optString("name", "Tiếp tục"),
                        distance = step.getDouble("distance"),
                        duration = step.getDouble("duration"),
                        maneuverIcon = mapManeuverToIcon("OSRM", maneuver),
                        roadName = step.optString("name", ""),
                        location = maneuver.getJSONArray("location").let { it.getDouble(1) to it.getDouble(0) }
                    ))
                }
            }
            result.add(RouteInfo(points, distance, duration, steps, i == 0, "OSRM"))
        }
        result
    }

    private suspend fun parseOrsResponse(body: String): List<RouteInfo> = withContext(Dispatchers.Default) {
        val json = JSONObject(body)
        val routesJson = json.getJSONArray("routes")
        val result = mutableListOf<RouteInfo>()
        for (i in 0 until routesJson.length()) {
            val route = routesJson.getJSONObject(i)
            val geometry = route.getString("geometry")
            val points = PolylineDecoder.decode(geometry, 5).map { it.latitude to it.longitude }
            
            val summary = route.getJSONObject("summary")
            val distance = summary.getDouble("distance")
            val duration = summary.getDouble("duration")
            
            val steps = mutableListOf<com.example.tymap.repository.StepInfo>()
            val segments = route.optJSONArray("segments")
            if (segments != null && segments.length() > 0) {
                val stepsJson = segments.getJSONObject(0).getJSONArray("steps")
                for (j in 0 until stepsJson.length()) {
                    val step = stepsJson.getJSONObject(j)
                    val wayPoints = step.getJSONArray("way_points")
                    steps.add(com.example.tymap.repository.StepInfo(
                        instruction = step.optString("instruction", "Đi tiếp"),
                        distance = step.getDouble("distance"),
                        duration = step.getDouble("duration"),
                        maneuverIcon = mapManeuverToIcon("ORS", step.optInt("type", 0)),
                        roadName = step.optString("name", ""),
                        location = points.getOrElse(wayPoints.getInt(0)) { 0.0 to 0.0 }
                    ))
                }
            }
            result.add(RouteInfo(points, distance, duration, steps, i == 0, "OpenRouteService"))
        }
        result
    }

    private suspend fun parseGraphHopperResponse(body: String): List<RouteInfo> = withContext(Dispatchers.Default) {
        val json = JSONObject(body)
        val pathsJson = json.optJSONArray("paths") ?: return@withContext emptyList<RouteInfo>()
        val result = mutableListOf<RouteInfo>()
        for (i in 0 until pathsJson.length()) {
            val path = pathsJson.getJSONObject(i)
            val distance = path.getDouble("distance")
            val duration = path.getLong("time") / 1000.0
            
            val points = mutableListOf<Pair<Double, Double>>()
            if (path.has("points")) {
                val pointsObj = path.get("points")
                if (pointsObj is String) {
                    points.addAll(PolylineDecoder.decode(pointsObj, 5).map { it.latitude to it.longitude })
                } else if (pointsObj is JSONObject && pointsObj.has("coordinates")) {
                    val coords = pointsObj.getJSONArray("coordinates")
                    for (j in 0 until coords.length()) {
                        val c = coords.getJSONArray(j)
                        points.add(c.getDouble(1) to c.getDouble(0))
                    }
                }
            }
            
            val steps = mutableListOf<com.example.tymap.repository.StepInfo>()
            val instructions = path.optJSONArray("instructions")
            if (instructions != null) {
                for (j in 0 until instructions.length()) {
                    val instr = instructions.getJSONObject(j)
                    val interval = instr.getJSONArray("interval")
                    steps.add(com.example.tymap.repository.StepInfo(
                        instruction = instr.getString("text"),
                        distance = instr.getDouble("distance"),
                        duration = instr.getLong("time") / 1000.0,
                        maneuverIcon = mapManeuverToIcon("GraphHopper", instr.optInt("sign", 0)),
                        roadName = instr.optString("street_name", ""),
                        location = points.getOrElse(interval.getInt(0)) { 0.0 to 0.0 }
                    ))
                }
            }
            result.add(RouteInfo(points, distance, duration, steps, i == 0, "GraphHopper"))
        }
        result
    }

    private suspend fun parseValhallaResponse(body: String): List<RouteInfo> = withContext(Dispatchers.Default) {
        val json = JSONObject(body)
        val trip = json.getJSONObject("trip")
        val legs = trip.getJSONArray("legs")
        val result = mutableListOf<RouteInfo>()
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val geometry = leg.getString("shape")
            val points = PolylineDecoder.decode(geometry, 6).map { it.latitude to it.longitude }
            val summary = leg.getJSONObject("summary")
            val distance = summary.getDouble("length") * 1000.0
            val duration = summary.getDouble("time")
            
            val steps = mutableListOf<com.example.tymap.repository.StepInfo>()
            val maneuvers = leg.optJSONArray("maneuvers")
            if (maneuvers != null) {
                for (j in 0 until maneuvers.length()) {
                    val m = maneuvers.getJSONObject(j)
                    steps.add(com.example.tymap.repository.StepInfo(
                        instruction = m.getString("instruction"),
                        distance = m.getDouble("length") * 1000.0,
                        duration = m.getDouble("time"),
                        maneuverIcon = mapManeuverToIcon("Valhalla", m.getInt("type")),
                        roadName = m.optString("street_names", ""),
                        location = points.getOrElse(m.getInt("begin_shape_index")) { 0.0 to 0.0 }
                    ))
                }
            }
            result.add(RouteInfo(points, distance, duration, steps, i == 0, "Valhalla"))
        }
        result
    }

    /**
     * Unified mapping function for all routing engines.
     * Returns a standard icon index (0-20) for the ESP32.
     * Based on the "BỔ SUNG CHI TIẾT VỀ BỘ ICON RẼ" table.
     */
    private fun mapManeuverToIcon(engine: String, maneuver: Any): Int {
        return try {
            when (engine) {
                "OSRM", "Mapbox" -> {
                    val m = maneuver as JSONObject
                    val type = m.getString("type")
                    val modifier = m.optString("modifier", "")
                    
                    when (type) {
                        "turn" -> when (modifier) {
                            "straight" -> 0
                            "slight right" -> 1
                            "right" -> 2
                            "sharp right" -> 3
                            "slight left" -> 4
                            "left" -> 5
                            "sharp left" -> 6
                            "uturn" -> 7 // default to left
                            else -> 0
                        }
                        "continue" -> 0
                        "fork" -> if (modifier.contains("left")) 9 else 10
                        "roundabout" -> {
                            val exit = m.optInt("exit", 1)
                            when (exit) {
                                1 -> 11
                                2 -> 12
                                3 -> 13
                                else -> 12
                            }
                        }
                        "arrive" -> 14
                        "merge" -> if (modifier.contains("left")) 15 else 16
                        "off ramp" -> if (modifier.contains("left")) 17 else 18
                        "on ramp" -> 0 // default to straight
                        "ferry" -> 19
                        else -> 0
                    }
                }
                "GraphHopper" -> {
                    val sign = maneuver as Int
                    when (sign) {
                        0 -> 0   // Continue
                        1 -> 1   // Slight right
                        2 -> 2   // Turn right
                        3 -> 3   // Sharp right
                        -1 -> 4  // Slight left
                        -2 -> 5  // Turn left
                        -3 -> 6  // Sharp left
                        4 -> 7   // U-turn left
                        5 -> 8   // U-turn right
                        6 -> 14  // Arrive
                        7 -> 11  // Roundabout
                        else -> 20 // Unknown
                    }
                }
                "Valhalla", "ORS" -> {
                    // Simplified fallback mapping for Valhalla and ORS types
                    val type = maneuver as Int
                    when (type) {
                        0, 6 -> 0  // Continue
                        1, 5 -> 1  // Slight right
                        2 -> 2     // Right
                        3 -> 3     // Sharp right
                        4 -> 4     // Slight left
                        10, 11 -> 5 // Left
                        12 -> 6    // Sharp left
                        7 -> 7     // U-turn
                        8 -> 12    // Roundabout
                        13 -> 14   // Arrive
                        else -> 20
                    }
                }
                else -> 20
            }
        } catch (e: Exception) { 20 }
    }
}
