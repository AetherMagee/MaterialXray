package com.material.xray.feature.home

import android.content.Context
import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.data.repository.ProviderRoutingActiveUpdate
import com.material.xray.core.data.repository.ProviderRoutingCoordinator
import com.material.xray.core.data.repository.ServerRepository
import com.material.xray.core.data.repository.ServerSelectionCoordinator
import com.material.xray.core.data.repository.SettingsRepository
import com.material.xray.core.data.repository.SubscriptionRefreshCoordinator
import com.material.xray.core.data.repository.SubscriptionRepository
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.runtime.RoutingChangeManager
import com.material.xray.core.xray.ActiveConfigOverrideStore
import com.material.xray.service.XrayService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Singleton

private const val SERVER_SELECTION_SETTLE_MILLIS = 200L

/** Why a server the user picked cannot be selected until they confirm something. */
enum class ServerSelectionBlocker { HardwareIdRequired, EditedActiveConfig }

/** Selects the server the user picks on the Home screen and connects to it. */
@Singleton
class HomeServerController(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val serverRepo: ServerRepository,
    private val subscriptionRepo: SubscriptionRepository,
    private val subscriptionRefreshCoordinator: SubscriptionRefreshCoordinator,
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
        // A provider may require the hardware ID for its servers. When the user keeps it off,
        // surface the choice instead of silently selecting the server. Re-tapping the already
        // selected server keeps the nudge: that selection then violates the provider policy.
        if (requiresHardwareIdConsent(serverId)) return ServerSelectionBlocker.HardwareIdRequired
        // The edited active config was written against the currently selected server, so
        // moving away from it throws the edit away. Say so before it happens.
        if (serverId != settingsRepo.lastServerId.first() && activeConfigOverrideStore.exists()) {
            return ServerSelectionBlocker.EditedActiveConfig
        }
        return null
    }

    suspend fun enableHardwareId(serverId: Long) {
        settingsRepo.setSubscriptionSendHardwareId(true)
        refreshServersForHwidPolicy(serverId)
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

    // The servers on screen were fetched without the hardware ID header; HWID-gated providers
    // typically hand out a different server set once it is sent, so refetch right away.
    private suspend fun refreshServersForHwidPolicy(serverId: Long) {
        val subscriptionId = serverRepo.getById(serverId)?.subscriptionId ?: return
        val subscription = subscriptionRepo.getById(subscriptionId) ?: return
        try {
            subscriptionRefreshCoordinator.refreshSubscription(subscription.id, subscription.url)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The next scheduled refresh can pick the provider up instead.
        }
    }

    private suspend fun requiresHardwareIdConsent(serverId: Long): Boolean {
        val serverEntity = serverRepo.getById(serverId) ?: return false
        val subscription = subscriptionRepo.getById(serverEntity.subscriptionId) ?: return false
        if (!subscription.requiresHardwareId) return false
        return !settingsRepo.subscriptionSendHardwareId.first()
    }
}
