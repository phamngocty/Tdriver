package com.example.tymap.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object PrefsHelper {
    private const val PREFS_NAME = "tymap_settings"
    private const val SECURE_PREFS_NAME = "tymap_secure_settings"

    private var securePrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getSecurePrefs(context: Context): SharedPreferences {
        if (securePrefs == null) {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                securePrefs = EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                android.util.Log.e("PrefsHelper", "EncryptedSharedPreferences init failed, falling back: ${e.message}")
                securePrefs = getPrefs(context)
            }
        }
        return securePrefs!!
    }

    // General Settings
    fun putInt(context: Context, key: String, value: Int) = getPrefs(context).edit().putInt(key, value).apply()
    fun getInt(context: Context, key: String, default: Int) = getPrefs(context).getInt(key, default)

    fun putString(context: Context, key: String, value: String) = getPrefs(context).edit().putString(key, value).apply()
    fun getString(context: Context, key: String, default: String) = getPrefs(context).getString(key, default) ?: default

    fun putBoolean(context: Context, key: String, value: Boolean) = getPrefs(context).edit().putBoolean(key, value).apply()
    fun getBoolean(context: Context, key: String, default: Boolean) = getPrefs(context).getBoolean(key, default)

    fun putFloat(context: Context, key: String, value: Float) = getPrefs(context).edit().putFloat(key, value).apply()
    fun getFloat(context: Context, key: String, default: Float) = getPrefs(context).getFloat(key, default)

    fun putStringSet(context: Context, key: String, value: Set<String>) = getPrefs(context).edit().putStringSet(key, value).apply()
    fun getStringSet(context: Context, key: String, default: Set<String>) = getPrefs(context).getStringSet(key, default) ?: default

    // Secure Keys (With fail-safe mirroring to guarantee persistence across app restarts)
    fun putSecureString(context: Context, key: String, value: String) {
        try {
            getSecurePrefs(context).edit().putString(key, value).commit()
        } catch (e: Exception) {
            android.util.Log.e("PrefsHelper", "putSecureString failed: ${e.message}")
        }
        putString(context, key, value)
    }

    fun getSecureString(context: Context, key: String, default: String): String {
        val secureVal = try {
            getSecurePrefs(context).getString(key, "") ?: ""
        } catch (e: Exception) {
            ""
        }
        if (secureVal.isNotEmpty()) return secureVal
        return getString(context, key, default)
    }

    // Specialized: Lịch sử kết nối BLE với Khử Trùng Lặp MAC & Quản Lý Xóa
    fun addPairedDevice(context: Context, name: String, mac: String) {
        if (mac.isBlank()) return
        val prefs = getPrefs(context)
        val oldSet = prefs.getStringSet("paired_history", emptySet()) ?: emptySet()
        val history = HashSet(oldSet)
        val cleanName = if (name.isBlank() || name == "Thiết bị") "TYMAP" else name.trim()
        val cleanMac = mac.trim().uppercase()
        // Loại bỏ mọi mục cũ có cùng MAC address để khử hoàn toàn trùng lặp
        val toRemove = history.filter { it.contains(cleanMac, ignoreCase = true) }
        history.removeAll(toRemove.toSet())
        history.add("$cleanName ($cleanMac)")
        prefs.edit().putStringSet("paired_history", history).apply()
    }

    fun addPairedDevice(context: Context, deviceEntry: String) {
        val mac = deviceEntry.substringAfter("(").substringBefore(")").trim()
        val name = deviceEntry.substringBefore("(").trim()
        if (mac.length == 17) {
            addPairedDevice(context, name, mac)
        } else {
            val prefs = getPrefs(context)
            val oldSet = prefs.getStringSet("paired_history", emptySet()) ?: emptySet()
            val history = HashSet(oldSet)
            val toRemove = history.filter { it.contains(deviceEntry, ignoreCase = true) }
            history.removeAll(toRemove.toSet())
            history.add(deviceEntry)
            prefs.edit().putStringSet("paired_history", history).apply()
        }
    }

    fun removePairedDevice(context: Context, macOrEntry: String) {
        val prefs = getPrefs(context)
        val oldSet = prefs.getStringSet("paired_history", emptySet()) ?: emptySet()
        val history = HashSet(oldSet)
        val target = macOrEntry.substringAfter("(").substringBefore(")").trim().ifEmpty { macOrEntry.trim() }
        val toRemove = history.filter { it.contains(target, ignoreCase = true) }
        if (toRemove.isNotEmpty()) {
            history.removeAll(toRemove.toSet())
            prefs.edit().putStringSet("paired_history", history).apply()
        }
    }
    
    fun getPairedHistory(context: Context): Set<String> {
        val rawSet = getPrefs(context).getStringSet("paired_history", emptySet()) ?: emptySet()
        // Deduplicate and sanitize entries
        val mapByMac = mutableMapOf<String, String>()
        for (entry in rawSet) {
            val mac = entry.substringAfter("(").substringBefore(")").trim().uppercase()
            if (mac.length == 17) {
                mapByMac[mac] = entry
            } else if (entry.isNotBlank()) {
                mapByMac[entry] = entry
            }
        }
        return mapByMac.values.toSet()
    }
    
    fun clearPairedHistory(context: Context) = getPrefs(context).edit().remove("paired_history").apply()

    // Advanced Color Filters
    fun putColorFilters(context: Context, json: String) = putString(context, "oled_filters_json", json)
    fun getColorFilters(context: Context): String = getString(context, "oled_filters_json", "[]")

    // Crop Config (Normalized)
    fun putCropConfig(context: Context, x: Float, y: Float, size: Float) {
        val editor = getPrefs(context).edit()
        editor.putFloat("crop_x_norm", x)
        editor.putFloat("crop_y_norm", y)
        editor.putFloat("crop_size_norm", size)
        editor.apply()
    }
}
