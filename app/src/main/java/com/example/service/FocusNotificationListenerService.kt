package com.example.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class FocusNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false

        // Simple and robust in-memory control flags synced by the Service and ViewModel
        var isFilterActive: Boolean = false
        var whitelistedPackages: Set<String> = setOf(
            "com.android.dialer",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.android.mms",
            "com.google.android.apps.messaging"
        )

        fun safeRequestRebind(context: android.content.Context) {
            try {
                Log.d("FocusNotification", "Proactively invoking requestRebind for FocusNotificationListenerService")
                requestRebind(ComponentName(context, FocusNotificationListenerService::class.java))
            } catch (e: Exception) {
                Log.e("FocusNotification", "Error invoking requestRebind: ${e.message}")
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        
        // Skip filtering our own notifications to ensure Pomodoro countdown runs flawlessly
        if (packageName == this.packageName) {
            return
        }

        if (isFilterActive) {
            val isWhitelisted = whitelistedPackages.contains(packageName)
            if (!isWhitelisted) {
                try {
                    // Impose system intervention: Dismiss the notification
                    cancelNotification(sbn.key)
                    Log.d("FocusNotification", "Intercepted and suppressed: $packageName")
                } catch (e: Exception) {
                    Log.e("FocusNotification", "Error suppressing notification: ${e.message}")
                }
            } else {
                Log.d("FocusNotification", "Allowed notification from whitelisted package: $packageName")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        Log.d("FocusNotification", "Notification listener connected successfully.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        Log.d("FocusNotification", "Notification listener disconnected. Attempting to requestRebind...")
        safeRequestRebind(this)
    }
}
