package com.material.xray.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import com.material.xray.model.GeoDataUpdateInterval
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Singleton

@Singleton
class GeoDataUpdateScheduler(
    private val context: Context,
) {
    suspend fun enqueueInitialRefresh() = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(INITIAL_REFRESH_SCHEDULED_KEY, false)) return@withContext

        val request = OneTimeWorkRequestBuilder<GeoDataUpdateWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            INITIAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        ).await()
        preferences.edit().putBoolean(INITIAL_REFRESH_SCHEDULED_KEY, true).apply()
    }

    fun schedulePeriodicRefresh(intervalHours: Int) {
        val normalizedIntervalHours = GeoDataUpdateInterval.normalize(intervalHours).toLong()
        val request = PeriodicWorkRequestBuilder<GeoDataUpdateWorker>(
            normalizedIntervalHours,
            TimeUnit.HOURS,
        )
            .setInitialDelay(normalizedIntervalHours, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private companion object {
        const val PREFERENCES_NAME = "geo_data_update"
        const val INITIAL_REFRESH_SCHEDULED_KEY = "initial_refresh_scheduled"
        const val INITIAL_WORK_NAME = "geo_data_initial_update"
        const val PERIODIC_WORK_NAME = "geo_data_auto_update"
        const val BACKOFF_DELAY_MINUTES = 15L
    }
}
