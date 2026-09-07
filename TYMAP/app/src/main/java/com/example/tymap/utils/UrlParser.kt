package com.example.tymap.utils

import android.os.Bundle
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern

object UrlParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false) // Disable auto-redirects to intercept 302 Locations immediately
        .followSslRedirects(false)
        .build()

    fun extractUrlFromText(text: String): String? {
        val matcher = Pattern.compile("https?://\\S+").matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    fun resolveRedirect(shortUrl: String): String {
        var currentUrl = shortUrl
        var attempts = 0
        val maxAttempts = 3
        
        while (attempts < maxAttempts) {
            try {
                var stepUrl = currentUrl
                var stepCount = 0
                
                while (stepCount < 5) {
                    val request = Request.Builder()
                        .url(stepUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                        .build()
                    
                    client.newCall(request).execute().use { response ->
                        val code = response.code
                        if (code in 300..399) {
                            val loc = response.header("Location")
                            if (loc != null) {
                                stepUrl = loc
                                stepCount++
                                
                                // If the location header already contains definitive destination coordinates, return it immediately
                                val coordRegex = "([-+]?\\d+\\.\\d+)[,%2C]([-+]?\\d+\\.\\d+)"
                                val hasCoords = (stepUrl.contains("!3d") && stepUrl.contains("!4d")) ||
                                                stepUrl.contains("!2m2!1d") ||
                                                Pattern.compile("(?:destination|daddr|q=|query=)$coordRegex").matcher(stepUrl).find() ||
                                                (!stepUrl.contains("/dir/") && Pattern.compile("@$coordRegex").matcher(stepUrl).find())
                                if (hasCoords) {
                                    return stepUrl
                                }
                                continue
                            }
                        } else if (code == 200) {
                            // If code is 200, we must inspect the HTML body for maps links or staticmap centers
                            val bodyString = response.body?.string() ?: ""
                            
                            // Check staticmap center first
                            val centerMatcher = Pattern.compile("center=([-+]?\\d+\\.\\d+)%2C([-+]?\\d+\\.\\d+)").matcher(bodyString)
                            if (centerMatcher.find()) {
                                val lat = centerMatcher.group(1)
                                val lon = centerMatcher.group(2)
                                if (lat != null && lon != null) {
                                    return "$stepUrl#@$lat,$lon"
                                }
                            }
                            
                            // Check for full maps links
                            val htmlMatcher = Pattern.compile("https://(www\\.)?google\\.[a-z.]+/maps/\\S+").matcher(bodyString)
                            if (htmlMatcher.find()) {
                                var foundUrl = htmlMatcher.group()
                                if (foundUrl.endsWith("\"") || foundUrl.endsWith("'") || foundUrl.endsWith(">")) {
                                    foundUrl = foundUrl.substring(0, foundUrl.length - 1)
                                }
                                return URLDecoder.decode(foundUrl, "UTF-8")
                            }
                        }
                        return stepUrl
                    }
                }
                return stepUrl
            } catch (e: Exception) {
                attempts++
                android.util.Log.w("UrlParser", "Resolve attempt $attempts failed: ${e.message}. Retrying...")
                if (attempts < maxAttempts) {
                    try {
                        Thread.sleep(800)
                    } catch (ie: InterruptedException) {}
                } else {
                    android.util.Log.e("UrlParser", "All resolve attempts failed: ${e.message}", e)
                }
            }
        }
        return currentUrl
    }

    fun parseDmsCoordinates(text: String): Pair<Double, Double>? {
        // e.g. 10°46'23.4"N 106°41'45.0"E or 10°46'23.4 N, 106°41'45.0 E
        try {
            val dmsPattern = Pattern.compile(
                "(\\d+)[°\\s]+(\\d+)['\\s]+([\\d.]+)\"?\\s*([NSns])[,;\\s]+(\\d+)[°\\s]+(\\d+)['\\s]+([\\d.]+)\"?\\s*([EWew])"
            )
            val m = dmsPattern.matcher(text)
            if (m.find()) {
                val degLat = m.group(1)!!.toDouble()
                val minLat = m.group(2)!!.toDouble()
                val secLat = m.group(3)!!.toDouble()
                val dirLat = m.group(4)!!.uppercase()

                val degLon = m.group(5)!!.toDouble()
                val minLon = m.group(6)!!.toDouble()
                val secLon = m.group(7)!!.toDouble()
                val dirLon = m.group(8)!!.uppercase()

                var lat = degLat + (minLat / 60.0) + (secLat / 3600.0)
                if (dirLat == "S") lat = -lat

                var lon = degLon + (minLon / 60.0) + (secLon / 3600.0)
                if (dirLon == "W") lon = -lon

                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    return Pair(lat, lon)
                }
            }
        } catch (e: Exception) {}
        return null
    }

    fun extractDestinationFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        
        // 1. Vietnamese share: "Đến <Điểm đến> qua <Tên đường>."
        val vnMatcher = Pattern.compile("(?i)(?:Đến|toi|tới)\\s+(.+?)\\s+(?:qua|bằng|theo)\\s+").matcher(text)
        if (vnMatcher.find()) {
            val place = vnMatcher.group(1)?.trim()
            if (!place.isNullOrBlank() && !isCurrentLocationIndicator(place)) return place
        }

        // 2. English share: "To <Destination> via <Road>."
        val enMatcher = Pattern.compile("(?i)To\\s+(.+?)\\s+via\\s+").matcher(text)
        if (enMatcher.find()) {
            val place = enMatcher.group(1)?.trim()
            if (!place.isNullOrBlank() && !isCurrentLocationIndicator(place)) return place
        }

        // 3. Multiline text: take first non-URL line if it's a specific place
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) }
        for (line in lines) {
            if (isCurrentLocationIndicator(line) || line.startsWith("Đã ghim vị trí", ignoreCase = true)
                || line.startsWith("Vị trí đã thả ghim", ignoreCase = true)
                || line.startsWith("Dropped pin", ignoreCase = true)) {
                continue
            }
            if (line.length in 3..120) {
                return line
            }
        }
        return null
    }

    private fun isCurrentLocationIndicator(name: String?): Boolean {
        if (name == null || name.isBlank()) return true
        val lower = name.trim().lowercase()
        return lower in listOf(
            "vị trí của tôi", "vị trí của bạn", "vị trí hiện tại",
            "my location", "current location", "your location",
            "here", "vị trí này", "tại đây"
        )
    }

    fun parseCoordinates(url: String, rawSharedText: String? = null): Bundle? {
        val coordRegex = "([-+]?\\d+\\.\\d+)[,%2C]([-+]?\\d+\\.\\d+)"
        
        // Auto-assign vehicle travel mode if detected in URL
        val vehicleType = when {
            url.contains("!3e9") || url.contains("travelmode=two-wheeler") || url.contains("travelmode=motorcycle") -> 1 // Motorcycle
            url.contains("!3e0") || url.contains("travelmode=driving") -> 0 // Car
            else -> -1
        }

        fun Bundle.withVehicle(): Bundle {
            if (vehicleType != -1) putInt("VEHICLE_TYPE", vehicleType)
            return this
        }

        val textDest = extractDestinationFromText(rawSharedText)

        // 1. Highest Priority: Explicit destination parameter in URL (?destination=... or &destination=... or &daddr=...)
        val destParamMatcher = Pattern.compile("[?&](?:destination|daddr)=([^&]+)").matcher(url)
        if (destParamMatcher.find()) {
            val rawDest = URLDecoder.decode(destParamMatcher.group(1)!!.replace("+", " "), "UTF-8").trim()
            val coordM = Pattern.compile("^$coordRegex$").matcher(rawDest)
            if (coordM.matches()) {
                val lat = coordM.group(1)!!.toDouble()
                val lon = coordM.group(2)!!.toDouble()
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    return Bundle().apply {
                        putString("SHARE_TYPE", "POI")
                        putDouble("DEST_LAT", lat)
                        putDouble("DEST_LON", lon)
                        putString("LABEL", textDest ?: "Điểm đến từ Google Maps")
                    }.withVehicle()
                }
            } else if (!isCurrentLocationIndicator(rawDest)) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "DIR_NAME")
                    putString("DEST_NAME", rawDest)
                    putString("LABEL", rawDest)
                }.withVehicle()
            }
        }

        // 2. Directions URL (/dir/...)
        if (url.contains("/dir/")) {
            // A. Extract waypoints in data=!4m... parameter
            // Google Maps encodes waypoints as !2m2!1d<lon>!2d<lat>
            // Note: In Google Maps !2m2, 1d is LONGITUDE and 2d is LATITUDE!
            val waypointMatcher = Pattern.compile("!2m2!1d([-+]?\\d+\\.\\d+)!2d([-+]?\\d+\\.\\d+)").matcher(url)
            val waypoints = mutableListOf<Pair<Double, Double>>() // (lat, lon)
            while (waypointMatcher.find()) {
                val lon = waypointMatcher.group(1)!!.toDouble()
                val lat = waypointMatcher.group(2)!!.toDouble()
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    waypoints.add(Pair(lat, lon))
                }
            }

            // B. Also check for !3d<lat>!4d<lon>
            val data3d4dMatcher = Pattern.compile("!3d([-+]?\\d+\\.\\d+)!4d([-+]?\\d+\\.\\d+)").matcher(url)
            val points3d4d = mutableListOf<Pair<Double, Double>>()
            while (data3d4dMatcher.find()) {
                val lat = data3d4dMatcher.group(1)!!.toDouble()
                val lon = data3d4dMatcher.group(2)!!.toDouble()
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    points3d4d.add(Pair(lat, lon))
                }
            }

            // C. Extract path segments after /dir/
            // e.g. /dir/Origin/Destination/@... or /dir//Destination/@...
            val dirPathPattern = Pattern.compile("/dir/(.*?)(?:/@|/data=|\\?|#|$)")
            val dirPathMatcher = dirPathPattern.matcher(url)
            var originSegment: String? = null
            var destSegment: String? = null

            if (dirPathMatcher.find()) {
                val pathContent = dirPathMatcher.group(1) ?: ""
                val segments = pathContent.split("/").map { URLDecoder.decode(it.replace("+", " "), "UTF-8").trim() }
                if (segments.size >= 2) {
                    originSegment = segments[0]
                    destSegment = segments.last { it.isNotEmpty() }
                } else if (segments.size == 1 && segments[0].isNotEmpty()) {
                    destSegment = segments[0]
                }
            }

            val finalDestLabel = when {
                !destSegment.isNullOrBlank() && !isCurrentLocationIndicator(destSegment) -> destSegment
                !textDest.isNullOrBlank() -> textDest
                else -> "Điểm đến từ Google Maps"
            }

            // Waypoints found: Point 0 is Origin (User current location), Last Point is Destination!
            if (waypoints.isNotEmpty()) {
                val destCoord = waypoints.last()
                val originCoord = if (waypoints.size >= 2) waypoints.first() else null

                return Bundle().apply {
                    if (originCoord != null && !isCurrentLocationIndicator(originSegment)) {
                        putString("SHARE_TYPE", "ROUTE")
                        putDouble("ORIGIN_LAT", originCoord.first)
                        putDouble("ORIGIN_LON", originCoord.second)
                    } else {
                        // Origin is user's current GPS location -> pin destination as POI
                        putString("SHARE_TYPE", "POI")
                    }
                    putDouble("DEST_LAT", destCoord.first)
                    putDouble("DEST_LON", destCoord.second)
                    putString("LABEL", finalDestLabel)
                }.withVehicle()
            }

            // 3d/4d points found: last point is destination
            if (points3d4d.isNotEmpty()) {
                val destCoord = points3d4d.last()
                return Bundle().apply {
                    putString("SHARE_TYPE", "POI")
                    putDouble("DEST_LAT", destCoord.first)
                    putDouble("DEST_LON", destCoord.second)
                    putString("LABEL", finalDestLabel)
                }.withVehicle()
            }

            // Destination segment contains raw coordinates (e.g. /dir/.../10.7725,106.6958/)
            if (!destSegment.isNullOrBlank()) {
                val coordM = Pattern.compile("^$coordRegex$").matcher(destSegment)
                if (coordM.matches()) {
                    val lat = coordM.group(1)!!.toDouble()
                    val lon = coordM.group(2)!!.toDouble()
                    if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                        return Bundle().apply {
                            putString("SHARE_TYPE", "POI")
                            putDouble("DEST_LAT", lat)
                            putDouble("DEST_LON", lon)
                            putString("LABEL", "Tọa độ đã ghim")
                        }.withVehicle()
                    }
                }
            }

            // Destination segment has place name that needs geocoding
            if (!destSegment.isNullOrBlank() && !isCurrentLocationIndicator(destSegment)) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "DIR_NAME")
                    putString("DEST_NAME", destSegment)
                    putString("LABEL", destSegment)
                }.withVehicle()
            }

            if (!textDest.isNullOrBlank() && !isCurrentLocationIndicator(textDest)) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "DIR_NAME")
                    putString("DEST_NAME", textDest)
                    putString("LABEL", textDest)
                }.withVehicle()
            }

            // CRITICAL: NEVER fallback to @lat,lon in /dir/ URLs because @ is the camera center, which is near origin/current location!
        }

        // 3. Place URLs (/place/...)
        if (url.contains("/place/")) {
            val placeNameMatcher = Pattern.compile("/place/([^/@?#]+)").matcher(url)
            val placeName = if (placeNameMatcher.find()) {
                URLDecoder.decode(placeNameMatcher.group(1)!!.replace("+", " "), "UTF-8").trim()
            } else null

            // Exact destination pin coordinates from !3d<lat>!4d<lon>
            val dataMatcher = Pattern.compile("!3d([-+]?\\d+\\.\\d+)!4d([-+]?\\d+\\.\\d+)").matcher(url)
            if (dataMatcher.find()) {
                val lat = dataMatcher.group(1)!!.toDouble()
                val lon = dataMatcher.group(2)!!.toDouble()
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    return Bundle().apply {
                        putString("SHARE_TYPE", "POI")
                        putDouble("DEST_LAT", lat)
                        putDouble("DEST_LON", lon)
                        putString("LABEL", placeName ?: (textDest ?: "Điểm từ Google Maps"))
                    }.withVehicle()
                }
            }

            // Check if placeName is coordinates (decimal or DMS)
            if (!placeName.isNullOrBlank()) {
                val coordM = Pattern.compile("^$coordRegex$").matcher(placeName)
                if (coordM.matches()) {
                    val lat = coordM.group(1)!!.toDouble()
                    val lon = coordM.group(2)!!.toDouble()
                    return Bundle().apply {
                        putString("SHARE_TYPE", "POI")
                        putDouble("DEST_LAT", lat)
                        putDouble("DEST_LON", lon)
                        putString("LABEL", "Tọa độ đã ghim")
                    }.withVehicle()
                }
                val dms = parseDmsCoordinates(placeName)
                if (dms != null) {
                    return Bundle().apply {
                        putString("SHARE_TYPE", "POI")
                        putDouble("DEST_LAT", dms.first)
                        putDouble("DEST_LON", dms.second)
                        putString("LABEL", "Tọa độ đã ghim")
                    }.withVehicle()
                }
                if (!isCurrentLocationIndicator(placeName)) {
                    return Bundle().apply {
                        putString("SHARE_TYPE", "DIR_NAME")
                        putString("DEST_NAME", placeName)
                        putString("LABEL", placeName)
                    }.withVehicle()
                }
            }
        }

        // 4. Query coordinates (q= or query=)
        val queryMatcher = Pattern.compile("[?&](?:q|query|loc)=([^&]+)").matcher(url)
        if (queryMatcher.find()) {
            val rawQuery = URLDecoder.decode(queryMatcher.group(1)!!.replace("+", " "), "UTF-8").trim()
            val stripped = rawQuery.removePrefix("loc:").trim()
            val coordM = Pattern.compile("^$coordRegex$").matcher(stripped)
            if (coordM.matches()) {
                val lat = coordM.group(1)!!.toDouble()
                val lon = coordM.group(2)!!.toDouble()
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    return Bundle().apply {
                        putString("SHARE_TYPE", "POI")
                        putDouble("DEST_LAT", lat)
                        putDouble("DEST_LON", lon)
                        putString("LABEL", textDest ?: "Vị trí đã chọn")
                    }.withVehicle()
                }
            }
            val dms = parseDmsCoordinates(stripped)
            if (dms != null) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "POI")
                    putDouble("DEST_LAT", dms.first)
                    putDouble("DEST_LON", dms.second)
                    putString("LABEL", textDest ?: "Tọa độ đã ghim")
                }.withVehicle()
            }
            if (!isCurrentLocationIndicator(stripped)) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "DIR_NAME")
                    putString("DEST_NAME", stripped)
                    putString("LABEL", stripped)
                }.withVehicle()
            }
        }

        // 5. Check DMS in raw shared text
        if (!rawSharedText.isNullOrBlank()) {
            val dms = parseDmsCoordinates(rawSharedText)
            if (dms != null) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "POI")
                    putDouble("DEST_LAT", dms.first)
                    putDouble("DEST_LON", dms.second)
                    putString("LABEL", textDest ?: "Tọa độ đã ghim")
                }.withVehicle()
            }
        }

        // 6. Generic !3d<lat>!4d<lon> anywhere in the URL (taking the last match)
        val gen3d4dMatcher = Pattern.compile("!3d([-+]?\\d+\\.\\d+)!4d([-+]?\\d+\\.\\d+)").matcher(url)
        var last3d4d: Pair<Double, Double>? = null
        while (gen3d4dMatcher.find()) {
            val lat = gen3d4dMatcher.group(1)!!.toDouble()
            val lon = gen3d4dMatcher.group(2)!!.toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                last3d4d = Pair(lat, lon)
            }
        }
        if (last3d4d != null) {
            return Bundle().apply {
                putString("SHARE_TYPE", "POI")
                putDouble("DEST_LAT", last3d4d.first)
                putDouble("DEST_LON", last3d4d.second)
                putString("LABEL", textDest ?: "Điểm từ Google Maps")
            }.withVehicle()
        }

        // 7. Fallback to raw text destination name
        if (!textDest.isNullOrBlank() && !isCurrentLocationIndicator(textDest)) {
            return Bundle().apply {
                putString("SHARE_TYPE", "DIR_NAME")
                putString("DEST_NAME", textDest)
                putString("LABEL", textDest)
            }.withVehicle()
        }

        // 8. Absolute last resort for non-route URLs: @lat,lon
        if (!url.contains("/dir/")) {
            val atMatcher = Pattern.compile("@$coordRegex").matcher(url)
            if (atMatcher.find()) {
                val lat = atMatcher.group(1)!!.toDouble()
                val lon = atMatcher.group(2)!!.toDouble()
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    return Bundle().apply {
                        putString("SHARE_TYPE", "POI")
                        putDouble("DEST_LAT", lat)
                        putDouble("DEST_LON", lon)
                        putString("LABEL", "Vị trí bản đồ")
                    }.withVehicle()
                }
            }
        }

        return null
    }

    fun cleanPlaceName(name: String): String {
        // Remove text in parentheses, e.g., "Lẩu Dê 135 (2)" -> "Lẩu Dê 135 "
        var cleaned = name.replace(Regex("\\([^)]*\\)"), " ")
        // Replace commas, dots, dashes, and slashes with spaces to avoid ElasticSearch query parser errors
        cleaned = cleaned.replace(Regex("[,.\\-_/]"), " ")
        // Normalize whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return cleaned
    }

    fun geocodeWithName(placeName: String): Bundle? {
        if (placeName.length <= 2) return null
        
        val cleanedName = cleanPlaceName(placeName)
        if (cleanedName.length <= 2) return null
        
        try {
            val geocodeUrl = "https://photon.komoot.io/api/?q=${URLEncoder.encode(cleanedName, "UTF-8")}&limit=1"
            val response = client.newCall(Request.Builder().url(geocodeUrl).header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)").build()).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body!!.string())
                val features = json.getJSONArray("features")
                if (features.length() > 0) {
                    val coords = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                    return Bundle().apply {
                        putString("SHARE_TYPE", "POI")
                        putDouble("DEST_LAT", coords.getDouble(1))
                        putDouble("DEST_LON", coords.getDouble(0))
                        putString("LABEL", placeName)
                    }
                }
            }
        } catch (e: Exception) {}

        // Fallback to Nominatim
        try {
            val nominatimUrl = "https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(cleanedName, "UTF-8")}&format=json&limit=1"
            val request = Request.Builder().url(nominatimUrl).header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val array = JSONArray(response.body!!.string())
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    return Bundle().apply {
                        putString("SHARE_TYPE", "POI")
                        putDouble("DEST_LAT", obj.getDouble("lat"))
                        putDouble("DEST_LON", obj.getDouble("lon"))
                        putString("LABEL", placeName)
                    }
                }
            }
        } catch (e: Exception) {}
        
        return null
    }
}
