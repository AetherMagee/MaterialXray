package com.material.xray

import android.app.Application
import android.util.Log
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.locale.initializeAppLocales
import com.material.xray.data.db.DatabaseOpenChecker
import com.material.xray.data.repository.BackupManager
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.di.ApplicationScope
import com.material.xray.service.AppUpdateScheduler
import com.material.xray.service.GeoDataUpdateScheduler
import com.material.xray.service.OemAutostartManager
import com.material.xray.service.StartupDiagnosticsLogger
import com.material.xray.service.SubscriptionUpdateScheduler
import com.material.xray.telemetry.DiagnosticsConsentMirror
import com.material.xray.telemetry.TelemetryReporter
import com.material.xray.telemetry.initializeSentryTelemetry
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class MaterialXrayApp : Application() {

    @Inject lateinit var subscriptionUpdateScheduler: SubscriptionUpdateScheduler

    @Inject lateinit var appUpdateScheduler: AppUpdateScheduler

    @Inject lateinit var geoDataUpdateScheduler: GeoDataUpdateScheduler

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var launcherIconManager: LauncherIconManager

    @Inject lateinit var backupManager: BackupManager

    @Inject lateinit var databaseOpenChecker: DatabaseOpenChecker

    @Inject lateinit var startupDiagnosticsLogger: StartupDiagnosticsLogger

    @Inject lateinit var oemAutostartManager: OemAutostartManager

    @Inject lateinit var telemetryReporter: TelemetryReporter

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        val diagnosticsConsentMirror = DiagnosticsConsentMirror(this)
        // This must precede Hilt's injection in super.onCreate() so opted-in users can report
        // failures while the application graph and eager startup state are being constructed.
        if (diagnosticsConsentMirror.isEnabled()) initializeSentryTelemetry(this)
        // Initialize locales before Hilt constructs UI data when MainActivity starts, so its
        // first locale-dependent server summaries use the selected language on API <= 32.
        initializeAppLocales(this)
        super.onCreate()
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            settingsRepository.diagnosticsEnabled.distinctUntilChanged().collectLatest { enabled ->
                diagnosticsConsentMirror.setEnabled(enabled)
                telemetryReporter.setEnabled(enabled)
            }
        }
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            settingsRepository.geoDataUpdateIntervalHours.distinctUntilChanged().collectLatest(
                geoDataUpdateScheduler::schedulePeriodicRefresh,
            )
        }
        appScope.launch {
            if (settingsRepository.autoConnect.first()) {
                delay(STARTUP_BACKGROUND_WORK_DELAY_SECONDS * 1_000)
                oemAutostartManager.restoreRootGrant()
            }
        }
        appScope.launch {
            if (!databaseOpenChecker.canRead()) return@launch
            runCatching { backupManager.recoverInterruptedRestore() }
                .onFailure { error -> Log.e(LOG_TAG, "Unable to recover interrupted backup restore", error) }
            runCatching { startupDiagnosticsLogger.logIfMissing() }
                .onFailure { error -> Log.e(LOG_TAG, "Unable to record startup diagnostics", error) }
            launcherIconManager.apply(settingsRepository.launcherIcon.first())
            appUpdateScheduler.setEnabled(settingsRepository.appUpdateChecksEnabled.first())
            subscriptionUpdateScheduler.schedulePeriodicUpdates()
            subscriptionUpdateScheduler.enqueueDueCheckNow(STARTUP_BACKGROUND_WORK_DELAY_SECONDS)
        }
    }

    private companion object {
        const val LOG_TAG = "MaterialXrayApp"
        const val STARTUP_BACKGROUND_WORK_DELAY_SECONDS = 30L
    }
}
