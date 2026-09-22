package com.material.xray.data.repository

import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.model.SubscriptionRouting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class SubscriptionRoutingRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val subscriptionDao: SubscriptionDao,
) {
    suspend fun apply(routing: SubscriptionRouting): Boolean {
        val target = routing.normalized()
        val current = SubscriptionRouting(
            rules = settingsRepository.customRoutingRules.first(),
            domainStrategy = settingsRepository.customRoutingDomainStrategy.first(),
            domainMatcher = settingsRepository.customRoutingDomainMatcher.first(),
            fallbackOutboundTag = settingsRepository.customRoutingFallbackOutbound.first()?.tag,
        ).normalized()
        if (current == target) return false
        settingsRepository.setCustomRouting(target)
        return true
    }

    suspend fun applyForSubscription(subscriptionId: Long): Boolean {
        val subscription = subscriptionDao.getById(subscriptionId) ?: return false
        val routing = subscription.toSubscriptionRouting() ?: return false
        return replaceActiveRouting(routing)
    }

    suspend fun clear(): Boolean = replaceActiveRouting(null)

    private suspend fun replaceActiveRouting(routing: SubscriptionRouting?): Boolean {
        val target = routing?.normalized() ?: SubscriptionRouting(emptyList())
        val current = SubscriptionRouting(
            rules = settingsRepository.subscriptionRoutingRules.first(),
            domainStrategy = settingsRepository.subscriptionRoutingDomainStrategy.first(),
            domainMatcher = settingsRepository.subscriptionRoutingDomainMatcher.first(),
            fallbackOutboundTag = settingsRepository.subscriptionRoutingFallbackOutbound.first()?.tag,
        ).normalized()
        if (current == target) return false
        settingsRepository.setSubscriptionRouting(target)
        return true
    }
}
