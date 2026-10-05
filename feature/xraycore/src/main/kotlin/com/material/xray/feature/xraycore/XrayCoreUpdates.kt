package com.material.xray.feature.xraycore

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.material.xray.core.android.locale.localizedString
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.model.AppUpdateInterval
import com.material.xray.core.ui.R
import com.material.xray.core.xraycore.XrayCoreManager
import com.material.xray.core.xraycore.XrayCoreUpdateAction
import com.material.xray.core.xraycore.XrayCoreUpdateSettingsStore
import com.material.xray.core.xraycore.normalizeXrayVersion
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.koin.android.annotation.KoinWorker
import org.koin.core.annotation.Singleton

/** Looks for a newer upstream core and, as the user chose, announces it or switches to it. */
@Singleton
class XrayCoreUpdater(
    private val manager: XrayCoreManager,
    private val settingsStore: XrayCoreUpdateSettingsStore,
    private val switcher: XrayCoreSwitcher,
    private val notifier: XrayCoreUpdateNotifier,
    private val log: LogBuffer,
) {
    suspend fun check() {
        val settings = settingsStore.settings.value
        if (!settings.periodicChecks || !manager.canDownload) return
        val release = manager.findUpdate() ?: return
        when (settings.action) {
            XrayCoreUpdateAction.Notify -> if (settings.notifiedTag != release.tag && notifier.showAvailable(release.tag)) {
                settingsStore.update { it.copy(notifiedTag = release.tag) }
            }
            XrayCoreUpdateAction.Install -> {
                val core = manager.installAndWait(release) ?: return
                switcher.switchTo(core.id)
                log.append(LogSource.APP, "Updated the Xray core to ${core.version}")
                removePreviousAutoInstall(settings.autoInstalledId, core.id)
                notifier.showInstalled(core.version)
            }
        }
    }

    /** Each update would otherwise leave the core it replaced behind; cores the user chose stay. */
    private suspend fun removePreviousAutoInstall(previousId: String?, currentId: String) {
        settingsStore.update { it.copy(autoInstalledId = currentId) }
        if (previousId == null || previousId == currentId || manager.state.value.selectedId == previousId) return
        if (manager.state.value.installed.none { it.id == previousId }) return
        try {
            manager.delete(previousId)
        } catch (error: IllegalStateException) {
            log.append(LogSource.APP, "Could not remove the replaced Xray core: ${error.message}")
        }
    }
}

/** Runs [XrayCoreUpdater] at the chosen interval while automatic checks are on. */
@Singleton
class XrayCoreUpdateScheduler(private val context: Context) {
    fun setEnabled(enabled: Boolean, interval: AppUpdateInterval) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<XrayCoreUpdateWorker>(interval.hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "xray_core_update_check"
    }
}

@KoinWorker
class XrayCoreUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val updater: XrayCoreUpdater,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = try {
        updater.check()
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        // The next periodic run tries again; failures are already in the app log.
        Result.success()
    }
}

@Singleton
class XrayCoreUpdateNotifier(private val context: Context) {
    fun showAvailable(tag: String): Boolean = show(
        title = context.localizedString(R.string.notification_xray_core_update_title),
        text = context.localizedString(R.string.notification_xray_core_update_text, "v${normalizeXrayVersion(tag)}"),
    )

    fun showInstalled(version: String): Boolean = show(
        title = context.localizedString(R.string.notification_xray_core_updated_title),
        text = context.localizedString(R.string.notification_xray_core_updated_text, "v${normalizeXrayVersion(version)}"),
    )

    private fun show(title: String, text: String): Boolean {
        if (!canPostNotifications()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.localizedString(R.string.notification_channel_xray_core_updates),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            if (notificationManager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        val openIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_default_monochrome)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun canPostNotifications(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private companion object {
        const val CHANNEL_ID = "xray_core_updates"
        const val NOTIFICATION_ID = 4
    }
}
