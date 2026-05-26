package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage_limits")
data class AppUsageLimit(
    @PrimaryKey val packageName: String,
    val appName: String,
    val allowedMinutes: Int = -1, // -1 means no limit
    val isHardBlocked: Boolean = false, // Blocked during Pomodoro working focus
    val minutesUsedToday: Int = 0,
    val lastResetDate: String = "" // "yyyy-MM-dd"
)
