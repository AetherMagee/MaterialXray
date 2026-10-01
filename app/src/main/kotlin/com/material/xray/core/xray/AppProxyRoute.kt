package com.material.xray.core.xray

import com.material.xray.model.ServerConfig

/**
 * An app group's route to its own outbound. Under TPROXY the group has an inbound of its own,
 * tagged [inboundTag]; under TUN it shares the one TUN inbound and is told apart by the source
 * address its [routeIndex] assigns, see [TunManager.appRouteSourceAddresses].
 */
data class AppProxyRoute(
    val inboundTag: String,
    val routeIndex: Int,
    val outboundTag: String,
    val server: ServerConfig,
    val applyRoutingRules: Boolean = false,
)
