package com.material.xray.core.data.repository

import com.material.xray.core.database.entity.ServerEntity
import com.material.xray.core.database.entity.SubscriptionEntity

data class ProviderRoutingAvailability(
    val providerName: String?,
    val appRoutingProvided: Boolean,
    val xrayRoutingProvided: Boolean,
)

fun SubscriptionEntity.providerRoutingAvailability() = ProviderRoutingAvailability(
    providerName = name.trim().takeIf { it.isNotEmpty() },
    appRoutingProvided = toSubscriptionAppRouting() != null,
    xrayRoutingProvided = toSubscriptionRouting() != null,
)

fun selectedProviderRoutingAvailability(
    selectedServerId: Long,
    servers: List<ServerEntity>,
    subscriptions: List<SubscriptionEntity>,
): ProviderRoutingAvailability? {
    val subscriptionId = servers.firstOrNull { it.id == selectedServerId }?.subscriptionId
    return subscriptions.firstOrNull { it.id == subscriptionId }?.providerRoutingAvailability()
}
