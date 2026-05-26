package com.example.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class FocusTileService : TileService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        collectJob?.cancel()
        collectJob = scope.launch {
            FocusService.isTimerRunning.collectLatest { isRunning ->
                updateTileState(isRunning)
            }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = FocusService.isTimerRunning.value
        val context = applicationContext
        
        try {
            if (isRunning) {
                // Stop Focus Mode
                val intent = Intent(context, FocusService::class.java).apply {
                    action = "PAUSE_POMODORO"
                }
                context.startService(intent)
                FocusNotificationListenerService.isFilterActive = false
            } else {
                // Start Focus Mode
                FocusService.startService(context)
                val intent = Intent(context, FocusService::class.java).apply {
                    action = "START_POMODORO"
                }
                context.startService(intent)
                FocusNotificationListenerService.isFilterActive = true
            }
        } catch (e: Exception) {
            android.util.Log.e("FocusTileService", "Failed to start/stop focus session from tile click: ${e.message}")
        }
    }

    private fun updateTileState(isRunning: Boolean) {
        try {
            val tile = qsTile ?: return
            if (isRunning) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Focus Active"
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Start Focus"
            }
            tile.updateTile()
        } catch (e: Exception) {
            android.util.Log.e("FocusTileService", "Error updating tile: ${e.message}")
        }
    }
}
