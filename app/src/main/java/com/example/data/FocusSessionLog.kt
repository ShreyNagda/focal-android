package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_sessions")
data class FocusSessionLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val durationMinutes: Int,
    val sessionType: String, // "WORK", "SHORT_BREAK", "LONG_BREAK"
    val timestamp: Long = System.currentTimeMillis()
)
