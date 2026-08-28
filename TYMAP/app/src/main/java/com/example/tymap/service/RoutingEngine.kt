package com.example.tymap.service

import android.content.Context
import com.example.tymap.repository.RouteInfo
import com.example.tymap.utils.NasConnectionManager
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
     * Progressive multi-engine route calculation with Smart Circuit Breaker:
     * 1. FAST-TRACK (ƯU TIÊN 1): Nếu NAS khả dụng, gọi GraphHopper NAS trước tiên để hiển thị NGAY LẬP TỨC (15-30ms).
     * 2. FAST-FAIL (KHÔNG CHỜ ĐỢI): Nếu mất kết nối NAS hoặc ngoài LAN (4G/LTE), NAS Circuit Breaker ngắt ngay trong < 1s (hoặc 0ms nếu đã ngắt trước đó).
     * 3. PARALLEL FALLBACK: Kích hoạt đồng thời các engine đám mây (Goong, OSRM, Valhalla, ORS, GH Cloud) và phát onProgressiveUpdate ngay khi engine đầu tiên trả về kết quả!
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

        // 1. FAST-TRACK: Ưu tiên GraphHopper NAS (15-30ms) nếu NAS khả dụng
        val ghKey = PrefsHelper.getSecureString(context, "api_key_gh", "")
        if (NasConnectionManager.isNasPotentiallyAvailable(context)) {
            try {
                android.util.Log.d("RoutingEngine", "Fetching Fast-Track GraphHopper NAS route with vehicleType: $vehicleType (1=scooter, 0=car)")
                val fastGhRoutes = fetchGraphHopperRoute(context, startLat, startLng, destLat, destLng, ghKey, vehicleType)
                if (!fastGhRoutes.isNullOrEmpty()) {
                    val selectedRoutes = fastGhRoutes.mapIndexed { i, r -> r.copy(isSelected = (i == 0)) }
                    withContext(Dispatchers.Main) {
                        onProgressiveUpdate?.invoke(selectedRoutes)
                    }
                    val primaryPolyline = selectedRoutes.firstOrNull()?.polyline
                    if (!primaryPolyline.isNullOrEmpty()) {
                        TrafficWarningManager.fetchRouteWarnings(context, primaryPolyline)
                    }
                    return@coroutineScope selectedRoutes
                }
            } catch (e: Exception) {
                android.util.Log.e("RoutingEngine", "Fast-track GraphHopper failed: ${e.message}")
            }
        } else {
            android.util.Log.d("RoutingEngine", "NAS is offline/remote, skipping direct NAS attempt to guarantee zero-wait cloud fallback.")
        }

        // 2. Chạy SONG SONG các engine đám mây với Progressive UI Update
        val otherEngines = mutableListOf<String>()
        
        val goongKey = PrefsHelper.getSecureString(context, "api_key_goong", "").trim()
        if (goongKey.isNotEmpty()) {
            otherEngines.add("Goong API (Việt Nam)")
        }

        val customUrl = PrefsHelper.getString(context, "custom_routing_url", "").trim()
        if (customUrl.isNotEmpty()) {
            otherEngines.add("Tùy chỉnh (Self-Hosted OSRM)")
        }

        otherEngines.add("OSRM Backend")
        otherEngines.add("Valhalla Routing")

        val orsKey = PrefsHelper.getSecureString(context, "api_key_ors", "").trim()
        if (orsKey.isNotEmpty()) {
            otherEngines.add("OpenRouteService (ORS)")
        }

        if (ghKey.isNotEmpty()) {
            otherEngines.add("GraphHopper Cloud")
        }

        val deferreds = otherEngines.map { engine ->
            async(Dispatchers.IO) {
                val key = when {
                    engine.contains("Goong") -> goongKey
                    engine.contains("OpenRouteService") || engine.contains("ORS") -> orsKey
                    engine.contains("GraphHopper") -> ghKey
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
                                compareByDescending<RouteInfo> { it.engineName.contains("NAS") || it.engineName.contains("Goong") || it.engineName.contains("GraphHopper") }
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

        // Chờ các engine hoàn tất
        deferreds.awaitAll()

        val finalRoutes = synchronized(accumulatedRoutes) {
            if (accumulatedRoutes.isEmpty()) return@coroutineScope null
            accumulatedRoutes.sortedWith(
                compareByDescending<RouteInfo> { it.engineName.contains("NAS") || it.engineName.contains("Goong") || it.engineName.contains("GraphHopper") }
                    .thenBy { it.distance }
            ).mapIndexed { index, route -> route.copy(isSelected = (index == 0)) }
        }

        // 3. Tải cảnh báo giao thông ngầm (Hoàn toàn không chặn luồng hiển thị bản đồ)
        val selectedRoute = finalRoutes?.firstOrNull { it.isSelected } ?: finalRoutes?.firstOrNull()
        if (selectedRoute != null && selectedRoute.polyline.isNotEmpty()) {
            launch(Dispatchers.IO) {
                try {
                    TrafficWarningManager.fetchRouteWarnings(context, selectedRoute.polyline)
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
            engine.contains("Goong") -> fetchGoongRoute(startLat, startLng, destLat, destLng, apiKey, vehicleType)
            engine == "Tùy chỉnh (Self-Hosted OSRM)" -> fetchCustomOsrmRoute(context, startLat, startLng, destLat, destLng, avoidHighways)
            engine.contains("OpenRouteService") || engine == "ORS" -> fetchOrsRoute(startLat, startLng, destLat, destLng, apiKey, avoidHighways)
            engine.contains("GraphHopper Cloud") -> fetchGraphHopperCloudRoute(startLat, startLng, destLat, destLng, apiKey, vehicleType)
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

    private suspend fun fetchGoongRoute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        apiKey: String?,
        vehicleType: Int
    ): List<RouteInfo>? {
        val key = apiKey?.trim() ?: return null
        if (key.isEmpty()) return null

        val vehicle = if (vehicleType == 1) "bike" else "car"
        val url = "https://rsapi.goong.io/Direction?origin=$startLat,$startLng&destination=$destLat,$destLng&vehicle=$vehicle&api_key=$key"
        val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()

        return try {
            NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "Goong Error: ${response.code}")
                    return null
                }
                parseGoongResponse(body)
            }
        } catch (e: Exception) {
            android.util.Log.e("RoutingEngine", "Goong Exception: ${e.message}")
            null
        }
    }

    private suspend fun parseGoongResponse(body: String): List<RouteInfo> = withContext(Dispatchers.Default) {
        val json = JSONObject(body)
        val routesJson = json.optJSONArray("routes") ?: return@withContext emptyList<RouteInfo>()
        val result = mutableListOf<RouteInfo>()

        for (i in 0 until routesJson.length()) {
            val route = routesJson.getJSONObject(i)
            val overviewPolyline = route.optJSONObject("overview_polyline")?.optString("points", "") ?: ""
            val points = if (overviewPolyline.isNotEmpty()) {
                PolylineDecoder.decode(overviewPolyline, 5).map { it.latitude to it.longitude }
            } else {
                emptyList()
            }

            val legs = route.optJSONArray("legs")
            var totalDistance = 0.0
            var totalDuration = 0.0
            val steps = mutableListOf<com.example.tymap.repository.StepInfo>()

            if (legs != null && legs.length() > 0) {
                val leg = legs.getJSONObject(0)
                totalDistance = leg.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
                totalDuration = leg.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0

                val stepsJson = leg.optJSONArray("steps")
                if (stepsJson != null) {
                    for (j in 0 until stepsJson.length()) {
                        val step = stepsJson.getJSONObject(j)
                        val htmlInstr = step.optString("html_instructions", "Đi tiếp")
                        val cleanInstr = android.text.Html.fromHtml(htmlInstr, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                        val stepDist = step.optJSONObject("distance")?.optDouble("value", 0.0) ?: 0.0
                        val stepDur = step.optJSONObject("duration")?.optDouble("value", 0.0) ?: 0.0
                        val maneuverStr = step.optString("maneuver", "")
                        val startLoc = step.optJSONObject("start_location")
                        val stepLat = startLoc?.optDouble("lat", 0.0) ?: 0.0
                        val stepLng = startLoc?.optDouble("lng", 0.0) ?: 0.0

                        steps.add(com.example.tymap.repository.StepInfo(
                            instruction = cleanInstr,
                            distance = stepDist,
                            duration = stepDur,
                            maneuverIcon = mapGoongManeuverToIcon(maneuverStr),
                            roadName = cleanInstr,
                            location = stepLat to stepLng
                        ))
                    }
                }
            }

            val (realisticDuration, realisticSteps) = calculateRealisticDuration(totalDuration, totalDistance, steps)
            result.add(RouteInfo(
                polyline = points,
                distance = totalDistance,
                duration = realisticDuration,
                steps = realisticSteps,
                isSelected = (i == 0),
                engineName = "Goong (Việt Nam)"
            ))
        }
        result
    }

    private fun mapGoongManeuverToIcon(maneuver: String): Int {
        return when {
            maneuver.contains("turn-slight-right") || maneuver.contains("fork-slight-right") -> 1
            maneuver.contains("turn-sharp-right") -> 3
            maneuver.contains("turn-right") || maneuver.contains("fork-right") -> 2
            maneuver.contains("turn-slight-left") || maneuver.contains("fork-slight-left") -> 4
            maneuver.contains("turn-sharp-left") -> 6
            maneuver.contains("turn-left") || maneuver.contains("fork-left") -> 5
            maneuver.contains("uturn") -> 7
            maneuver.contains("roundabout") || maneuver.contains("rotary") -> 12
            maneuver.contains("straight") || maneuver.contains("continue") -> 0
            maneuver.contains("merge") -> 15
            maneuver.contains("ramp") -> 17
            else -> 0
        }
    }

    private suspend fun fetchGraphHopperCloudRoute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        apiKey: String?,
        vehicleType: Int
    ): List<RouteInfo>? {
        val key = apiKey?.trim() ?: return null
        if (key.isEmpty()) return null

        val cloudProfile = if (vehicleType == 1) "bike" else "car"
        val url = "https://graphhopper.com/api/1/route?point=$startLat,$startLng&point=$destLat,$destLng&profile=$cloudProfile&locale=vi&key=$key&steps=true&points_encoded=true&algorithm=alternative_route"
        val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()

        return try {
            NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "GraphHopper Cloud Error: ${response.code}")
                    return null
                }
                parseGraphHopperResponse(body)
            }
        } catch (e: Exception) {
            android.util.Log.e("RoutingEngine", "GraphHopper Cloud Exception: ${e.message}")
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
        val profile = if (vehicleType == 1) "scooter" else "car"
        val avoidTolls = PrefsHelper.getBoolean(context, "avoid_tolls", false)
        val avoidFerries = PrefsHelper.getBoolean(context, "avoid_ferries", false)
        
        // 1. Kiểm tra NAS Circuit Breaker trước khi thử kết nối
        if (NasConnectionManager.isNasPotentiallyAvailable(context)) {
            val ghBaseUrl = NasConnectionManager.getGraphHopperBaseUrl(context)
            val nasResult = fetchNasGraphHopperRoute(startLat, startLng, destLat, destLng, profile, avoidTolls, avoidFerries, ghBaseUrl)
            if (!nasResult.isNullOrEmpty()) {
                return nasResult
            }
        }
        
        // 2. Fallback sang GraphHopper Cloud nếu có API key
        if (key.isNotEmpty()) {
            return fetchGraphHopperCloudRoute(startLat, startLng, destLat, destLng, key, vehicleType)
        }

        return null
    }

    /**
     * Tự kết nối đến Server GraphHopper riêng trên NAS (LAN hoặc DuckDNS HTTPS)
     * Trích xuất Tọa độ [Latitude, Longitude] & Hướng dẫn Turn-by-Turn tiếng Việt
     * Hỗ trợ Custom Weighting: Né trạm thu phí (avoid_tolls), Né phà/đò (avoid_ferries)
     */
    suspend fun fetchNasGraphHopperRoute(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        profile: String = "scooter",
        avoidTolls: Boolean = false,
        avoidFerries: Boolean = false,
        baseUrl: String = "https://route.nas152.duckdns.org",
        allowAlternatives: Boolean = true
    ): List<RouteInfo>? {
        val candidateProfiles = when (profile) {
            "scooter" -> listOf("scooter", "car")
            else -> listOf("car", "scooter")
        }

        for (p in candidateProfiles) {
            val routes = tryFetchNasGraphHopperWithProfile(startLat, startLng, destLat, destLng, p, avoidTolls, avoidFerries, baseUrl, allowAlternatives)
            if (!routes.isNullOrEmpty()) {
                if (routes.size >= 3) {
                    return routes
                }
                val combinedRoutes = routes.toMutableList()
                for (fallbackProfile in candidateProfiles) {
                    if (fallbackProfile == p || combinedRoutes.size >= 3) continue
                    val extraRoutes = tryFetchNasGraphHopperWithProfile(startLat, startLng, destLat, destLng, fallbackProfile, avoidTolls, avoidFerries, baseUrl, allowAlternatives)
                    if (!extraRoutes.isNullOrEmpty()) {
                        for (extra in extraRoutes) {
                            if (combinedRoutes.size < 3 && combinedRoutes.none { Math.abs(it.distance - extra.distance) < 50.0 }) {
                                combinedRoutes.add(extra)
                            }
                        }
                    }
                }
                return combinedRoutes.mapIndexed { idx, r ->
                    val title = when (idx) {
                        0 -> "GraphHopper [Server Nhà] (Tối ưu)"
                        1 -> "GraphHopper [Server Nhà] (Đường tránh)"
                        2 -> "GraphHopper [Server Nhà] (Ngắn nhất)"
                        else -> "GraphHopper [Server Nhà] (Tuyến ${idx + 1})"
                    }
                    r.copy(engineName = title, isSelected = (idx == 0))
                }
            }
        }
        return null
    }

    private suspend fun tryFetchNasGraphHopperWithProfile(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        profile: String,
        avoidTolls: Boolean,
        avoidFerries: Boolean,
        baseUrl: String,
        allowAlternatives: Boolean
    ): List<RouteInfo>? {
        val priorityRules = JSONArray()
        if (avoidTolls) {
            priorityRules.put(JSONObject().apply {
                put("if", "toll != NO")
                put("multiply_by", 0.0)
            })
        }
        if (avoidFerries) {
            priorityRules.put(JSONObject().apply {
                put("if", "road_environment == FERRY")
                put("multiply_by", 0.0)
            })
        }

        var customModelParam = ""
        if (priorityRules.length() > 0) {
            val customModelJson = JSONObject().apply {
                put("priority", priorityRules)
            }.toString()
            val encodedCustomModel = java.net.URLEncoder.encode(customModelJson, "UTF-8")
            customModelParam = "&custom_model=$encodedCustomModel"
        }

        val cleanBaseUrl = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) baseUrl else "http://$baseUrl:8989"
        val urlAttempts = mutableListOf<String>()

        if (allowAlternatives) {
            urlAttempts.add("$cleanBaseUrl/route?point=$startLat,$startLng&point=$destLat,$destLng&profile=$profile&locale=vi&points_encoded=false&instructions=true&algorithm=alternative_route&alternative_route.max_paths=3&alternative_route.max_weight_factor=2.5&alternative_route.max_share_factor=0.95$customModelParam")
            urlAttempts.add("$cleanBaseUrl/route?point=$startLat,$startLng&point=$destLat,$destLng&profile=$profile&locale=vi&points_encoded=false&instructions=true&algorithm=alternative_route&alternative_route.max_paths=3$customModelParam")
        }
        if (customModelParam.isNotEmpty()) {
            urlAttempts.add("$cleanBaseUrl/route?point=$startLat,$startLng&point=$destLat,$destLng&profile=$profile&locale=vi&points_encoded=false&instructions=true$customModelParam")
        }
        urlAttempts.add("$cleanBaseUrl/route?point=$startLat,$startLng&point=$destLat,$destLng&profile=$profile&locale=vi&points_encoded=false&instructions=true")

        val nasClient = NasConnectionManager.nasHttpClient

        for (url in urlAttempts) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()
                nasClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use
                    if (response.isSuccessful) {
                        val routes = parseNasGraphHopperResponse(body)
                        if (routes.isNotEmpty()) {
                            NasConnectionManager.markNasSuccess()
                            return routes
                        }
                    } else {
                        android.util.Log.w("RoutingEngine", "NAS GraphHopper attempt returned ${response.code} for URL: $url")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RoutingEngine", "NAS GraphHopper attempt failed: ${e.message}")
                if (NasConnectionManager.isConnectionFailure(e)) {
                    NasConnectionManager.markNasFailed("NAS unreachable on $url: ${e.message}")
                    // Fast-fail: Đứt kết nối NAS thì dừng ngay, KHÔNG loop tiếp các URL còn lại để tránh delay!
                    break
                }
            }
        }
        return null
    }

    private suspend fun parseNasGraphHopperResponse(body: String): List<RouteInfo> = withContext(Dispatchers.Default) {
        val json = JSONObject(body)
        val pathsJson = json.optJSONArray("paths") ?: return@withContext emptyList<RouteInfo>()
        val result = mutableListOf<RouteInfo>()

        for (i in 0 until pathsJson.length()) {
            val path = pathsJson.getJSONObject(i)
            val distance = path.optDouble("distance", 0.0)
            val duration = path.optDouble("time", 0.0) / 1000.0

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
                        duration = instr.optDouble("time", 0.0) / 1000.0,
                        maneuverIcon = mapManeuverToIcon("GraphHopper", instr.optInt("sign", 0)),
                        roadName = instr.optString("street_name", ""),
                        location = stepLocation
                    ))
                }
            }

            val (realisticDuration, realisticSteps) = calculateRealisticDuration(duration, distance, steps)

            val routeTitle = when (i) {
                0 -> "GraphHopper [Server Nhà] (Tối ưu)"
                1 -> "GraphHopper [Server Nhà] (Đường tránh)"
                2 -> "GraphHopper [Server Nhà] (Ngắn nhất)"
                else -> "GraphHopper [Server Nhà] (Tuyến ${i + 1})"
            }

            result.add(RouteInfo(
                polyline = points,
                distance = distance,
                duration = realisticDuration,
                steps = realisticSteps,
                isSelected = (i == 0),
                engineName = routeTitle
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
            val (realisticDuration, realisticSteps) = calculateRealisticDuration(duration, distance, steps)
            result.add(RouteInfo(points, distance, realisticDuration, realisticSteps, i == 0, "OSRM"))
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
            val (realisticDuration, realisticSteps) = calculateRealisticDuration(duration, distance, steps)
            result.add(RouteInfo(points, distance, realisticDuration, realisticSteps, i == 0, "OpenRouteService"))
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
            val (realisticDuration, realisticSteps) = calculateRealisticDuration(duration, distance, steps)
            result.add(RouteInfo(points, distance, realisticDuration, realisticSteps, i == 0, "GraphHopper"))
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
            val (realisticDuration, realisticSteps) = calculateRealisticDuration(duration, distance, steps)
            result.add(RouteInfo(points, distance, realisticDuration, realisticSteps, i == 0, "Valhalla"))
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

    /**
     * Thuật toán hiệu chỉnh thời gian di chuyển (ETA) thực tế cho giao thông xe máy tại Việt Nam:
     * - Bù trừ vận tốc thực tế đô thị (18-22 km/h) so với vận tốc lý tưởng OSM (40-50 km/h)
     * - Cộng thời gian phạt tại các ngã ba, ngã tư, đèn đỏ (Turn & Traffic Light Penalty: 18-22s / ngã rẽ)
     * - Giúp ETA khớp với thực tế của Google Maps (khắc phục hoàn toàn độ lệch 21p vs 42p).
     */
    private fun calculateRealisticDuration(
        rawDurationSeconds: Double,
        distanceMeters: Double,
        steps: List<com.example.tymap.repository.StepInfo>,
        vehicleType: Int = 1 // 1=scooter, 0=car
    ): Pair<Double, List<com.example.tymap.repository.StepInfo>> {
        if (distanceMeters <= 0 || rawDurationSeconds <= 0) {
            return rawDurationSeconds to steps
        }

        val distanceKm = distanceMeters / 1000.0

        // 1. Phạt thời gian giao lộ & đèn tín hiệu (Turn Penalty)
        var turnCount = 0
        for (s in steps) {
            if (s.maneuverIcon != 0 && s.maneuverIcon != 14 && s.maneuverIcon != 20) { // Các bước rẽ chuyển hướng
                turnCount++
            }
        }
        val turnPenaltySec = if (vehicleType == 1) turnCount * 22.0 else turnCount * 30.0

        // 2. Vận tốc cơ sở thực tế theo chiều dài tuyến đường tại Việt Nam
        val baseSpeedKmh = if (vehicleType == 1) {
            when {
                distanceKm < 3.0 -> 18.0   // Đô thị cự ly gần (nhiều đèn đỏ/ngõ hẻm): 18 km/h
                distanceKm < 10.0 -> 21.0  // Đô thị trung bình: 21 km/h
                distanceKm < 25.0 -> 28.0  // Hỗn hợp nội/ngoại thành: 28 km/h
                else -> 36.0               // Đường dài / Quốc lộ: 36 km/h
            }
        } else {
            when {
                distanceKm < 3.0 -> 15.0   // Ô tô nội đô kẹt xe: 15 km/h
                distanceKm < 10.0 -> 18.0  // Ô tô đường phố: 18 km/h
                distanceKm < 25.0 -> 30.0  // Ô tô hỗn hợp: 30 km/h
                else -> 45.0               // Ô tô quốc lộ: 45 km/h
            }
        }

        val estimatedTravelSec = (distanceKm / baseSpeedKmh) * 3600.0
        val realisticTotalSec = Math.max(estimatedTravelSec + turnPenaltySec, rawDurationSeconds * 1.85)

        // 3. Phân bổ lại thời lượng cho từng step (tương thích mượt mà với TBT navigation)
        val scaleFactor = realisticTotalSec / rawDurationSeconds
        val adjustedSteps = steps.map { step ->
            val isTurn = (step.maneuverIcon != 0 && step.maneuverIcon != 14 && step.maneuverIcon != 20)
            val stepTurnBonus = if (isTurn) 15.0 else 0.0
            val newStepDuration = (step.duration * scaleFactor * 0.85) + stepTurnBonus
            step.copy(duration = newStepDuration)
        }

        return realisticTotalSec to adjustedSteps
    }
}
