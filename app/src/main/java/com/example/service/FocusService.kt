package com.example.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.IBinder
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.AppUsageLimit
import com.example.data.FocusRepository
import com.example.data.FocusSessionLog
import com.example.ui.BlockActivity
import com.example.FocusWidgetProvider
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*

class FocusService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var timerJob: Job? = null
    private var trackerJob: Job? = null

    private val secondsTracker = mutableMapOf<String, Int>()
    private var lastCheckedDate: String? = null

    companion object {
        const val CHANNEL_ID = "minimal_focus_channel"
        const val NOTIFICATION_ID = 4101

        // Live Pomodoro Countdown & States - Exposed as Singletons to Compose with Zero Latency
        val timerSecondsRemaining = MutableStateFlow(1500) // Default: 25 minutes
        val isTimerRunning = MutableStateFlow(false)
        val isTimerStarted = MutableStateFlow(false)
        val timerSessionType = MutableStateFlow("WORK") // "WORK", "SHORT_BREAK", "LONG_BREAK"
        val currentCycle = MutableStateFlow(1)

        // Configuration
        var pomodoroDurationMins = 25
        var shortBreakDurationMins = 5
        var longBreakDurationMins = 15
        
        val hardcodedBlockedPackages = listOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.twitter.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.facebook.katana"
        )
        
        // Settings flags
        val isMonochromeScreenToggleActive = MutableStateFlow(false)

        @Volatile
        var isBlockActivityShowing = false

        @Volatile
        var activeActivityCount = 0

        val isAppInForeground: Boolean
            get() = activeActivityCount > 0

        // Temporary authorized package
        @Volatile
        var authorizedPackage: String? = null

        @Volatile
        var justAuthorizedByBlockActivity = false

        @Volatile
        var authorizationTimestamp = 0L

        // Tracker status
        val lastForegroundApp = MutableStateFlow("")

        @Volatile
        var currentForegroundPackage: String? = null

        // Cache home launcher packages and launchable applications to prevent repeating expensive PackageManager queries
        @Volatile
        var cachedLauncherPackages: Set<String> = emptySet()

        val cachedLaunchableApps = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        fun startService(context: Context) {
            val intent = Intent(context, FocusService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("FocusService", "Could not start foreground service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FocusService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e("FocusService", "Could not stop service: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServiceForeground()
        startUsageAndAppTracker()

        // Prefill launcher package cache lazily if not already populated
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            cachedLauncherPackages = resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
            Log.d("FocusService", "Prefilled cached launcher packages in onCreate: $cachedLauncherPackages")
        } catch (e: Exception) {
            Log.e("FocusService", "Could not prefill launcher packages cache: ${e.message}")
        }

        // Sync system-level grayscale setting when local toggle is updated
        serviceScope.launch {
            isMonochromeScreenToggleActive.collect { active ->
                try {
                    val resolver = contentResolver
                    Settings.Secure.putInt(resolver, "accessibility_display_daltonizer_enabled", if (active) 1 else 0)
                    Settings.Secure.putInt(resolver, "accessibility_display_daltonizer", if (active) 0 else -1)
                } catch (e: Exception) {
                    Log.w("FocusService", "Could not set global system grayscale: ${e.message}")
                }
            }
        }

        // Trigger widget update at startup
        FocusWidgetProvider.updateAllWidgets(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle custom control actions if sent
        val action = intent?.action
        when (action) {
            "START_POMODORO" -> startPomodoro()
            "PAUSE_POMODORO" -> pausePomodoro()
            "RESET_POMODORO" -> resetPomodoro()
            "SKIP_POMODORO" -> skipPomodoro()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun buildTimerNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isRunning = isTimerRunning.value
        val sessionTypeName = when (timerSessionType.value) {
            "WORK" -> "Work"
            "SHORT_BREAK" -> "Short Break"
            "LONG_BREAK" -> "Long Break"
            else -> "Work"
        }
        
        val title = if (isRunning) {
            "Focal - $sessionTypeName"
        } else if (isTimerStarted.value) {
            "$sessionTypeName - Paused"
        } else {
            "Focal - $sessionTypeName"
        }

        val remainingSeconds = timerSecondsRemaining.value
        val remainingMinutes = remainingSeconds / 60
        val remainingSecs = remainingSeconds % 60
        val text = String.format(Locale.getDefault(), "%02d:%02d", remainingMinutes, remainingSecs)

        // Setup Pause/Resume Action
        val pauseResumeActionIntent = Intent(this, FocusService::class.java).apply {
            action = if (isRunning) "PAUSE_POMODORO" else "START_POMODORO"
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseResumeActionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseResumeTitle = if (isRunning) "Pause" else if (isTimerStarted.value) "Resume" else "Start"
        val pauseResumeIcon = if (isRunning) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        // Setup Reset Action
        val resetActionIntent = Intent(this, FocusService::class.java).apply {
            action = "RESET_POMODORO"
        }
        val resetPendingIntent = PendingIntent.getService(
            this,
            2,
            resetActionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setTicker("Focus engine update")
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Ensure low importance
            .addAction(pauseResumeIcon, pauseResumeTitle, pauseResumePendingIntent)
            .addAction(android.R.drawable.ic_menu_revert, "Reset", resetPendingIntent)

        if (isRunning) {
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
            builder.setWhen(System.currentTimeMillis() + (remainingSeconds * 1000L))
            builder.setContentText("Focus run active — tracking screen-time")
        } else {
            builder.setUsesChronometer(false)
            builder.setContentText(text)
        }

        return builder.build()
    }

    private fun startServiceForeground() {
        val notification = buildTimerNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use standard FOREGROUND_SERVICE_TYPE_DATA_SYNC (clean, universally supported)
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val notification = buildTimerNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotificationText(): String {
        val minutes = timerSecondsRemaining.value / 60
        val seconds = timerSecondsRemaining.value % 60
        val timeStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        val sessionName = timerSessionType.value
        val runningStatus = if (isTimerRunning.value) "RUNNING" else if (isTimerStarted.value) "PAUSED" else "Focus now"
        return "[$sessionName // $runningStatus] -> $timeStr (Cycle ${currentCycle.value}/4)"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Minimal Focus Engine Background Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Handles background countdowns and usage interventions."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = AndroidColor.WHITE
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // --- POMODORO ACTIONS ---

    private fun startPomodoro() {
        if (isTimerRunning.value) return
        isTimerRunning.value = true
        isTimerStarted.value = true

        timerJob = serviceScope.launch {
            while (isActive && timerSecondsRemaining.value > 0) {
                delay(1000)
                timerSecondsRemaining.value -= 1
                // Only update notification on minute boundaries as the chronometer handles second-specific ticks
                if (timerSecondsRemaining.value % 60 == 0) {
                    updateNotification()
                }
                // Update widget every second for responsive, live countdown display (now is non-blocking IO thread)
                FocusWidgetProvider.updateAllWidgets(applicationContext)
            }
            if (timerSecondsRemaining.value == 0) {
                onPomodoroCompleted()
            }
        }
        updateNotification()
        FocusWidgetProvider.updateAllWidgets(applicationContext)
    }

    private fun pausePomodoro() {
        isTimerRunning.value = false
        timerJob?.cancel()
        timerJob = null
        updateNotification()
        FocusWidgetProvider.updateAllWidgets(applicationContext)
    }

    private fun resetPomodoro() {
        pausePomodoro()
        isTimerStarted.value = false
        val defaultMinutes = when (timerSessionType.value) {
            "WORK" -> pomodoroDurationMins
            "SHORT_BREAK" -> shortBreakDurationMins
            "LONG_BREAK" -> longBreakDurationMins
            else -> pomodoroDurationMins
        }
        timerSecondsRemaining.value = defaultMinutes * 60
        updateNotification()
        FocusWidgetProvider.updateAllWidgets(applicationContext)
    }

    private fun skipPomodoro() {
        if (timerSessionType.value == "WORK") {
            Log.d("FocusService", "Cannot skip a work block!")
            return
        }
        pausePomodoro()
        onPomodoroCompleted()
    }

    private fun onPomodoroCompleted() {
        val currentType = timerSessionType.value
        
        // Log finished session to Room Database
        serviceScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val repo = FocusRepository(db)
            val duration = when (currentType) {
                "WORK" -> pomodoroDurationMins
                "SHORT_BREAK" -> shortBreakDurationMins
                "LONG_BREAK" -> longBreakDurationMins
                else -> pomodoroDurationMins
            }
            repo.logSession(FocusSessionLog(durationMinutes = duration, sessionType = currentType))
        }

        // Cycle calculation:
        // WORK -> BREAK -> WORK -> BREAK until 4 cycles completed, then LONG_BREAK
        if (currentType == "WORK") {
            if (currentCycle.value >= 4) {
                timerSessionType.value = "LONG_BREAK"
                timerSecondsRemaining.value = longBreakDurationMins * 60
            } else {
                timerSessionType.value = "SHORT_BREAK"
                timerSecondsRemaining.value = shortBreakDurationMins * 60
            }
        } else {
            // It was a short break or long break, return to work
            if (currentType == "LONG_BREAK") {
                currentCycle.value = 1
            } else {
                currentCycle.value += 1
            }
            timerSessionType.value = "WORK"
            timerSecondsRemaining.value = pomodoroDurationMins * 60
        }

        // Fire a highly recognizable short system notification to notify transition
        fireCompletionAlert("Focus Intermission Changed", "New Mode: ${timerSessionType.value}")

        // Play standard notification ringtone or fall back to ToneGenerator beep
        try {
            val systemSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtoneSound = RingtoneManager.getRingtone(applicationContext, systemSoundUri)
            ringtoneSound?.play()
        } catch (e: Exception) {
            Log.e("FocusService", "Failed to play completion sound using RingtoneManager: ${e.message}")
            try {
                val toneG = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                toneG.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 300)
            } catch (ex: Exception) {
                Log.e("FocusService", "Failed to play ToneGenerator warning beep: ${ex.message}")
            }
        }

        isTimerRunning.value = false
        isTimerStarted.value = false
        updateNotification()
        FocusWidgetProvider.updateAllWidgets(applicationContext)
    }

    private fun fireCompletionAlert(title: String, text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 2, alertNotification)
    }

    // --- TRACKING & BLOCKING SYSTEM ---

    private fun startUsageAndAppTracker() {
        trackerJob = serviceScope.launch(Dispatchers.Default) {
            val db = AppDatabase.getDatabase(applicationContext)
            val repo = FocusRepository(db)

            while (isActive) {
                delay(1000)

                // Skip tracking entirely if screen is off/not interactive
                val isInteractive = try {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isInteractive
                } catch (e: Exception) {
                    true
                }
                if (!isInteractive) {
                    continue
                }

                // 1. Maintain Reset for Daily Usage Stats at Midnight
                val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                checkMidnightReset(repo, todayDate)

                // 2. Fetch Active Foreground Package (prefer highly responsive real-time accessibility detection, with usage stats fallback)
                val fgApp = if (isAppInForeground) {
                    packageName
                } else {
                    currentForegroundPackage ?: getForegroundPackage()
                }
                if (fgApp != null && fgApp.isNotEmpty() && fgApp != packageName) {
                    lastForegroundApp.value = fgApp

                    // Manage screen limits & tracking for all monitored foreground apps
                    var limit = repo.getLimitForPackage(fgApp)

                    // Check if it's a launchable app that isn't a keyboard or system shell to monitor all apps
                    val isSystemOrKeyboard = fgApp == "android" 
                        || fgApp == "com.android.systemui" 
                        || fgApp.contains("inputmethod") 
                        || fgApp.contains("keyboard")

                    val isHomeLauncher = if (cachedLauncherPackages.isEmpty()) {
                        try {
                            val intent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                            }
                            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                            val result = resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
                            cachedLauncherPackages = result
                            result.contains(fgApp)
                        } catch (e: Exception) {
                            false
                        }
                    } else {
                        cachedLauncherPackages.contains(fgApp)
                    }

                    if (limit == null && !isSystemOrKeyboard && !isHomeLauncher) {
                        val appLabel = getAppNameFromPackageName(fgApp)
                        val newLimit = AppUsageLimit(
                            packageName = fgApp,
                            appName = appLabel,
                            allowedMinutes = -1,
                            isHardBlocked = false,
                            minutesUsedToday = 0,
                            lastResetDate = todayDate
                        )
                        repo.saveLimit(newLimit)
                        limit = newLimit
                    }

                    if (limit != null) {
                        // Cumulate open times in seconds
                        val seconds = (secondsTracker[fgApp] ?: 0) + 1
                        if (seconds >= 60) {
                            secondsTracker[fgApp] = 0
                            // Increment daily database log asynchronously
                            repo.updateMinutesUsed(fgApp, limit.minutesUsedToday + 1)
                        } else {
                            secondsTracker[fgApp] = seconds
                        }

                        // Inspect if we should immediately apply hard block interventions
                        checkAndBlockIfNeeded(limit, repo)
                    }
                }
            }
        }
    }

    private suspend fun checkMidnightReset(repo: FocusRepository, todayDate: String) {
        if (lastCheckedDate == todayDate) return
        lastCheckedDate = todayDate
        withContext(Dispatchers.IO) {
            repo.resetDailyUsage(todayDate)
        }
    }

    private fun checkAndBlockIfNeeded(limit: AppUsageLimit, repo: FocusRepository) {
        if (isBlockActivityShowing || isAppInForeground) {
            return
        }

        val isFocusWorkOn = isTimerRunning.value && timerSessionType.value == "WORK"
        val isOverLimit = limit.allowedMinutes in 0..limit.minutesUsedToday
        val isHardBlockedAndFocusActive = (limit.isHardBlocked || hardcodedBlockedPackages.contains(limit.packageName)) && isFocusWorkOn

        if (isOverLimit || isHardBlockedAndFocusActive) {
            if (authorizedPackage == limit.packageName) {
                return
            }

            val reason = when {
                isOverLimit -> "Daily limit of ${limit.allowedMinutes} mins exceeded ($todayUsageString)"
                else -> "App blocked during active Pomodoro Work Session"
            }

            // Launch Block Activity
            serviceScope.launch(Dispatchers.Main) {
                isBlockActivityShowing = true
                val blockIntent = Intent(applicationContext, BlockActivity::class.java).apply {
                    putExtra("BLOCKED_APP_NAME", limit.appName)
                    putExtra("BLOCK_REASON", reason)
                    putExtra("TARGET_PACKAGE_NAME", limit.packageName)
                    putExtra("REMAINING_MINUTES", 0)
                    putExtra("SHOW_OPEN_BUTTON", false)
                    putExtra("MINUTES_USED_TODAY", limit.minutesUsedToday)
                    putExtra("ALLOWED_MINUTES", limit.allowedMinutes)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(blockIntent)
            }
        }
    }

    private fun getAppNameFromPackageName(packageName: String): String {
        val pm = packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            when (packageName) {
                "com.instagram.android" -> "Instagram"
                "com.google.android.youtube" -> "YouTube"
                "com.twitter.android" -> "Twitter"
                "com.zhiliaoapp.musically" -> "TikTok"
                "com.facebook.katana" -> "Facebook"
                else -> "Distracting App"
            }
        }
    }

    private val todayUsageString: String
        get() = "Today's limit exhausted"

    private fun getForegroundPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTime = System.currentTimeMillis()

        // 1. Try UsageEvents first (high-precision event-driven detection)
        val usageEvents = usageStatsManager.queryEvents(endTime - 10000, endTime)
        val event = UsageEvents.Event()
        var lastResumedEventPackage: String? = null

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumedEventPackage = event.packageName
            }
        }

        if (lastResumedEventPackage != null && lastResumedEventPackage.isNotEmpty()) {
            return lastResumedEventPackage
        }

        // 2. Continuous fallback (query usage stats sorted desc lastTimeUsed for active screen continuity)
        try {
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, endTime - 3600000, endTime)
            if (!stats.isNullOrEmpty()) {
                var mostRecentStats: android.app.usage.UsageStats? = null
                for (usageStats in stats) {
                    if (mostRecentStats == null || usageStats.lastTimeUsed > mostRecentStats.lastTimeUsed) {
                        mostRecentStats = usageStats
                    }
                }
                return mostRecentStats?.packageName
            }
        } catch (e: Exception) {
            Log.e("FocusService", "Failed to query continuous usage stats fallback: ${e.message}")
        }

        return null
    }
}
