package com.material.xray.ui.home

import com.material.xray.core.common.connection.PendingRoutingChange
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.data.repository.SubscriptionRefreshCoordinator
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.data.repository.SubscriptionRoutingRepository
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.service.RoutingChangeManager
import com.material.xray.service.SubscriptionUpdateScheduler
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
    ) {
        subscriptionRepo.add(
            name = name,
            url = url,
            preferJson = preferJson,
            allowInsecureUpdates = allowInsecureUpdates,
            userAgentMode = userAgentMode,
            customUserAgent = customUserAgent,
            customHeaders = customHeaders,
        )
    }

    suspend fun addLink(link: String) {
        subscriptionRepo.addLink(link)
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
