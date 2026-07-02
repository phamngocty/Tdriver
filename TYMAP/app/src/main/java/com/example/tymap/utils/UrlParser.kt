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
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun extractUrlFromText(text: String): String? {
        val matcher = Pattern.compile("https?://\\S+").matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    fun resolveRedirect(shortUrl: String): String {
        var currentUrl = shortUrl
        var redirects = 0
        while (redirects < 5) {
            try {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code in 300..399) {
                        val loc = response.header("Location")
                        if (loc != null) {
                            currentUrl = loc
                            redirects++
                            continue
                        }
                    }
                    return response.request.url.toString()
                }
            } catch (e: Exception) {
                break
            }
        }
        return currentUrl
    }

    fun parseCoordinates(url: String): Bundle? {
        val coordRegex = "([-+]?\\d+\\.\\d+)[,%2C]([-+]?\\d+\\.\\d+)"
        val googleDataRegex = "!3d([-+]?\\d+\\.\\d+)!4d([-+]?\\d+\\.\\d+)"
        
        // 1. Route /dir/
        if (url.contains("/dir/")) {
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
            }
        }

        // 2. POI !3d...!4d...
        val dataMatcher = Pattern.compile(googleDataRegex).matcher(url)
        if (dataMatcher.find()) {
            return Bundle().apply {
                putString("SHARE_TYPE", "POI")
                putDouble("DEST_LAT", dataMatcher.group(1)!!.toDouble())
                putDouble("DEST_LON", dataMatcher.group(2)!!.toDouble())
                putString("LABEL", "Điểm từ Google Maps")
            }
        }

        // 3. POI @lat,lng hoặc q=lat,lng
        val poiMatcher = Pattern.compile("(@|q=)$coordRegex").matcher(url)
        if (poiMatcher.find()) {
            return Bundle().apply {
                putString("SHARE_TYPE", "POI")
                putDouble("DEST_LAT", poiMatcher.group(2)!!.toDouble())
                putDouble("DEST_LON", poiMatcher.group(3)!!.toDouble())
            }
        }
        
        return null
    }

    fun geocodeWithName(placeName: String): Bundle? {
        if (placeName.length <= 2) return null
        
        // Try Photon first
        try {
            val geocodeUrl = "https://photon.komoot.io/api/?q=${URLEncoder.encode(placeName, "UTF-8")}&limit=1"
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
            val nominatimUrl = "https://nominatim.openstreetmap.org/search?q=${URLEncoder.encode(placeName, "UTF-8")}&format=json&limit=1"
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
