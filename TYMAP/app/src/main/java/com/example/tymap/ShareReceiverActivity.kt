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

                if (data == null) {
                    val namePatterns = listOf("/place/([^/]+)/", "q=([^&]+)", "search/([^/\\?]+)")
                    var placeName: String? = null
                    for (p in namePatterns) {
                        val m = java.util.regex.Pattern.compile(p).matcher(finalUrl)
                        if (m.find()) {
                            placeName = URLDecoder.decode(m.group(1)!!.replace("+", " "), "UTF-8")
                            break
                        }
                    }
                    if (placeName != null) {
                        data = UrlParser.geocodeWithName(placeName)
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
