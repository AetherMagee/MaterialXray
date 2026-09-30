package com.material.xray.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.material.xray.core.xray.GeoDataManager
import org.koin.android.annotation.KoinWorker

@KoinWorker
class GeoDataUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val geoDataManager: GeoDataManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = runCatching {
        geoDataManager.refreshForScheduledUpdate()
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
