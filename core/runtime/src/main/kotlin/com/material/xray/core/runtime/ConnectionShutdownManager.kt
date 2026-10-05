package com.material.xray.core.runtime

import android.content.Context
import android.os.SystemClock
import com.material.xray.core.android.locale.localizedString
import com.material.xray.core.common.connection.ConnectionShutdown
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.connection.ConnectionStep
import com.material.xray.core.connection.ConnectionStepExecutor
import com.material.xray.core.model.ConnectionProgress
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.ui.R
import com.material.xray.service.XrayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Singleton

@Singleton(binds = [ConnectionShutdown::class])
class ConnectionShutdownManager(
    private val context: Context,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
) : ConnectionShutdown {
    private val stepExecutor = ConnectionStepExecutor(
        elapsedRealtime = SystemClock::elapsedRealtime,
        log = { message -> log.append(LogSource.APP, message) },
        onProgressStarted = stateCoordinator::beginConnectionProgress,
        onProgressFinished = stateCoordinator::endConnectionProgress,
    )

    override suspend fun disconnectIfRunning() = stepExecutor.execute(
        ConnectionStep("Disconnect Xray for maintenance", ConnectionProgress.StoppingCore) {
            disconnectIfRunningOnce()
        },
    )

    override fun forceDisconnect() {
        XrayService.disconnect(context, force = true)
    }

    private suspend fun disconnectIfRunningOnce() {
        if (!stateCoordinator.state.value.requiresRuntimeDisconnect()) return

        stateCoordinator.markDisconnecting()
        try {
            forceDisconnect()
        } catch (error: IllegalStateException) {
            markStopFailure()
            throw error
        } catch (error: SecurityException) {
            markStopFailure()
            throw error
        }
        val terminalState = withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) {
            stateCoordinator.state.first { !it.requiresRuntimeDisconnect() }
        }
        if (terminalState == null) {
            markStopFailure()
            error("Timed out waiting for the active connection to stop")
        }
        if (terminalState is ConnectionState.Error) {
            error("Could not stop the active connection: ${terminalState.message}")
        }
    }

    private fun markStopFailure() {
        stateCoordinator.markError(context.localizedString(R.string.connection_error_stop_runtime), retryable = false)
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
