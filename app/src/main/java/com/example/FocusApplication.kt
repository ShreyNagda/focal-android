package com.example

import android.app.Application
import android.app.Activity
import android.os.Bundle
import com.example.service.FocusService

class FocusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                FocusService.activeActivityCount = startedActivities
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities < 0) startedActivities = 0
                FocusService.activeActivityCount = startedActivities
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
