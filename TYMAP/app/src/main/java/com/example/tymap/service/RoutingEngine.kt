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
        destLat: Double, destLng: Double
    ): List<RouteInfo> {
        return fetchRouteWithFallback(context, startLat, startLng, destLat, destLng) ?: emptyList()
    }

    /**
     * Parallel multi-engine route calculation across ALL engines:
     * OSRM (Self-Hosted/Demo), Valhalla, GraphHopper, and ORS.
     * Merges all results and sorts by shortest distance (meters) first.
     */
    suspend fun fetchRouteWithFallback(
        context: Context,
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        priorityList: List<String> = emptyList()
    ): List<RouteInfo>? = coroutineScope {
        val vehicleType = PrefsHelper.getInt(context, "vehicle_type", 0)
        val engines = mutableListOf(
            "OSRM Backend",
            "Valhalla Routing",
            "GraphHopper Routing",
            "OpenRouteService (ORS)"
        )
        val customUrl = PrefsHelper.getString(context, "custom_routing_url", "").trim()
        if (customUrl.isNotEmpty()) {
            engines.add(0, "Tùy chỉnh (Self-Hosted OSRM)")
        }

        val deferreds = engines.map { engine ->
            async(Dispatchers.IO) {
                val key = when {
                    engine.contains("OpenRouteService") || engine.contains("ORS") -> PrefsHelper.getSecureString(context, "api_key_ors", "")
                    engine.contains("GraphHopper") -> PrefsHelper.getSecureString(context, "api_key_gh", "")
                    else -> null
                }
                try {
                    fetchRoute(context, startLat, startLng, destLat, destLng, engine, key, vehicleType)
                } catch (e: Exception) {
                    null
                }
            }
        }

        val allResults = deferreds.awaitAll().filterNotNull().flatten()
        if (allResults.isEmpty()) return@coroutineScope null

        // Sort all routes from all engines by shortest distance first
        val sortedRoutes = allResults.sortedBy { it.distance }

        // Set shortest route as selected (index 0)
        val finalRoutes = sortedRoutes.mapIndexed { index, route ->
            route.copy(isSelected = (index == 0))
        }

        // Tích hợp Goong.io (Primary): Tải toàn bộ biển báo & camera dọc tuyến đường 1 LẦN duy nhất khi bắt đầu lộ trình
        val selectedRoute = finalRoutes.firstOrNull { it.isSelected } ?: finalRoutes.firstOrNull()
        if (selectedRoute != null && selectedRoute.polyline.isNotEmpty()) {
            TrafficWarningManager.fetchRouteWarningsFromGoong(context, selectedRoute.polyline)
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
            engine.contains("GraphHopper") -> fetchGraphHopperRoute(startLat, startLng, destLat, destLng, apiKey, vehicleType)
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
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        apiKey: String?,
        vehicleType: Int
    ): List<RouteInfo>? {
        val key = apiKey?.trim() ?: return null
        if (key.isEmpty()) return null
        
        val profile = "car"
        val url = "https://graphhopper.com/api/1/route?point=$startLat,$startLng&point=$destLat,$destLng&profile=$profile&locale=vi&key=$key&steps=true&points_encoded=true&algorithm=alternative_route"
        val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    android.util.Log.e("RoutingEngine", "GraphHopper Error: ${response.code}")
                    return null
                }
                parseGraphHopperResponse(body)
            }
        } catch (e: Exception) {
            null
        }
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
