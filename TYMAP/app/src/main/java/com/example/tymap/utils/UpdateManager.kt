package com.example.tymap.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.tymap.repository.NavigationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class UpdateInfo(
    val hasAppUpdate: Boolean,
    val appVersionCode: Int,
    val appVersionName: String,
    val appApkUrl: String,
    val appChangelog: String,
    val hasFirmwareUpdate: Boolean,
    val firmwareVersionCode: Int,
    val firmwareVersionName: String,
    val firmwareBinUrl: String,
    val firmwareChangelog: String
)

sealed class UpdateCheckResult {
    data class Success(val info: UpdateInfo) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object UpdateManager {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Danh sách máy chủ cập nhật theo thứ tự ưu tiên:
     * 1. Gitea NAS (Public DuckDNS qua Nginx Proxy Manager SSL)
     * 2. Gitea NAS (Mạng nội bộ LAN)
     * 3. Fusion Engine NAS (Public DuckDNS)
     * 4. GitHub Cloud (Dự phòng toàn cầu)
     */
    fun getDefaultUpdateUrls(context: Context): List<String> {
        val customUrl = PrefsHelper.getString(context, "github_update_url", "").trim()
        val list = mutableListOf<String>()
        if (customUrl.isNotEmpty()) {
            list.add(customUrl)
        }
        // 1. Máy chủ NAS Fusion Engine (Public DuckDNS có SSL Let's Encrypt hợp lệ, hoạt động 24/7 trên cả WiFi & 4G)
        list.add("https://alert.nas152.duckdns.org/version.json")
        // 2. Gitea NAS (Mạng nội bộ LAN)
        list.add("http://192.168.1.114:3002/nas152/Tdriver/raw/branch/master/version.json")
        list.add("http://192.168.1.114:3002/nas152/Tdriver/raw/branch/main/version.json")
        // 3. Gitea NAS (Public DuckDNS)
        list.add("https://git.nas152.duckdns.org/nas152/Tdriver/raw/branch/master/version.json")
        list.add("https://git.nas152.duckdns.org/nas152/Tdriver/raw/branch/main/version.json")
        // 4. Dự phòng repo TYMAP
        list.add("https://git.nas152.duckdns.org/nas152/TYMAP/raw/branch/main/version.json")
        list.add("http://192.168.1.114:3002/nas152/TYMAP/raw/branch/main/version.json")
        // 5. GitHub Cloud (Dự phòng toàn cầu)
        list.add("https://raw.githubusercontent.com/phamn/TYMAP/main/version.json")
        return list
    }

    /**
     * Tự động kiểm tra bản cập nhật App APK và Firmware ESP32 từ Gitea NAS / Fusion Engine / GitHub.
     */
    suspend fun checkUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val urls = getDefaultUpdateUrls(context)
        var lastError = "Không thể kết nối đến máy chủ cập nhật."
        var fallbackInfo: UpdateInfo? = null

        for (updateUrl in urls) {
            try {
                val request = Request.Builder()
                    .url(updateUrl)
                    .header("User-Agent", "TYMAP-Updater/2.0")
                    .build()

                clientNewCall(request).use { response ->
                    if (!response.isSuccessful) {
                        lastError = "Máy chủ $updateUrl phản hồi mã lỗi HTTP ${response.code}"
                        return@use
                    }
                    val bodyStr = response.body?.string() ?: return@use
                    val json = try {
                        JSONObject(bodyStr)
                    } catch (e: Exception) {
                        lastError = "Lỗi phân tích cú pháp JSON từ $updateUrl"
                        return@use
                    }

                    val appObj = json.optJSONObject("app") ?: JSONObject()
                    val appVerCode = appObj.optInt("versionCode", 0)
                    val appVerName = appObj.optString("versionName", "")
                    var appApkUrl = appObj.optString("apkUrl", "")
                    val appChangelog = appObj.optString("changelog", "Bản cập nhật mới cho TYMAP.")

                    val fwObj = json.optJSONObject("firmware") ?: JSONObject()
                    var fwVerCode = fwObj.optInt("versionCode", 0)
                    var fwVerName = fwObj.optString("versionName", "")
                    var fwBinUrl = fwObj.optString("binUrl", "")
                    var fwChangelog = fwObj.optString("changelog", "Bản nâng cấp firmware mới cho ESP32 HUD.")

                    // Bỏ qua nếu là dữ liệu test không hợp lệ (cả app lẫn fw versionCode đều <= 0)
                    if (appVerCode <= 0 && fwVerCode <= 0) {
                        NavigationRepository.addLog("UpdateManager: Bỏ qua $updateUrl (dữ liệu version.json không hợp lệ)")
                        return@use
                    }

                    // Tự động nhận diện thiết bị đang kết nối (GC9A01 hay OLED SH1106 / SSD1306)
                    val deviceDisplay = NavigationRepository.deviceStatus.value["display"]
                        ?: PrefsHelper.getString(context, "connected_device_display", "GC9A01")
                    val isOled = deviceDisplay.contains("OLED", ignoreCase = true) ||
                                 deviceDisplay.contains("SH1106", ignoreCase = true) ||
                                 deviceDisplay.contains("SSD1306", ignoreCase = true)

                    if (isOled) {
                        val fwOledObj = json.optJSONObject("firmware_oled")
                        val oledUrl = fwOledObj?.optString("binUrl", "") ?: fwObj.optString("oledBinUrl", "")
                        if (oledUrl.isNotEmpty()) {
                            fwBinUrl = oledUrl
                            if (fwOledObj != null) {
                                val oledVerCode = fwOledObj.optInt("versionCode", 0)
                                val oledVerName = fwOledObj.optString("versionName", "")
                                if (oledVerCode > 0) fwVerCode = oledVerCode
                                if (oledVerName.isNotEmpty()) fwVerName = oledVerName
                                val oledChangelog = fwOledObj.optString("changelog", "")
                                if (oledChangelog.isNotEmpty()) fwChangelog = oledChangelog
                            }
                        }
                    }

                    // Resolve relative URLs based on the active server URL
                    val baseUrl = updateUrl.substringBeforeLast("/")
                    if (appApkUrl.startsWith("./") || (!appApkUrl.startsWith("http://") && !appApkUrl.startsWith("https://") && appApkUrl.isNotEmpty())) {
                        appApkUrl = "$baseUrl/${appApkUrl.removePrefix("./")}"
                    }
                    if (fwBinUrl.startsWith("./") || (!fwBinUrl.startsWith("http://") && !fwBinUrl.startsWith("https://") && fwBinUrl.isNotEmpty())) {
                        fwBinUrl = "$baseUrl/${fwBinUrl.removePrefix("./")}"
                    }

                    val currentAppVerName = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
                    } catch (e: Exception) { "1.0.0" }

                    val currentAppVerCode = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                        }
                    } catch (e: Exception) { 1 }

