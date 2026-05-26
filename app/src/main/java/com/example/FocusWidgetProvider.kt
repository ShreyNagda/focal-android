package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.service.quicksettings.TileService
import com.example.service.FocusNotificationListenerService
import com.example.service.FocusService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FocusWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_FOCUS = "com.example.action.TOGGLE_FOCUS"

        private var lastTileIsRunning: Boolean? = null
        private var lastTileIsStarted: Boolean? = null

        fun updateAllWidgets(context: Context) {
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val appWidgetManager = AppWidgetManager.getInstance(appContext)
                    val thisWidget = ComponentName(appContext, FocusWidgetProvider::class.java)
                    val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                    if (allWidgetIds.isEmpty()) return@launch
                    
                    val isStarted = FocusService.isTimerStarted.value
                    val isRunning = FocusService.isTimerRunning.value
                    val secondsRemaining = FocusService.timerSecondsRemaining.value
                    val minutes = secondsRemaining / 60
                    val seconds = secondsRemaining % 60
                    val timeStr = String.format("%02d:%02d", minutes, seconds)

                    for (widgetId in allWidgetIds) {
                        val views = RemoteViews(appContext.packageName, R.layout.focus_widget)
                        
                        // Update text based on current state
                        if (isStarted) {
                            views.setTextViewText(R.id.widget_title, timeStr)
                            views.setTextColor(R.id.widget_title, android.graphics.Color.WHITE)
                            
                            val statusText = "${FocusService.timerSessionType.value} • ${if (isRunning) "RUNNING" else "PAUSED"}"
                            views.setTextViewText(R.id.widget_status, statusText)
                            
                            val statusColor = if (isRunning) "#4CAF50" else "#A1A1AA"
                            views.setTextColor(R.id.widget_status, android.graphics.Color.parseColor(statusColor))
                        } else {
                            views.setTextViewText(R.id.widget_title, "focal")
                            views.setTextColor(R.id.widget_title, android.graphics.Color.WHITE)
                            
                            views.setTextViewText(R.id.widget_status, "START FOCUS")
                            views.setTextColor(R.id.widget_status, android.graphics.Color.parseColor("#71717A"))
                        }
                        
                        // Set click action to toggle focus mode
                        val intent = Intent(appContext, FocusWidgetProvider::class.java).apply {
                            action = ACTION_TOGGLE_FOCUS
                        }
                        val pendingIntent = PendingIntent.getBroadcast(
                            appContext, 
                            widgetId, 
                            intent, 
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
                        
                        appWidgetManager.updateAppWidget(widgetId, views)
                    }

                    // Sync Quick Settings Tile state only on status transitions (avoid 1-second interval IPC flooding)
                    if (isRunning != lastTileIsRunning || isStarted != lastTileIsStarted) {
                        lastTileIsRunning = isRunning
                        lastTileIsStarted = isStarted
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            try {
                                TileService.requestListeningState(
                                    appContext,
                                    ComponentName(appContext, "com.example.service.FocusTileService")
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("FocusWidgetProvider", "Failed to update Quick Settings Tile state: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FocusWidgetProvider", "Error updating widgets in background: ${e.message}")
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_FOCUS) {
            val isRunning = FocusService.isTimerRunning.value
            try {
                if (isRunning) {
                    // Stop Focus Mode
                    val serviceIntent = Intent(context, FocusService::class.java).apply {
                        action = "PAUSE_POMODORO"
                    }
                    context.startService(serviceIntent)
                    FocusNotificationListenerService.isFilterActive = false
                } else {
                    // Start Focus Mode
                    FocusService.startService(context)
                    val serviceIntent = Intent(context, FocusService::class.java).apply {
                        action = "START_POMODORO"
                    }
                    context.startService(serviceIntent)
                    FocusNotificationListenerService.isFilterActive = true
                }
            } catch (e: Exception) {
                android.util.Log.e("FocusWidgetProvider", "Failed to start/stop focus session from widget: ${e.message}")
            }
            
            // Re-update widgets
            updateAllWidgets(context)
        }
    }
}
