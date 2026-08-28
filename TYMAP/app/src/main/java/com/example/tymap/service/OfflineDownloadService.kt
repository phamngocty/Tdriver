package com.example.tymap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.tymap.MainActivity
import com.example.tymap.R
import com.example.tymap.utils.PrefsHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class OfflineDownloadService : Service() {

    companion object {
        const val ACTION_START_DOWNLOAD = "ACTION_START_DOWNLOAD"
        const val ACTION_CONFIRM_MOBILE_DATA = "ACTION_CONFIRM_MOBILE_DATA"
        const val ACTION_CANCEL_DOWNLOAD = "ACTION_CANCEL_DOWNLOAD"
        
        const val BROADCAST_PROGRESS = "com.example.tymap.OFFLINE_PROGRESS"
        const val BROADCAST_NEED_CONFIRM = "com.example.tymap.OFFLINE_NEED_CONFIRM"
        const val BROADCAST_COMPLETE = "com.example.tymap.OFFLINE_COMPLETE"
        const val BROADCAST_ERROR = "com.example.tymap.OFFLINE_ERROR"

        // Helper to convert lat/lon to OSM tile coordinates
        fun getTileX(lon: Double, zoom: Int): Int = floor((lon + 180) / 360 * (1 shl zoom)).toInt()
        fun getTileY(lat: Double, zoom: Int): Int {
            val latRad = Math.toRadians(lat)
            return floor((1 - log(tan(latRad) + 1 / cos(latRad), Math.E) / Math.PI) / 2 * (1 shl zoom)).toInt()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val NOTIFICATION_ID = 99
    private val CHANNEL_ID = "OfflineDownloadChannel"

    private var isDownloading = false
    private var isPausedWaitingForNetworkConfirm = false
    private var totalTiles = 0
    private var downloadedTiles = 0
    private var downloadedBytes = 0L
    private var currentRegionId = ""
    private var currentRegionName = ""
    private var zoomLevels = intArrayOf()
    private var minLat = 0.0
    private var maxLat = 0.0
    private var minLon = 0.0
    private var maxLon = 0.0
    private var tileSourceIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                if (isDownloading) {
                    sendErrorBroadcast("Đang có tiến trình tải bản đồ khác chạy.")
                    return START_NOT_STICKY
                }
                
                currentRegionId = intent.getStringExtra("REGION_ID") ?: ""
                currentRegionName = intent.getStringExtra("REGION_NAME") ?: "Vùng bản đồ"
                zoomLevels = intent.getIntArrayExtra("ZOOMS") ?: intArrayOf()
                minLat = intent.getDoubleExtra("MIN_LAT", 0.0)
                maxLat = intent.getDoubleExtra("MAX_LAT", 0.0)
                minLon = intent.getDoubleExtra("MIN_LON", 0.0)
                maxLon = intent.getDoubleExtra("MAX_LON", 0.0)
                tileSourceIndex = intent.getIntExtra("TILE_SOURCE_INDEX", 0)

                startForeground(NOTIFICATION_ID, buildNotification("Đang chuẩn bị tải...", 0))
                startDownloadProcess()
            }
            ACTION_CONFIRM_MOBILE_DATA -> {
                if (isPausedWaitingForNetworkConfirm) {
                    isPausedWaitingForNetworkConfirm = false
                    startForeground(NOTIFICATION_ID, buildNotification("Đang tiếp tục tải...", 0))
                    resumeDownloadProcess()
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                stopDownload()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tải bản đồ ngoại tuyến",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tải bản đồ offline: $currentRegionName")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, progress == 0 && isDownloading)
            .build()
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startDownloadProcess() {
        isDownloading = true
        downloadedTiles = 0
        downloadedBytes = 0L
        
        if (!isNetworkConnected()) {
            sendErrorBroadcast("Không có kết nối Internet")
            stopDownload()
            return
        }

        if (!isWifiConnected()) {
            isPausedWaitingForNetworkConfirm = true
            sendBroadcast(Intent(BROADCAST_NEED_CONFIRM))
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification("Chờ xác nhận sử dụng dữ liệu di động...", 0))
            return
        }

        resumeDownloadProcess()
    }

    private fun resumeDownloadProcess() {
        serviceScope.launch {
            try {
                // Tính toán danh sách tile
                val tileList = mutableListOf<Triple<Int, Int, Int>>() // zoom, x, y
                for (z in zoomLevels) {
                    val xMin = getTileX(minLon, z)
                    val xMax = getTileX(maxLon, z)
                    val yMin = getTileY(maxLat, z) // Lat lớn hơn nằm trên (y nhỏ hơn)
                    val yMax = getTileY(minLat, z)

                    for (x in min(xMin, xMax)..max(xMin, xMax)) {
                        for (y in min(yMin, yMax)..max(yMin, yMax)) {
                            tileList.add(Triple(z, x, y))
                        }
                    }
                }

                totalTiles = tileList.size
                if (totalTiles == 0) {
                    sendErrorBroadcast("Vùng chọn không chứa mảnh bản đồ nào")
                    stopDownload()
                    return@launch
                }

                val offlineDir = File(getExternalFilesDir(null), "offline_maps/$currentRegionId")
                if (!offlineDir.exists()) offlineDir.mkdirs()

                // Lấy URL base của nguồn bản đồ
                val tileUrlTemplate = getTileUrlTemplate(tileSourceIndex)

                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                for (i in 0 until totalTiles) {
                    if (!isDownloading || isPausedWaitingForNetworkConfirm) break
                    
                    val (z, x, y) = tileList[i]
                    val tileFile = File(offlineDir, "$z/$x/$y.png")
                    if (tileFile.parentFile?.exists() == false) {
                        tileFile.parentFile?.mkdirs()
                    }

                    // Tải tile nếu chưa tồn tại cục bộ
                    if (!tileFile.exists() || tileFile.length() == 0L) {
                        val url = tileUrlTemplate.replace("{z}", z.toString())
                            .replace("{x}", x.toString())
                            .replace("{y}", y.toString())
                        
                        try {
                            downloadTile(url, tileFile)
                        } catch (e: Exception) {
                            // Bỏ qua lỗi và tiếp tục tải tile tiếp theo
                            android.util.Log.e("OfflineDownloadService", "Failed to download tile: $url", e)
                        }
                    } else {
                        downloadedTiles++
                        downloadedBytes += tileFile.length()
                    }

                    // Cập nhật tiến độ sau mỗi 5 tiles hoặc tile cuối
                    if (i % 5 == 0 || i == totalTiles - 1) {
                        val progress = ((downloadedTiles.toFloat() / totalTiles) * 100).toInt()
                        notificationManager.notify(NOTIFICATION_ID, buildNotification("Đã tải $downloadedTiles/$totalTiles tiles (${downloadedBytes / 1024 / 1024} MB)", progress))
                        sendProgressBroadcast(progress, downloadedTiles, totalTiles, downloadedBytes)
                    }
                }

                if (isDownloading && !isPausedWaitingForNetworkConfirm) {
                    saveRegionInfo()
                    sendBroadcast(Intent(BROADCAST_COMPLETE))
                    notificationManager.notify(NOTIFICATION_ID, buildNotification("Tải bản đồ hoàn tất! ($downloadedTiles tiles)", 100))
                    stopSelf()
                }

            } catch (e: Exception) {
                sendErrorBroadcast("Lỗi trong quá trình tải: ${e.message}")
                stopDownload()
            }
        }
    }

    private fun downloadTile(url: String, targetFile: File) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "TYMAP")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body
                if (body != null) {
                    val bytes = body.bytes()
                    FileOutputStream(targetFile).use { fos ->
                        fos.write(bytes)
                    }
                    downloadedTiles++
                    downloadedBytes += bytes.size
                }
            }
        }
    }

    private fun getTileUrlTemplate(index: Int): String {
        return when (index) {
            0 -> {
                // Google Maps Dark (MT)
                val encodedStyle = "s.t:1|s.e:g|p.c:#ff242f3e,s.t:1|s.e:l.t.f|p.c:#ff746855,s.t:1|s.e:l.t.s|p.c:#ff242f3e,s.t:3|s.e:g.f|p.c:#ff242f3e,s.t:3|s.e:l.t.f|p.c:#ff746855,s.t:4|s.e:g.f|p.c:#ff212a37,s.t:5|s.e:g.f|p.c:#ff38414e,s.t:5|s.e:g.s|p.c:#ff212a37,s.t:5|s.e:l.t.f|p.c:#ff9ca5b3,s.t:6|s.e:g.f|p.c:#ff746855,s.t:6|s.e:g.s|p.c:#ff242f3e,s.t:6|s.e:l.t.f|p.c:#ffd59563,s.t:81|s.e:g.f|p.c:#ff17263c,s.t:82|s.e:g.f|p.c:#ff1f2835,s.t:82|s.e:l.t.f|p.c:#ff515c6d,s.t:82|s.e:l.t.s|p.c:#ff1f2835"
                "https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}&apistyle=$encodedStyle"
            }
            1 -> {
                // Google Maps Standard (MT)
                "https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}"
            }
            2 -> {
                // Google Maps Satellite
                "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"
            }
            3 -> {
                // Google Maps Hybrid
                "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}"
            }
            4 -> {
                // OSM Mapnik
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
            }
            5 -> {
                // OSM HOT
                "https://tile.openstreetmap.fr/hot/{z}/{x}/{y}.png"
            }
            else -> {
                val customUrl = PrefsHelper.getString(this, "custom_tile_url", "")
                if (customUrl.isNotEmpty()) customUrl else "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
            }
        }
    }

    private fun saveRegionInfo() {
        val parentDir = File(getExternalFilesDir(null), "offline_maps")
        if (!parentDir.exists()) parentDir.mkdirs()
        val configFile = File(parentDir, "regions.json")
        
        val listType = object : TypeToken<MutableList<Map<String, Any>>>() {}.type
        val regionsList: MutableList<Map<String, Any>> = if (configFile.exists()) {
            try {
                gson.fromJson(configFile.readText(), listType)
            } catch (e: Exception) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }

        // Xóa thông tin vùng cũ nếu trùng ID
        regionsList.removeAll { it["id"] == currentRegionId }

        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val regionMap = mapOf(
            "id" to currentRegionId,
            "name" to currentRegionName,
            "minZoom" to (zoomLevels.minOrNull() ?: 14),
            "maxZoom" to (zoomLevels.maxOrNull() ?: 16),
            "boundingBox" to mapOf(
                "minLat" to minLat,
                "maxLat" to maxLat,
                "minLon" to minLon,
                "maxLon" to maxLon
            ),
            "tilesCount" to downloadedTiles,
            "sizeBytes" to downloadedBytes,
            "date" to format.format(Date()),
            "tileSourceIndex" to tileSourceIndex
        )

        regionsList.add(regionMap)
        configFile.writeText(gson.toJson(regionsList))
    }

    private fun sendProgressBroadcast(progress: Int, downloaded: Int, total: Int, bytes: Long) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra("PROGRESS", progress)
            putExtra("DOWNLOADED", downloaded)
            putExtra("TOTAL", total)
            putExtra("BYTES", bytes)
            putExtra("REGION_ID", currentRegionId)
        }
        sendBroadcast(intent)
    }

    private fun sendErrorBroadcast(message: String) {
        val intent = Intent(BROADCAST_ERROR).apply {
            putExtra("MESSAGE", message)
            putExtra("REGION_ID", currentRegionId)
        }
        sendBroadcast(intent)
    }

    private fun stopDownload() {
        isDownloading = false
        isPausedWaitingForNetworkConfirm = false
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
