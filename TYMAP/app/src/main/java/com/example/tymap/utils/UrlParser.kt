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
                                
                                // If the location header already contains coordinates, return it immediately
                                val coordRegex = "([-+]?\\d+\\.\\d+)[,%2C]([-+]?\\d+\\.\\d+)"
                                val hasCoords = stepUrl.contains("!3d") && stepUrl.contains("!4d") || 
                                                Pattern.compile("(@|q=|query=)$coordRegex").matcher(stepUrl).find()
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

    fun parseCoordinates(url: String): Bundle? {
        val coordRegex = "([-+]?\\d+\\.\\d+)[,%2C]([-+]?\\d+\\.\\d+)"
        val googleDataRegex = "!3d([-+]?\\d+\\.\\d+)!4d([-+]?\\d+\\.\\d+)"
        
        // 1. Highest Priority for Routes: Check if URL is a route but contains destination coordinate metadata (!3d...!4d...)
        if (url.contains("/dir/")) {
            val dataMatcher = Pattern.compile(googleDataRegex).matcher(url)
            if (dataMatcher.find()) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "POI")
                    putDouble("DEST_LAT", dataMatcher.group(1)!!.toDouble())
                    putDouble("DEST_LON", dataMatcher.group(2)!!.toDouble())
                    putString("LABEL", "Điểm đến từ Google Maps")
                }
            }
        }

        // 2. Highest Priority for POIs: Extract exact location coordinates from POI data (!3d...!4d...)
        val dataMatcher = Pattern.compile(googleDataRegex).matcher(url)
        if (dataMatcher.find()) {
            return Bundle().apply {
                putString("SHARE_TYPE", "POI")
                putDouble("DEST_LAT", dataMatcher.group(1)!!.toDouble())
                putDouble("DEST_LON", dataMatcher.group(2)!!.toDouble())
                putString("LABEL", "Điểm từ Google Maps")
            }
        }

        // 3. Second Priority: Extract coordinates from @lat,lon or q=lat,lon
        val poiMatcher = Pattern.compile("(@|q=|query=)$coordRegex").matcher(url)
        if (poiMatcher.find()) {
            return Bundle().apply {
                putString("SHARE_TYPE", "POI")
                putDouble("DEST_LAT", poiMatcher.group(2)!!.toDouble())
                putDouble("DEST_LON", poiMatcher.group(3)!!.toDouble())
                putString("LABEL", "Vị trí đã chọn")
            }
        }

        // 4. Third Priority: Extract any generic valid coordinates sequence in the URL
        val genericMatcher = Pattern.compile(coordRegex).matcher(url)
        if (genericMatcher.find()) {
            val lat = genericMatcher.group(1)!!.toDouble()
            val lon = genericMatcher.group(2)!!.toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "POI")
                    putDouble("DEST_LAT", lat)
                    putDouble("DEST_LON", lon)
                    putString("LABEL", "Tọa độ từ liên kết")
                }
            }
        }

        // 5. Fourth Priority for Routes: If no coordinates could be parsed, check if it's a route with names
        if (url.contains("/dir/")) {
            // Check if matches route coords
            val matcher = Pattern.compile(coordRegex).matcher(url)
            val matches = mutableListOf<Pair<Double, Double>>()
            while (matcher.find()) {
                matches.add(Pair(matcher.group(1)!!.toDouble(), matcher.group(2)!!.toDouble()))
            }
            if (matches.size >= 2) {
                return Bundle().apply {
                    putString("SHARE_TYPE", "ROUTE")
                    putDouble("ORIGIN_LAT", matches[0].first)
                    putDouble("ORIGIN_LON", matches[0].second)
                    putDouble("DEST_LAT", matches.last().first)
                    putDouble("DEST_LON", matches.last().second)
                    putString("LABEL", "Lộ trình Google Maps")
                }
            } else {
                // Parse destination place name for geocoding fallback
                val dirPattern = Pattern.compile("/dir/([^/]+)/([^/\\?#]+)")
                val dirMatcher = dirPattern.matcher(url)
                if (dirMatcher.find()) {
                    val destNameEncoded = dirMatcher.group(2)
                    if (destNameEncoded != null) {
                        try {
                            val destName = URLDecoder.decode(destNameEncoded.replace("+", " "), "UTF-8")
                            if (destName.isNotEmpty() && destName.lowercase() != "vị trí của tôi" && destName.lowercase() != "my location") {
                                return Bundle().apply {
                                    putString("SHARE_TYPE", "DIR_NAME")
                                    putString("DEST_NAME", destName)
                                    putString("LABEL", destName)
                                }
                            }
                        } catch (e: Exception) {}
                    }
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
            val response = client.newCall(Request.Builder().url(geocodeUrl).build()).execute()
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
            val request = Request.Builder().url(nominatimUrl).header("User-Agent", "TYMAP").build()
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
