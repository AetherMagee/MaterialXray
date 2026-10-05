package com.material.xray.feature.home

import android.content.Context
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.data.repository.ProviderRoutingActiveUpdate
import com.material.xray.core.data.repository.ProviderRoutingCoordinator
import com.material.xray.core.data.repository.ServerRepository
import com.material.xray.core.data.repository.ServerSelectionCoordinator
import com.material.xray.core.data.repository.SettingsRepository
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.runtime.RoutingChangeManager
import com.material.xray.core.xray.ActiveConfigOverrideStore
import com.material.xray.service.XrayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Singleton

private const val SERVER_SELECTION_SETTLE_MILLIS = 200L

/** Why a server the user picked cannot be selected until they confirm something. */
enum class ServerSelectionBlocker { EditedActiveConfig, }

/** Selects the server the user picks on the Home screen and connects to it. */
@Singleton
class HomeServerController(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val serverRepo: ServerRepository,
    private val serverSelectionCoordinator: ServerSelectionCoordinator,
    private val providerRoutingCoordinator: ProviderRoutingCoordinator,
    private val activeConfigOverrideStore: ActiveConfigOverrideStore,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
    private val routingChangeManager: RoutingChangeManager,
) {
    fun connect(server: ServerConfig) {
        // The new connection is built from the current settings, so nothing is left pending.
        routingChangeManager.clearPendingChanges()
        XrayService.connect(context, server)
    }

    /** What the user has to confirm before [apply] may select [serverId], or null if nothing. */
    suspend fun blocker(serverId: Long): ServerSelectionBlocker? {
        // The edited active config was written against the currently selected server, so
        // moving away from it throws the edit away. Say so before it happens.
        if (serverId != settingsRepo.lastServerId.first() && activeConfigOverrideStore.exists()) {
            return ServerSelectionBlocker.EditedActiveConfig
        }
        return null
    }

    suspend fun discardEditedActiveConfig() {
        activeConfigOverrideStore.clear()
    }

    suspend fun apply(serverId: Long) {
        val selectionChanged = serverSelectionCoordinator.withSelectionLock {
            if (serverId == settingsRepo.lastServerId.first()) return@withSelectionLock false
            val serverEntity = serverRepo.getById(serverId) ?: return@withSelectionLock false
            runCatching { serverRepo.parseConfig(serverEntity) }.getOrNull() ?: return@withSelectionLock false
            settingsRepo.setLastServerId(serverId)
            true
        }
        if (!selectionChanged) return

        delay(SERVER_SELECTION_SETTLE_MILLIS)
        serverSelectionCoordinator.withSelectionLock {
            if (serverId != settingsRepo.lastServerId.first()) return@withSelectionLock
            providerRoutingCoordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER)

            val state = connectionStateCoordinator.state.value
            if (state is ConnectionState.Connected ||
                state is ConnectionState.ApplyingRoutingChanges ||
                state is ConnectionState.Error
            ) {
                routingChangeManager.clearPendingChanges()
                XrayService.switchServer(context)
            }
        }
    }
}
