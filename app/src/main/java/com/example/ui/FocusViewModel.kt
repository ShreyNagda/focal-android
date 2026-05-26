package com.example.ui

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.service.FocusNotificationListenerService
import com.example.service.FocusService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AppInfo(val packageName: String, val appName: String)

class FocusViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: FocusRepository
    
    // Installed applications
    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    // Database limit flows
    val appLimits: StateFlow<List<AppUsageLimit>>

    // Completed focus logs from DB
    val sessionLogs: StateFlow<List<FocusSessionLog>>

    // Live state bindings from the Service Singletons
    val timerSecondsRemaining = FocusService.timerSecondsRemaining
    val isTimerRunning = FocusService.isTimerRunning
    val isTimerStarted = FocusService.isTimerStarted
    val timerSessionType = FocusService.timerSessionType
    val currentCycle = FocusService.currentCycle
    val isMonochromeActive = FocusService.isMonochromeScreenToggleActive

    // Onboarding persistence state
    private val sharedPrefs = application.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
    private val _onboardingCompleted = MutableStateFlow(sharedPrefs.getBoolean("onboarding_completed", false))
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    fun completeOnboarding() {
        sharedPrefs.edit().putBoolean("onboarding_completed", true).apply()
        _onboardingCompleted.value = true
    }

    // In-memory whitelisted apps for notification listener
    private val _notificationWhitelist = MutableStateFlow<Set<String>>(FocusNotificationListenerService.whitelistedPackages)
    val notificationWhitelist: StateFlow<Set<String>> = _notificationWhitelist.asStateFlow()

    // Interactive Notification Filter toggle state
    private val _isNotificationFilterEnabled = MutableStateFlow(FocusNotificationListenerService.isFilterActive)
    val isNotificationFilterEnabled: StateFlow<Boolean> = _isNotificationFilterEnabled.asStateFlow()

    // Permission check flags (checked on resume)
    val hasUsageStatsPermission = MutableStateFlow(false)
    val hasNotificationListenerPermission = MutableStateFlow(false)
    val hasOverlayPermission = MutableStateFlow(false)
    val hasAccessibilityPermission = MutableStateFlow(false)

    init {
        val database = AppDatabase.getDatabase(application)
        repository = FocusRepository(database)

        appLimits = repository.allLimits.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        sessionLogs = repository.allSessions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        loadInstalledApps()
        updatePermissionFlags()
    }

    // --- PERMISSIONS CHECKS ---

    fun updatePermissionFlags() {
        val context = getApplication<Application>()
        hasUsageStatsPermission.value = checkUsageStatsPermission(context)
        val hasNotifPerm = checkNotificationListenerPermission(context)
        hasNotificationListenerPermission.value = hasNotifPerm
        hasOverlayPermission.value = checkOverlayPermission(context)
        hasAccessibilityPermission.value = checkAccessibilityPermission(context)
        
        // Self-heal/Rebind notification listener if authorized but disconnected
        if (hasNotifPerm && !FocusNotificationListenerService.isConnected) {
            FocusNotificationListenerService.safeRequestRebind(context)
        }
        
        // Push current notification filter state
        _isNotificationFilterEnabled.value = FocusNotificationListenerService.isFilterActive
        _notificationWhitelist.value = FocusNotificationListenerService.whitelistedPackages
    }

    private fun checkUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun checkOverlayPermission(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun checkNotificationListenerPermission(context: Context): Boolean {
        return try {
            val packageName = context.packageName
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            flat != null && flat.contains(packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun checkAccessibilityPermission(context: Context): Boolean {
        return try {
            val expectedServiceName = "${context.packageName}/com.example.service.FocusAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.split(':').any { it.trim().equals(expectedServiceName, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    // --- APP LIST & LIMIT MANAGEMENT ---

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val pm = context.packageManager
                
                // Query launchable intents directly - simple, super fast (single system IPC), and doesn't lock/freeze the thread or cause ANR
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolved = pm.queryIntentActivities(mainIntent, 0)
                val list = resolved.mapNotNull { info ->
                    val pkg = info.activityInfo.packageName
                    if (pkg == context.packageName) null
                    else {
                        val label = info.loadLabel(pm).toString()
                        AppInfo(packageName = pkg, appName = label)
                    }
                }

                val finalizedList = list
                    .distinctBy { it.packageName }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })

                _installedApps.value = finalizedList
                android.util.Log.d("FocusViewModel", "Loaded ${finalizedList.size} installed apps using single queryIntentActivities call.")
            } catch (e: Exception) {
                android.util.Log.e("FocusViewModel", "Failed to load apps: ${e.message}")
                _installedApps.value = emptyList()
            }
        }
    }

    fun setAppLimit(packageName: String, appName: String, minutes: Int, isHardBlocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            // Check if limit entry already exists
            val existing = repository.getLimitForPackage(packageName)
            val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val limit = existing?.copy(
                allowedMinutes = minutes,
                isHardBlocked = isHardBlocked
            ) ?: AppUsageLimit(
                packageName = packageName,
                appName = appName,
                allowedMinutes = minutes,
                isHardBlocked = isHardBlocked,
                lastResetDate = todayDate
            )
            repository.saveLimit(limit)
        }
    }

    fun removeLimit(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.getLimitForPackage(packageName)
            if (existing != null) {
                repository.deleteLimit(existing)
            }
        }
    }

    fun toggleHardBlock(packageName: String, appName: String, shouldHardBlock: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.getLimitForPackage(packageName)
            val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val limit = existing?.copy(isHardBlocked = shouldHardBlock)
                ?: AppUsageLimit(
                    packageName = packageName,
                    appName = appName,
                    isHardBlocked = shouldHardBlock,
                    lastResetDate = todayDate
                )
            repository.saveLimit(limit)
        }
    }

    // --- TIMERS & SETTINGS CONTROLS ---

    fun startTimer(context: Context) {
        FocusService.startService(context)
        sendTimerAction(context, "START_POMODORO")
    }

    fun pauseTimer(context: Context) {
        sendTimerAction(context, "PAUSE_POMODORO")
    }

    fun resetTimer(context: Context) {
        sendTimerAction(context, "RESET_POMODORO")
    }

    fun skipTimer(context: Context) {
        sendTimerAction(context, "SKIP_POMODORO")
    }

    fun updateServiceDurations(work: Int, short: Int, long: Int) {
        FocusService.pomodoroDurationMins = work
        FocusService.shortBreakDurationMins = short
        FocusService.longBreakDurationMins = long
    }

    fun toggleNotificationFilter(enabled: Boolean) {
        FocusNotificationListenerService.isFilterActive = enabled
        _isNotificationFilterEnabled.value = enabled
    }

    fun toggleNotificationWhitelistApp(packageName: String) {
        val currentSet = FocusNotificationListenerService.whitelistedPackages.toMutableSet()
        if (currentSet.contains(packageName)) {
            currentSet.remove(packageName)
        } else {
            currentSet.add(packageName)
        }
        FocusNotificationListenerService.whitelistedPackages = currentSet
        _notificationWhitelist.value = currentSet
    }

    fun toggleMonochromeMode() {
        isMonochromeActive.value = !isMonochromeActive.value
    }

    fun clearFocusHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearSessions()
        }
    }

    private fun sendTimerAction(context: Context, action: String) {
        val intent = Intent(context, FocusService::class.java).apply {
            this.action = action
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("FocusViewModel", "Could not send timer action: ${e.message}")
        }
    }
}
