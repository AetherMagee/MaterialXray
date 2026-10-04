package com.material.xray.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.material.xray.core.data.repository.SubscriptionRefreshCoordinator
import org.koin.android.annotation.KoinWorker

@KoinWorker
class SubscriptionUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val subscriptionRefreshCoordinator: SubscriptionRefreshCoordinator,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = runCatching {
        subscriptionRefreshCoordinator.refreshDueSubscriptions()
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )
}
