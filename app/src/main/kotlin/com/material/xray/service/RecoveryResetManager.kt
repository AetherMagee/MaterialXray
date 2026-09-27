package com.material.xray.service

import android.content.Context
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.CleanupManager
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.XrayStateReadResult
import com.material.xray.model.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Stops runtime state without opening the database before Android erases app data. */
@Singleton
class RecoveryResetManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val rootShell: RootShell,
    private val rootlessOrphanStopper: RootlessOrphanStopper,
) {
    suspend fun prepareForReset(): Boolean {
        if (stateCoordinator.state.value.requiresRuntimeDisconnect()) {
            XrayService.disconnect(context, force = true)
            val settled = withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) {
                stateCoordinator.state.first { !it.requiresRuntimeDisconnect() }
            }
            if (settled != ConnectionState.Disconnected) return false
        }

        return withContext(Dispatchers.IO) {
            val recorded = StateFile(context).readResult()
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
