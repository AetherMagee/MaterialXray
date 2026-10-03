package com.material.xray.core.xray

import com.material.xray.model.ServerConfig

/**
 * An app group's route to its own outbound. Under TPROXY the group has an inbound of its own,
 * tagged [inboundTag]; under TUN it shares the one TUN inbound and is told apart by the source
 * address its [routeIndex] assigns, see [appRouteSourceAddresses].
 */
data class AppProxyRoute(
    val inboundTag: String,
    val routeIndex: Int,
    val outboundTag: String,
    val server: ServerConfig,
    val applyRoutingRules: Boolean = false,
)

fun appTunAddressCidr(index: Int): String = "10.0.${index.coerceIn(1, 254)}.1/30"

fun appTunIpv6AddressCidr(index: Int): String = "fd10:10:14:${index.coerceIn(1, 254).toString(16)}::1/64"

/** The source addresses an app group's traffic carries into the TUN, one per family. */
fun appRouteSourceAddresses(index: Int): List<String> = listOf(
    appTunAddressCidr(index).substringBefore('/'),
    appTunIpv6AddressCidr(index).substringBefore('/'),
)
