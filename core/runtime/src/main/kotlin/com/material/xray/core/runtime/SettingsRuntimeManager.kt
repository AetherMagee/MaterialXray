package com.material.xray.core.runtime

import android.content.Context
import com.material.xray.core.android.launcher.LauncherIconManager
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.connection.GeoDataUpdateBatch
import com.material.xray.core.connection.routing.TproxyCompatibilityDetector
import com.material.xray.core.connection.routing.isConclusive
import com.material.xray.core.data.repository.SettingsRepository
import com.material.xray.core.model.AppUpdateInterval
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.model.LauncherIcon
import com.material.xray.core.model.RootConnectionBackend
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.service.XrayService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Singleton

/** The core's version, null once [loaded] when it could not be read. */
data class XrayCoreVersion(val loaded: Boolean = false, val version: String? = null)

@Singleton
class SettingsRuntimeManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rootShell: RootShell,
    private val geoDataManager: GeoDataManager,
    private val launcherIconManager: LauncherIconManager,
    private val appUpdateScheduler: AppUpdateScheduler,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val tproxyCompatibilityDetector: TproxyCompatibilityDetector,
    private val log: LogBuffer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    private val _xrayCoreVersion = MutableStateFlow(XrayCoreVersion())
    private val diagnosticsMutex = Mutex()
    private var diagnosticsLoaded = false
    private val geoDataUpdateBatch = GeoDataUpdateBatch(::reloadActiveConnectionIfConnected)

    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()

    /** The version of the core the next connection starts, the bundled one or one the user installed. */
    val xrayCoreVersion: StateFlow<XrayCoreVersion> = _xrayCoreVersion.asStateFlow()

    suspend fun setLauncherIcon(icon: LauncherIcon) {
        settingsRepository.setLauncherIcon(icon)
        launcherIconManager.apply(icon)
    }

    suspend fun setAppUpdateChecksEnabled(enabled: Boolean) {
        settingsRepository.setAppUpdateChecksEnabled(enabled)
        appUpdateScheduler.setEnabled(enabled, settingsRepository.appUpdateInterval.first())
    }

    suspend fun setAppUpdateInterval(interval: AppUpdateInterval) {
        settingsRepository.setAppUpdateInterval(interval)
        appUpdateScheduler.setEnabled(true, interval)
    }

    suspend fun setUseRootService(enabled: Boolean): Boolean {
        if (!enabled) {
            settingsRepository.setUseRootService(false)
            reloadActiveConnectionIfConnected()
            return true
        }
        val available = withContext(ioDispatcher) { rootShell.open(RootShell.NetworkNamespace.INIT) }
        _rootAvailable.value = available
        if (!available) return false
        settingsRepository.setUseRootService(true)
        reloadActiveConnectionIfConnected()
        return true
    }

    suspend fun setRootConnectionBackend(backend: RootConnectionBackend) {
        settingsRepository.setRootConnectionBackend(backend)
        reloadActiveConnectionIfConnected()
    }

    /**
     * TPROXY support cannot change while the process lives, so it is probed exactly once here, at
     * application startup, and remembered for the rest of the lifecycle. Connecting, restarting the core
     * and opening the settings screen all read that cached verdict and never re-probe.
     *
     * Only root-mode users are probed, because the probe needs a root shell. Enabling root mode later
     * performs the one check at that point instead.
     */
    suspend fun loadRuntimeDiagnostics() = diagnosticsMutex.withLock {
        if (diagnosticsLoaded) return@withLock
        if (settingsRepository.useRootService.first() && checkRootAvailability()) {
            detectTproxyCompatibility()
        }
        refreshXrayCoreVersion()
        diagnosticsLoaded = true
    }

    val tproxyCompatibility: StateFlow<TproxyCompatibility> get() = tproxyCompatibilityDetector.state

    suspend fun detectTproxyCompatibility(forceRefresh: Boolean = false): TproxyCompatibility {
        log.append(LogSource.APP, "Checking TPROXY IPv4 and IPv6 compatibility...")
        val detection = if (forceRefresh) {
            tproxyCompatibilityDetector.refresh()
        } else {
            tproxyCompatibilityDetector.detect()
        }
        return detection.also { result ->
            when (result) {
                is TproxyCompatibility.Supported -> log.append(
                    LogSource.APP,
                    "TPROXY compatibility: supported (ipv6=${result.ipv6})",
                )
                is TproxyCompatibility.Unsupported -> {
                    val details = result.details
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.take(1_000)
                        ?.takeIf(String::isNotEmpty)
                    log.append(
                        LogSource.APP,
                        "TPROXY compatibility: unsupported reason=${result.reason}" +
                            details?.let { ", details=$it" }.orEmpty(),
                    )
                    demoteTproxyBackend(result)
                }
                TproxyCompatibility.Checking,
                TproxyCompatibility.Unknown,
                -> log.append(LogSource.APP, "TPROXY compatibility check returned $result")
            }
        }
    }

    /**
     * TPROXY is the default backend, so a device that cannot run it would otherwise fail at connect time
     * with the option greyed out and no way back. Moving the stored selection to TUN as soon as the
     * verdict is known keeps the next connect working without the user having to intervene.
     */
    private suspend fun demoteTproxyBackend(result: TproxyCompatibility.Unsupported) {
        if (!shouldDemoteTproxyBackend(result, settingsRepository.rootConnectionBackend.first())) return
        settingsRepository.setRootConnectionBackend(RootConnectionBackend.Tun)
        log.append(LogSource.APP, "TPROXY is unsupported on this device; the root backend was switched to TUN")
    }

    suspend fun updateGeoDataAsset(asset: GeoDataAsset, url: String) = geoDataUpdateBatch.run {
        when (asset) {
            GeoDataAsset.GEOIP -> settingsRepository.setGeoipUrl(url)
            GeoDataAsset.GEOSITE -> settingsRepository.setGeositeUrl(url)
        }
        geoDataManager.refresh(asset)
    }

    suspend fun checkRootAvailability(): Boolean {
        val available = withContext(ioDispatcher) { rootShell.open(RootShell.NetworkNamespace.INIT) }
        _rootAvailable.value = available
        if (!available && settingsRepository.useRootService.first()) {
            settingsRepository.setUseRootService(false)
            reloadActiveConnectionIfConnected()
        }
        return available
    }

    /** Rereads [xrayCoreVersion], which changes when the user switches cores. */
    suspend fun refreshXrayCoreVersion() {
        _xrayCoreVersion.value = XrayCoreVersion(loaded = true, version = withContext(ioDispatcher) { appXrayBinary(context).readVersion() })
    }

    private fun reloadActiveConnectionIfConnected() {
        val state = stateCoordinator.state.value
        if (state is ConnectionState.Connected || state is ConnectionState.ApplyingRoutingChanges) {
            XrayService.reload(context)
        }
    }
}

/**
 * Only a conclusive kernel verdict may rewrite the stored backend. A denied root shell, a timeout or a
 * foreign rule conflict says nothing about whether the device supports TPROXY, so those must leave the
 * user's choice alone and stay retryable.
 */
internal fun shouldDemoteTproxyBackend(
    result: TproxyCompatibility,
    currentBackend: RootConnectionBackend,
): Boolean = result is TproxyCompatibility.Unsupported &&
    result.isConclusive() &&
    currentBackend == RootConnectionBackend.Tproxy
