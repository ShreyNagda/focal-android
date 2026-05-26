package com.example.data

import kotlinx.coroutines.flow.Flow

class FocusRepository(private val database: AppDatabase) {
    val allLimits: Flow<List<AppUsageLimit>> = database.appUsageLimitDao().getAllLimits()
    val allSessions: Flow<List<FocusSessionLog>> = database.focusSessionDao().getAllSessions()

    suspend fun getLimitForPackage(packageName: String): AppUsageLimit? {
        return database.appUsageLimitDao().getLimitForPackage(packageName)
    }

    suspend fun saveLimit(limit: AppUsageLimit) {
        database.appUsageLimitDao().insertOrUpdateLimit(limit)
    }

    suspend fun deleteLimit(limit: AppUsageLimit) {
        database.appUsageLimitDao().deleteLimit(limit)
    }

    suspend fun resetDailyUsage(todayDate: String) {
        database.appUsageLimitDao().resetAllDailyUsage(todayDate)
    }

    suspend fun updateMinutesUsed(packageName: String, minutes: Int) {
        database.appUsageLimitDao().updateMinutesUsed(packageName, minutes)
    }

    suspend fun logSession(session: FocusSessionLog) {
        database.focusSessionDao().insertSession(session)
    }

    suspend fun clearSessions() {
        database.focusSessionDao().clearAll()
    }
}
