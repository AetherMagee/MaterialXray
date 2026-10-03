package com.material.xray

import android.app.Application
import android.util.Log
import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.locale.initializeAppLocales
import com.material.xray.core.network.Ipv6Detector
import com.material.xray.core.xray.ProviderGeoDataManager
import com.material.xray.data.db.DatabaseOpenChecker
import com.material.xray.data.repository.BackupManager
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.Ipv6Mode
import com.material.xray.service.AppUpdateScheduler
import com.material.xray.service.GeoDataUpdateScheduler
import com.material.xray.service.OemAutostartManager
import com.material.xray.service.StartupDiagnosticsLogger
import com.material.xray.service.SubscriptionUpdateScheduler
import com.material.xray.telemetry.DiagnosticsConsentMirror
import com.material.xray.telemetry.TelemetryReporter
import com.material.xray.telemetry.initializeSentryTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.annotation.KoinApplication
import org.koin.core.qualifier.named
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class MaterialXrayApp : Application() {

    private val subscriptionUpdateScheduler: SubscriptionUpdateScheduler by inject()

    private val appUpdateScheduler: AppUpdateScheduler by inject()

    private val geoDataUpdateScheduler: GeoDataUpdateScheduler by inject()

    private val settingsRepository: SettingsRepository by inject()

    private val launcherIconManager: LauncherIconManager by inject()

    private val backupManager: BackupManager by inject()

    private val databaseOpenChecker: DatabaseOpenChecker by inject()

    private val startupDiagnosticsLogger: StartupDiagnosticsLogger by inject()

    private val oemAutostartManager: OemAutostartManager by inject()

    private val telemetryReporter: TelemetryReporter by inject()

    private val providerGeoDataManager: ProviderGeoDataManager by inject()

    private val serverRepository: ServerRepository by inject()

    private val ipv6Detector: Ipv6Detector by inject()

    private val appScope: CoroutineScope by inject(named<ApplicationScope>())

    override fun onCreate() {
        val diagnosticsConsentMirror = DiagnosticsConsentMirror(this)
        // This must precede starting Koin so opted-in users can report failures while the
        // application graph and eager startup state are being constructed.
        if (diagnosticsConsentMirror.isEnabled()) initializeSentryTelemetry(this)
        // Initialize locales before Koin constructs UI data when MainActivity starts, so its
        // first locale-dependent server summaries use the selected language on API <= 32.
        initializeAppLocales(this)
        super.onCreate()
        startKoin<MaterialXrayApp> {
            androidContext(this@MaterialXrayApp)
            workManagerFactory()
        }
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
            runCatching {
                if (settingsRepository.geoDataUpdateIntervalHours.first() != 0) {
                    geoDataUpdateScheduler.enqueueInitialRefresh()
                }
            }
                .onFailure { error -> Log.e(LOG_TAG, "Unable to schedule initial geodata refresh", error) }
        }
        appScope.launch { providerGeoDataManager.keepUpToDate() }
        appScope.launch {
            // Settle IPv6 for the selected server before the user connects, so Auto can start with it.
            if (settingsRepository.ipv6Mode.first() != Ipv6Mode.Auto || !databaseOpenChecker.canRead()) return@launch
            val server = serverRepository.getById(settingsRepository.lastServerId.first()) ?: return@launch
            runCatching { ipv6Detector.check(serverRepository.parseConfig(server)) }
                .onFailure { error -> Log.e(LOG_TAG, "Unable to check IPv6", error) }
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
            appUpdateScheduler.setEnabled(
                settingsRepository.appUpdateChecksEnabled.first(),
                settingsRepository.appUpdateInterval.first(),
            )
            subscriptionUpdateScheduler.schedulePeriodicUpdates()
            subscriptionUpdateScheduler.enqueueDueCheckNow(STARTUP_BACKGROUND_WORK_DELAY_SECONDS)
        }
    }

    private companion object {
        const val LOG_TAG = "MaterialXrayApp"
        const val STARTUP_BACKGROUND_WORK_DELAY_SECONDS = 30L
    }
}
