package com.material.xray.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import org.koin.android.annotation.KoinWorker

@KoinWorker
class GeoDataUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val geoDataManager: GeoDataManager,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = runCatching {
        if (settingsRepository.geoDataUpdateIntervalHours.first() != 0) {
            geoDataManager.refreshForScheduledUpdate()
        }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
