package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageLimitDao {
    @Query("SELECT * FROM app_usage_limits ORDER BY appName ASC")
    fun getAllLimits(): Flow<List<AppUsageLimit>>

    @Query("SELECT * FROM app_usage_limits WHERE packageName = :packageName LIMIT 1")
    suspend fun getLimitForPackage(packageName: String): AppUsageLimit?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateLimit(limit: AppUsageLimit)

    @Delete
    suspend fun deleteLimit(limit: AppUsageLimit)

    @Query("UPDATE app_usage_limits SET minutesUsedToday = 0, lastResetDate = :todayDate WHERE lastResetDate != :todayDate")
    suspend fun resetAllDailyUsage(todayDate: String)

    @Query("UPDATE app_usage_limits SET minutesUsedToday = :minutes WHERE packageName = :packageName")
    suspend fun updateMinutesUsed(packageName: String, minutes: Int)
}
