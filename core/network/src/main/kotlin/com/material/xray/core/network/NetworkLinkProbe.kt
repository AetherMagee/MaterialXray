package com.material.xray.core.network

import java.net.InetAddress
import javax.net.SocketFactory

/**
 * What [Ipv6Detector] needs to know about one of the device's networks. On Android,
 * `:core:android` binds `AndroidNetworkLinkProbe` (`ConnectivityManager`).
 */
data class NetworkLink(
    /** The platform's default network; while our VPN is up that is the VPN itself. */
    val isDefault: Boolean,
    val hasInternet: Boolean,
    val isVpn: Boolean,
    /** The platform checked that this network actually reaches the internet. */
    val isValidated: Boolean,
    val isCellular: Boolean,
    /** The addresses assigned to the network's interface. */
    val addresses: List<InetAddress>,
    val hasIpv6DefaultRoute: Boolean,
    /** Opens sockets bound to this network, so they bypass any VPN. */
    val socketFactory: SocketFactory,
)

/** Lists the device's networks. */
fun interface NetworkLinkProbe {
    fun links(): List<NetworkLink>
}

/**
 * The network sessions run over: the default one when it is a physical network with internet
 * access, otherwise (while our VPN is the default) the physical network the platform would prefer.
 */
internal fun List<NetworkLink>.physicalLink(): NetworkLink? = firstOrNull { it.isDefault && it.isPhysicalInternet }
    ?: filter { it.isPhysicalInternet }.maxByOrNull { it.preference }

private val NetworkLink.isPhysicalInternet: Boolean
    get() = hasInternet && !isVpn

/** Android's own pick: validated first, then unmetered over cellular. */
private val NetworkLink.preference: Int
    get() = (if (isValidated) 2 else 0) + (if (isCellular) 0 else 1)
