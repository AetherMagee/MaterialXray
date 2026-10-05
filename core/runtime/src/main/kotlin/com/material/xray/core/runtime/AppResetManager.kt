package com.material.xray.core.runtime

import android.app.ActivityManager
import android.content.Context
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.model.ConnectionState
import com.material.xray.service.XrayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Singleton

/** Wipes every piece of app data, leaving the app as a fresh install would. */
@Singleton
class AppResetManager(
    private val context: Context,
    private val stateCoordinator: ConnectionStateCoordinator,
) {
    /**
     * Never returns on success: the system kills the app once its data is gone. The connection is
     * stopped first because root mode undoes its routing from files the wipe deletes.
     */
    suspend fun reset() {
        if (stateCoordinator.state.value.requiresRuntimeDisconnect()) {
            XrayService.disconnect(context, force = true)
            checkNotNull(
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) {
                    stateCoordinator.state.first { !it.requiresRuntimeDisconnect() }
                },
            ) { "Timed out waiting for the active connection to stop" }
        }
        check(context.getSystemService(ActivityManager::class.java).clearApplicationUserData()) {
            "The system refused to clear the app data"
        }
    }

    private companion object {
        const val DISCONNECT_TIMEOUT_MILLIS = 10_000L
    }
}

internal fun ConnectionState.requiresRuntimeDisconnect(): Boolean = when (this) {
    ConnectionState.Connecting,
    ConnectionState.ApplyingRoutingChanges,
    ConnectionState.UpdatingRoutingData,
    is ConnectionState.Connected,
    ConnectionState.Disconnecting,
    -> true

    ConnectionState.Disconnected,
    is ConnectionState.Error,
    is ConnectionState.InterfaceBusy,
    is ConnectionState.RestartRequired,
    -> false
}
