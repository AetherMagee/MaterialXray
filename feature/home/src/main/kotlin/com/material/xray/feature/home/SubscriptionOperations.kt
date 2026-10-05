package com.material.xray.feature.home

import com.material.xray.core.common.connection.PendingRoutingChange
import com.material.xray.core.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.core.data.repository.SubscriptionRefreshCoordinator
import com.material.xray.core.data.repository.SubscriptionRepository
import com.material.xray.core.data.repository.SubscriptionRoutingRepository
import com.material.xray.core.database.entity.SubscriptionEntity
import com.material.xray.core.model.SubscriptionUserAgentMode
import com.material.xray.core.runtime.RoutingChangeManager
import com.material.xray.core.runtime.SubscriptionUpdateScheduler
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Singleton

/**
 * The subscription changes the Home screen can make. Fetching operations throw [IOException] when
 * the provider cannot be reached, which the screen reports to the user.
 */
@Singleton
class SubscriptionOperations(
    private val subscriptionRepo: SubscriptionRepository,
    private val subscriptionRefreshCoordinator: SubscriptionRefreshCoordinator,
    private val subscriptionUpdateScheduler: SubscriptionUpdateScheduler,
    private val subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
    private val subscriptionRoutingRepository: SubscriptionRoutingRepository,
    private val routingChangeManager: RoutingChangeManager,
) {
    val refreshingSubscriptionIds: StateFlow<Set<Long>> = subscriptionRepo.refreshingSubscriptionIds

    suspend fun add(
        name: String,
        url: String,
        preferJson: Boolean,
        allowInsecureUpdates: Boolean,
        userAgentMode: SubscriptionUserAgentMode,
        customUserAgent: String,
        customHeaders: String,
        confirmHardwareId: suspend () -> Boolean,
    ) {
        subscriptionRepo.add(
            name = name,
            url = url,
            preferJson = preferJson,
            allowInsecureUpdates = allowInsecureUpdates,
            userAgentMode = userAgentMode,
            customUserAgent = customUserAgent,
            customHeaders = customHeaders,
            confirmHardwareId = confirmHardwareId,
        )
    }

    suspend fun addLink(link: String, confirmHardwareId: suspend () -> Boolean) {
        subscriptionRepo.addLink(link, confirmHardwareId)
    }

    suspend fun delete(sub: SubscriptionEntity) {
        subscriptionRefreshCoordinator.deleteSubscription(sub)
    }

    suspend fun update(
        sub: SubscriptionEntity,
        name: String,
        url: String,
        preferJson: Boolean,
        allowInsecureUpdates: Boolean,
        autoUpdateIntervalHours: Int,
        userAgentMode: SubscriptionUserAgentMode,
        customUserAgent: String,
        customHeaders: String,
    ) {
        val normalizedIntervalHours = autoUpdateIntervalHours.coerceAtLeast(0)
        val normalizedCustomUserAgent = customUserAgent.trim().ifBlank { null }
        val normalizedCustomHeaders = customHeaders.trim().ifBlank { null }
        val identityChanged = userAgentMode != SubscriptionUserAgentMode.fromValue(sub.userAgentMode) ||
            normalizedCustomUserAgent != sub.customUserAgent ||
            normalizedCustomHeaders != sub.customHeaders
        val hasSubscriptionChanges = name.trim() != sub.name ||
            url.trim() != sub.url ||
            preferJson != (sub.preferJson ?: true) ||
            allowInsecureUpdates != sub.allowInsecureUpdates ||
            identityChanged
        val hasIntervalChanges = normalizedIntervalHours != sub.autoUpdateIntervalHours

        if (hasSubscriptionChanges) {
            try {
                subscriptionRefreshCoordinator.updateSubscription(
                    sub.copy(
                        preferJson = preferJson,
                        allowInsecureUpdates = allowInsecureUpdates,
                        autoUpdateIntervalHours = normalizedIntervalHours,
                        userAgentMode = userAgentMode.value,
                        customUserAgent = normalizedCustomUserAgent,
                        customHeaders = normalizedCustomHeaders,
                    ),
                    name,
                    url,
                )
            } finally {
                if (hasIntervalChanges) subscriptionUpdateScheduler.enqueueDueCheckNow()
            }
        } else if (hasIntervalChanges) {
            setAutoUpdateInterval(sub.id, normalizedIntervalHours)
        }
    }

    suspend fun refreshAll(): SubscriptionRepository.RefreshBatchResult = subscriptionRefreshCoordinator.refreshAll()

    suspend fun refresh(sub: SubscriptionEntity) {
        subscriptionRefreshCoordinator.refreshSubscription(sub.id, sub.url)
    }

    suspend fun setAutoUpdateInterval(subId: Long, intervalHours: Int) {
        subscriptionRepo.setAutoUpdateInterval(subId, intervalHours)
        subscriptionUpdateScheduler.enqueueDueCheckNow()
    }

    suspend fun setDescriptionHidden(subId: Long, hidden: Boolean) {
        subscriptionRepo.setDescriptionHidden(subId, hidden)
    }

    suspend fun reorder(subscriptionIds: List<Long>) {
        subscriptionRepo.updateSortOrders(subscriptionIds)
    }

    /** Applies a subscription's routing to the user's settings, flagging a live connection to reload. */
    suspend fun applyRouting(data: SubscriptionRoutingData) {
        if (data.appRouting != null && subscriptionAppRoutingRepository.apply(data.appRouting)) {
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
        if (data.routing != null && subscriptionRoutingRepository.apply(data.routing)) {
            routingChangeManager.markPendingChanges(PendingRoutingChange.XRAY_ROUTING)
        }
    }
}
