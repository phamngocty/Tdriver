package com.example.tymap

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tymap.utils.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val url = UrlParser.extractUrlFromText(text)
        
        if (url != null) {
            resolveAndExtract(url, text)
        } else if (text.isNotBlank()) {
            // Text without HTTP URL (e.g. DMS coords or direct address)
            lifecycleScope.launch(Dispatchers.IO) {
                var data = UrlParser.parseCoordinates("", text)
                if (data == null) {
                    val destName = UrlParser.extractDestinationFromText(text)
                    if (destName != null) {
                        data = UrlParser.geocodeWithName(destName)
                    }
                }
                deliverResult(data)
            }
        } else {
            finish()
        }
    }

    private fun resolveAndExtract(shortUrl: String, rawText: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalUrl = UrlParser.resolveRedirect(shortUrl)
                Log.d("TYMAP_DEBUG", "Final URL: $finalUrl")

                var data = UrlParser.parseCoordinates(finalUrl, rawText)

                if (data != null && data.getString("SHARE_TYPE") == "DIR_NAME") {
                    val destName = data.getString("DEST_NAME") ?: ""
                    val geocoded = UrlParser.geocodeWithName(destName)
                    if (geocoded != null) {
                        if (data.containsKey("VEHICLE_TYPE")) {
                            geocoded.putInt("VEHICLE_TYPE", data.getInt("VEHICLE_TYPE"))
                        }
                        data = geocoded
                    }
                }

                if (data == null) {
                    val namePatterns = listOf(
                        "/place/([^/]+)/", "q=([^&]+)", "search/([^/\\?]+)",
                        "/dir/[^/]+/([^/\\?#]+)", "[?&]destination=([^&]+)", "[?&]daddr=([^&]+)"
                    )
                    var placeName: String? = null
                    for (p in namePatterns) {
                        val m = java.util.regex.Pattern.compile(p).matcher(finalUrl)
                        if (m.find()) {
                            placeName = URLDecoder.decode(m.group(m.groupCount())!!.replace("+", " "), "UTF-8")
                            break
                        }
                    }
                    if (placeName.isNullOrBlank()) {
                        placeName = UrlParser.extractDestinationFromText(rawText)
                    }

                    if (!placeName.isNullOrBlank() && placeName.lowercase() !in listOf("vị trí của tôi", "my location", "vị trí của bạn")) {
                        // Check if placeName is actually a raw lat,lng string (e.g. "10.7725,106.6958")
                        val coordPattern = java.util.regex.Pattern.compile("^[-+]?\\d+\\.\\d+[,%2C\\s]+[-+]?\\d+\\.\\d+$")
                        if (coordPattern.matcher(placeName.trim()).matches()) {
                            val parts = placeName.trim().split(Regex("[,%2C\\s]+"))
                            if (parts.size >= 2) {
                                val lat = parts[0].toDoubleOrNull()
                                val lon = parts[1].toDoubleOrNull()
                                if (lat != null && lon != null) {
                                    data = Bundle().apply {
                                        putString("SHARE_TYPE", "POI")
                                        putDouble("DEST_LAT", lat)
                                        putDouble("DEST_LON", lon)
                                        putString("LABEL", "Tọa độ đã ghim")
                                    }
                                }
                            }
                        } else {
                            data = UrlParser.geocodeWithName(placeName)
                        }
                    }
                }

                deliverResult(data)
            } catch (e: Exception) {
                Log.e("TYMAP_DEBUG", "Error: ${e.message}")
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    private suspend fun deliverResult(data: Bundle?) {
        withContext(Dispatchers.Main) {
            if (data != null) {
                // Post directly to NavigationRepository so active MapFragment gets it immediately
                com.example.tymap.repository.NavigationRepository.postSharedLocation(data)

                val mainIntent = Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                    putExtras(data)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(mainIntent)
            } else {
                Toast.makeText(this@ShareReceiverActivity, "Không thể xác định vị trí điểm đến. Thử lại sau.", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
