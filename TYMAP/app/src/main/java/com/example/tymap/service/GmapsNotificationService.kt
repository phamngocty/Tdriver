package com.example.tymap.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.tymap.repository.NavigationRepository

class GmapsNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == "com.google.android.apps.maps") {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getString("android.text") ?: ""
            
            // Large icon usually contains the maneuver icon in Gmaps
            // We can't easily send the bitmap over BLE yet, so we'll try to map common instructions to indices
            
            if (title.isNotEmpty() || text.isNotEmpty()) {
                val intent = Intent("com.example.tymap.ACTION_GMAPS_HUD").apply {
                    putExtra("instruction", title)
                    putExtra("details", text)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Handle if needed
    }
}