                    val hasAppUpdate = (appVerCode > currentAppVerCode || isVersionHigher(appVerName, currentAppVerName)) && appApkUrl.isNotEmpty()

                    val currentFwVerCode = PrefsHelper.getInt(context, "esp32_fw_version_code", 0)
                    val currentFwVerName = PrefsHelper.getString(context, "esp32_fw_version_name", "1.0.0")
                    val hasFirmwareUpdate = (fwVerCode > currentFwVerCode || isVersionHigher(fwVerName, currentFwVerName)) && fwBinUrl.isNotEmpty()

                    val displayLabel = if (isOled) "OLED (SH1106)" else "GC9A01"
                    val info = UpdateInfo(
                        hasAppUpdate = hasAppUpdate,
                        appVersionCode = appVerCode,
                        appVersionName = appVerName,
                        appApkUrl = appApkUrl,
                        appChangelog = appChangelog,
                        hasFirmwareUpdate = hasFirmwareUpdate,
                        firmwareVersionCode = fwVerCode,
                        firmwareVersionName = fwVerName,
                        firmwareBinUrl = fwBinUrl,
                        firmwareChangelog = "[$displayLabel] $fwChangelog"
                    )

                    // Nếu tìm thấy cập nhật (App hoặc FW có link tải), trả về kết quả ngay
                    if (hasAppUpdate || hasFirmwareUpdate) {
                        NavigationRepository.addLog("UpdateManager: Tìm thấy bản cập nhật mới ($displayLabel) từ: $updateUrl")
                        return@withContext UpdateCheckResult.Success(info)
                    }

                    // Nếu hợp lệ nhưng chưa cần cập nhật, ghi nhận làm fallback hợp lệ nếu các server sau không phản hồi
                    if (fallbackInfo == null && (appApkUrl.isNotEmpty() || fwBinUrl.isNotEmpty())) {
                        fallbackInfo = info
                    }
                }
            } catch (e: Exception) {
                lastError = "Lỗi kết nối $updateUrl: ${e.message}"
            }
        }

        fallbackInfo?.let {
            NavigationRepository.addLog("UpdateManager: Đang ở phiên bản mới nhất")
            return@withContext UpdateCheckResult.Success(it)
        }

        UpdateCheckResult.Error(lastError)
    }

    private fun isVersionHigher(remoteVer: String, currentVer: String): Boolean {
        if (remoteVer.isEmpty()) return false
        val cleanRemote = remoteVer.removePrefix("v").trim()
        val cleanCurrent = currentVer.removePrefix("v").trim()
        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun clientNewCall(request: Request) = httpClient.newBuilder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()
        .newCall(request)
        .execute()

    /**
     * Tải file APK từ GitHub Releases và tự động kích hoạtPackageInstaller để cài đặt.
     */
    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(apkUrl).build()
            clientNewCall(request).use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val contentLength = body.contentLength()

                val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                val apkFile = File(downloadDir, "TYMAP_Update.apk")
                if (apkFile.exists()) apkFile.delete()

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    downloaded += read
                    if (contentLength > 0) {
                        val progress = ((downloaded * 100) / contentLength).toInt()
                        withContext(Dispatchers.Main) { onProgress(progress) }
                    }
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    installApkFile(context, apkFile)
                }
                true
            }
        } catch (e: Exception) {
            NavigationRepository.addLog("UpdateManager: Lỗi tải APK -> ${e.message}")
            false
        }
    }

    /**
     * Tải file firmware.bin cho ESP32 HUD.
     */
    suspend fun downloadFirmwareBin(binUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(binUrl).build()
            clientNewCall(request).use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun installApkFile(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
