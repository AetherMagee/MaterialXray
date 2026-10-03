package com.material.xray.data.repository

import com.material.xray.core.app.AppInventorySource
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.toAppBypassEntity
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionAppRoutingMode
import org.koin.core.annotation.Singleton

@Singleton
class SubscriptionAppRoutingRepository(
    private val appBypassDao: AppBypassDao,
    private val subscriptionDao: SubscriptionDao,
    private val appInventory: AppInventorySource,
) {
    suspend fun apply(routing: SubscriptionAppRouting): Boolean {
        val normalized = routing.normalized() ?: return false
        return replaceActiveRouting(normalized)
    }

    suspend fun applyForSubscription(subscriptionId: Long): Boolean {
        val subscription = subscriptionDao.getById(subscriptionId) ?: return false
        val routing = subscription.toSubscriptionAppRouting() ?: return false
        return replaceActiveRouting(routing)
    }

    suspend fun clear(): Boolean = replaceActiveRouting(null)

    private suspend fun replaceActiveRouting(routing: SubscriptionAppRouting?): Boolean {
        val providerAssignments = routing?.let { buildProviderAssignments(it) }.orEmpty()
        // Read and replace in one transaction after the slow package scan, so a user edit written
        // meanwhile is part of the merge instead of being overwritten by a stale snapshot.
        return appBypassDao.replaceAllWith { current ->
            mergeProviderAssignments(current, providerAssignments, routing)
        }
    }

    private suspend fun buildProviderAssignments(routing: SubscriptionAppRouting): List<AppBypassEntity> {
        if (routing.packageNames.isEmpty()) return emptyList()

        // Assignments are materialised against the currently installed snapshot. An inverted list
        // writes explicit rows for every unlisted app, so an app installed afterwards has no row
        // (and keeps the default proxy route) until the next subscription refresh re-applies it.
        return appInventory.loadRoutingSnapshot().apps
            .mapNotNull { app ->
                val mode = routing.assignmentModeFor(app.packageName) ?: return@mapNotNull null
                mode.toRouteAssignment()
                    .toAppBypassEntity(
                        packageName = app.packageName,
                        profileId = app.profileId,
                        uid = app.uid,
                        manual = false,
                    )
            }
    }

    private fun SubscriptionAppRoutingMode.toRouteAssignment(): AppRouteAssignment = when (this) {
        SubscriptionAppRoutingMode.Direct -> AppRouteAssignment(AppRouteMode.Direct)
        SubscriptionAppRoutingMode.DefaultSelected -> AppRouteAssignment(AppRouteMode.DefaultSelected)
        SubscriptionAppRoutingMode.DefaultOutbound -> AppRouteAssignment(AppRouteMode.DefaultOutbound)
    }
}

/**
 * Provider rows replace every non-manual row, while manual rows survive for apps the provider
 * does not claim so user edits outside the provider's list outlive a refresh.
 */
internal fun mergeProviderAssignments(
    current: List<AppBypassEntity>,
    providerAssignments: List<AppBypassEntity>,
    routing: SubscriptionAppRouting?,
): List<AppBypassEntity> {
    val keptManual = current.filter { it.manual && routing?.assignmentModeFor(it.packageName) == null }
    return (providerAssignments + keptManual)
        .sortedWith(compareBy(AppBypassEntity::profileId, AppBypassEntity::packageName))
}
