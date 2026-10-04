package com.material.xray.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.material.xray.core.runtime.AppUpdateChecker
import kotlinx.coroutines.CancellationException
import org.koin.android.annotation.KoinWorker

@KoinWorker
class AppUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val appUpdateChecker: AppUpdateChecker,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        appUpdateChecker.check()
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Result.success()
    }
}
