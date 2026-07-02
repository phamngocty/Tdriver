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
            resolveAndExtract(url)
        } else {
            finish()
        }
    }

    private fun resolveAndExtract(shortUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalUrl = UrlParser.resolveRedirect(shortUrl)
                Log.d("TYMAP_DEBUG", "Final URL: $finalUrl")

                var data = UrlParser.parseCoordinates(finalUrl)

                if (data != null && data.getString("SHARE_TYPE") == "DIR_NAME") {
                    val destName = data.getString("DEST_NAME") ?: ""
                    data = UrlParser.geocodeWithName(destName)
                }

                if (data == null) {
                    val namePatterns = listOf("/place/([^/]+)/", "q=([^&]+)", "search/([^/\\?]+)", "/dir/[^/]+/([^/\\?#]+)")
                    var placeName: String? = null
                    for (p in namePatterns) {
                        val m = java.util.regex.Pattern.compile(p).matcher(finalUrl)
                        if (m.find()) {
                            placeName = URLDecoder.decode(m.group(m.groupCount())!!.replace("+", " "), "UTF-8")
                            break
                        }
                    }
                    if (placeName != null && placeName.lowercase() != "vị trí của tôi" && placeName.lowercase() != "my location") {
                        // Check if placeName is actually a raw lat,lng string (e.g. "10.7725,106.6958")
                        // If so, parse it directly offline instead of sending to online geocoding (which fails/gets wrong results)
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

                withContext(Dispatchers.Main) {
                    if (data != null) {
                        val mainIntent = Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                            putExtras(data)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(mainIntent)
                    } else {
                        Toast.makeText(this@ShareReceiverActivity, "Không thể xác định vị trí. Thử lại sau.", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            } catch (e: Exception) {
                Log.e("TYMAP_DEBUG", "Error: ${e.message}")
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }
}
