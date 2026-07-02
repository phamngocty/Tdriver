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

    // Secure Keys
    fun putSecureString(context: Context, key: String, value: String) = getSecurePrefs(context).edit().putString(key, value).apply()
    fun getSecureString(context: Context, key: String, default: String) = getSecurePrefs(context).getString(key, default) ?: default

    // Specialized
    fun addPairedDevice(context: Context, deviceEntry: String) {
        val prefs = getPrefs(context)
        val history = prefs.getStringSet("paired_history", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (!history.contains(deviceEntry)) {
            history.add(deviceEntry)
            prefs.edit().putStringSet("paired_history", history).apply()
        }
    }
    
    fun getPairedHistory(context: Context): Set<String> = getPrefs(context).getStringSet("paired_history", emptySet()) ?: emptySet()
    
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
