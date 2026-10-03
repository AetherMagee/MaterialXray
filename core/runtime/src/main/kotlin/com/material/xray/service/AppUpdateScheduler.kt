package com.material.xray.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.material.xray.core.common.connection.AppUpdateScheduling
import com.material.xray.model.AppUpdateInterval
import java.util.concurrent.TimeUnit
import org.koin.core.annotation.Singleton

@Singleton(binds = [AppUpdateScheduling::class])
class AppUpdateScheduler(
    private val context: Context,
) : AppUpdateScheduling {
    override fun setEnabled(enabled: Boolean, interval: AppUpdateInterval) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK_NAME)
        if (enabled) {
            schedule(interval)
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    private fun schedule(interval: AppUpdateInterval) {
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(interval.hours.toLong(), TimeUnit.HOURS)
            .setConstraints(networkConstraints())
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
        const val PERIODIC_WORK_NAME = "app_update_check"
        const val LEGACY_IMMEDIATE_WORK_NAME = "app_update_check_now"
    }
}
