package com.material.xray.core.runtime

import android.content.Context
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.connection.VPN_SERVICE_INTERFACE_LABEL
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.XrayStateReadResult
import com.material.xray.service.XrayService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Singleton

/** Stops runtime state without opening the database before Android erases app data. */
@Singleton
class RecoveryResetManager(
    private val context: Context,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val rootShell: RootShell,
    private val rootlessOrphanStopper: RootlessOrphanStopper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun prepareForReset(): Boolean {
        if (stateCoordinator.state.value.requiresRuntimeDisconnect()) {
            XrayService.disconnect(context, force = true)
            val settled = withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) {
                stateCoordinator.state.first { !it.requiresRuntimeDisconnect() }
            }
            if (settled != ConnectionState.Disconnected) return false
        }

        return withContext(ioDispatcher) {
            val recorded = appStateFile(context).readResult()
            when (rootCleanupDecision(recorded)) {
                RootCleanupDecision.None -> if (recorded is XrayStateReadResult.Present) {
                    rootlessOrphanStopper.stop(recorded.state.xrayPid)
                } else {
                    true
                }
                RootCleanupDecision.Required -> CleanupManager(context, rootShell).ensureCleanState()
                // Without the saved marks and route tables, fallback cleanup cannot prove that
                // root routing is gone. Keep the app data and require manual recovery.
                RootCleanupDecision.Unsafe -> false
            }
        }
    }

    private companion object {
        const val DISCONNECT_TIMEOUT_MILLIS = 10_000L
    }
}

internal enum class RootCleanupDecision { None, Required, Unsafe }

internal fun rootCleanupDecision(recorded: XrayStateReadResult): RootCleanupDecision = when (recorded) {
    XrayStateReadResult.Absent -> RootCleanupDecision.None
    is XrayStateReadResult.Present -> if (recorded.state.physicalInterface == VPN_SERVICE_INTERFACE_LABEL) {
        RootCleanupDecision.None
    } else {
        RootCleanupDecision.Required
    }
    XrayStateReadResult.Unreadable -> RootCleanupDecision.Unsafe
}
