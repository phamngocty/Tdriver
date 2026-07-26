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
    private val httpClient = OkHttpClient()

    /**
     * Tự động kiểm tra bản cập nhật App APK và Firmware ESP32 từ GitHub Releases / Raw Version JSON.
     */
    suspend fun checkUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val customUrl = PrefsHelper.getString(context, "github_update_url", "").trim()
        val updateUrl = if (customUrl.isNotEmpty()) {
            customUrl
        } else {
            "https://raw.githubusercontent.com/phamn/TYMAP/main/version.json"
        }

        try {
            val request = Request.Builder().url(updateUrl).build()
            clientNewCall(request).use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("Khởi tạo kết nối thất bại (HTTP ${response.code})")
                }
                val bodyStr = response.body?.string() ?: return@withContext UpdateCheckResult.Error("Dữ liệu trống")
                val json = JSONObject(bodyStr)

                val appObj = json.optJSONObject("app") ?: JSONObject()
                val appVerCode = appObj.optInt("versionCode", 0)
                val appVerName = appObj.optString("versionName", "")
                val appApkUrl = appObj.optString("apkUrl", "")
                val appChangelog = appObj.optString("changelog", "Bản cập nhật mới cho TYMAP.")

                val fwObj = json.optJSONObject("firmware") ?: JSONObject()
                val fwVerCode = fwObj.optInt("versionCode", 0)
                val fwVerName = fwObj.optString("versionName", "")
                val fwBinUrl = fwObj.optString("binUrl", "")
                val fwChangelog = fwObj.optString("changelog", "Bản nâng cấp firmware mới cho ESP32 HUD.")

                val currentAppVerCode = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                    }
                } catch (e: Exception) { 1 }

                val hasAppUpdate = appVerCode > currentAppVerCode && appApkUrl.isNotEmpty()

                val currentFwVerCode = PrefsHelper.getInt(context, "esp32_fw_version_code", 0)
                val hasFirmwareUpdate = fwVerCode > currentFwVerCode && fwBinUrl.isNotEmpty()

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
                    firmwareChangelog = fwChangelog
                )
                UpdateCheckResult.Success(info)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error("Lỗi kết nối GitHub: ${e.message}")
        }
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
