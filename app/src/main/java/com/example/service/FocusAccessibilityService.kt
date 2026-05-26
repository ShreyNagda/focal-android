package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.data.AppDatabase
import com.example.data.AppUsageLimit
import com.example.ui.BlockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Highly responsive, event-driven AccessibilityService to detect and block distracting apps.
 * Intercepts TYPE_WINDOW_STATE_CHANGED events to detect when a target application enters the foreground,
 * minimizes it instantly to the launcher, and shows our custom full-screen BlockActivity.
 */
class FocusAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    @Volatile
    private var cachedLimits = mapOf<String, AppUsageLimit>()

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            db.appUsageLimitDao().getAllLimits().collect { limits ->
                withContext(Dispatchers.Main) {
                    cachedLimits = limits.associateBy { it.packageName }
                    Log.d("FocusAccessibility", "Synchronized ${cachedLimits.size} limits in memory cache.")
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // We only care about window change events (apps opening / switching screens)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            // Communicates the foreground package to the screen-time engine for live monitoring
            if (packageName != this.packageName) {
                FocusService.currentForegroundPackage = packageName
            }
            
            // Skip checking our own application so we don't block the BlockActivity or MainActivity!
            if (packageName == this.packageName) return

            // Bypass if BlockActivity is already showing or if our application is in foreground
            if (FocusService.isBlockActivityShowing || FocusService.isAppInForeground) {
                return
            }

            Log.d("FocusAccessibility", "Detected active foreground package: $packageName")

            // Check if just authorized by BlockActivity
            if (FocusService.justAuthorizedByBlockActivity && packageName == FocusService.authorizedPackage) {
                FocusService.justAuthorizedByBlockActivity = false
                Log.d("FocusAccessibility", "Allowing just-authorized app: $packageName")
                return
            }

            // Reset authorized package if user switches to a different third-party app
            if (FocusService.authorizedPackage != null && packageName != FocusService.authorizedPackage) {
                if (shouldResetAuthorization(packageName)) {
                    Log.d("FocusAccessibility", "User switched away from ${FocusService.authorizedPackage} to $packageName. Resetting authorization.")
                    FocusService.authorizedPackage = null
                }
            }

            val isFocusWorkOn = FocusService.isTimerRunning.value && FocusService.timerSessionType.value == "WORK"

            // 1. First check: Check hardcoded list for immediate containment
            if (FocusService.hardcodedBlockedPackages.contains(packageName)) {
                if (isFocusWorkOn) {
                    if (FocusService.authorizedPackage != packageName) {
                        val limitInfo = cachedLimits[packageName]
                        triggerAppIntervention(
                            blockedPackage = packageName,
                            reason = "App is blacklisted during active Focus Session",
                            showOpenButton = false,
                            remainingMins = 0,
                            targetPkg = packageName,
                            minutesUsedToday = limitInfo?.minutesUsedToday ?: 0
                        )
                        return
                    }
                }
            }

            // 2. Second check: Instant synchronous O(1) memory lookup for zero-latency blocking
            val limit = cachedLimits[packageName]
            if (limit != null) {
                val hasScreenLimit = limit.allowedMinutes >= 0
                val isOverLimit = hasScreenLimit && limit.minutesUsedToday >= limit.allowedMinutes
                val isHardBlockedAndFocusActive = limit.isHardBlocked && isFocusWorkOn

                if (isOverLimit || isHardBlockedAndFocusActive) {
                    val reason = when {
                        isOverLimit -> "Daily limit of ${limit.allowedMinutes} mins exceeded"
                        else -> "App is blacklisted during active Focus Session"
                    }
                    if (FocusService.authorizedPackage != packageName) {
                        triggerAppIntervention(
                            blockedPackage = packageName,
                            reason = reason,
                            showOpenButton = false,
                            remainingMins = 0,
                            targetPkg = packageName,
                            minutesUsedToday = limit.minutesUsedToday,
                            allowedMinutes = limit.allowedMinutes
                        )
                    }
                } else if (hasScreenLimit) {
                    // Standard Screen Time Limit: Active but still has time remaining
                    // Intercept whenever opened to show time remaining & Open/Close buttons!
                    if (FocusService.authorizedPackage != packageName) {
                        val remainingMinutes = limit.allowedMinutes - limit.minutesUsedToday
                        val reason = "Daily screen limit is configured: ${limit.allowedMinutes} mins"
                        triggerAppIntervention(
                            blockedPackage = packageName,
                            reason = reason,
                            showOpenButton = true,
                            remainingMins = remainingMinutes,
                            targetPkg = packageName,
                            minutesUsedToday = limit.minutesUsedToday,
                            allowedMinutes = limit.allowedMinutes
                        )
                    }
                }
            }
        }
    }

    /**
     * Minimizes the distracting foreground app immediately and displays our custom reminder screen.
     */
    private fun triggerAppIntervention(
        blockedPackage: String,
        reason: String,
        showOpenButton: Boolean = false,
        remainingMins: Int = -1,
        targetPkg: String? = null,
        minutesUsedToday: Int = 0,
        allowedMinutes: Int = -1
    ) {
        Log.i("FocusAccessibility", "Intercepting app: $blockedPackage. Reason: $reason")

        // 1. Fetch friendly app label (e.g. YouTube, Instagram) using the PackageManager
        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(blockedPackage, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            blockedPackage
        }

        // 3. Fire full-screen BlockActivity with required flags for starting outside an Activity lifecycle context
        val blockIntent = Intent(applicationContext, BlockActivity::class.java).apply {
            putExtra("BLOCKED_APP_NAME", appName)
            putExtra("BLOCK_REASON", reason)
            putExtra("TARGET_PACKAGE_NAME", targetPkg ?: blockedPackage)
            putExtra("REMAINING_MINUTES", remainingMins)
            putExtra("SHOW_OPEN_BUTTON", showOpenButton)
            putExtra("MINUTES_USED_TODAY", minutesUsedToday)
            putExtra("ALLOWED_MINUTES", allowedMinutes)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        FocusService.isBlockActivityShowing = true
        startActivity(blockIntent)
    }

    private fun shouldResetAuthorization(newPackage: String): Boolean {
        if (newPackage == packageName) return false
        if (newPackage == "android" || newPackage == "com.android.systemui") return false
        if (newPackage.contains("inputmethod") || newPackage.contains("keyboard")) return false

        val launcherPackages = getLauncherPackages()
        if (launcherPackages.contains(newPackage)) {
            // Returning to the home screen launcher should ALWAYS reset authorization instantly
            // so that if the app is re-opened, the block screen is displayed again.
            return true
        }

        // For other apps, keep authorized package if the transition is within 5 seconds of clicking "Open App"
        val elapsedSinceAuth = System.currentTimeMillis() - FocusService.authorizationTimestamp
        if (elapsedSinceAuth < 5000) {
            return false
        }

        // Use static high-performance thread-safe ConcurrentHashMap cache to completely avoid repeating getLaunchIntentForPackage IPCs
        return FocusService.cachedLaunchableApps.getOrPut(newPackage) {
            try {
                packageManager.getLaunchIntentForPackage(newPackage) != null
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun getLauncherPackages(): Set<String> {
        val cached = FocusService.cachedLauncherPackages
        if (cached.isNotEmpty()) {
            return cached
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val pm = packageManager
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val result = resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
            FocusService.cachedLauncherPackages = result
            result
        } catch (e: Exception) {
            emptySet()
        }
    }

    override fun onInterrupt() {
        Log.w("FocusAccessibility", "Accessibility service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
