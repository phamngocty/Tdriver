package com.example.tymap.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.tymap.utils.PrefsHelper
import com.google.gson.Gson

class MyNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName
        val enabledApps = PrefsHelper.getStringSet(this, "enabled_notifications", emptySet())
        
        if (enabledApps.contains(pkg)) {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            
            if (title.isNotEmpty() || text.isNotEmpty()) {
                val appLabel = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) { pkg }

                val notificationData = mapOf(
                    "app" to appLabel,
                    "title" to title,
                    "message" to text
                )

                val bleManager = NavigationService.bleManager
                bleManager?.writeNotification(Gson().toJson(notificationData))
            }
        }
    }
}
