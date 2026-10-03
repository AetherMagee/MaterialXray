package com.material.xray.core.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.material.xray.core.network.NetworkLink
import com.material.xray.core.network.NetworkLinkProbe
import java.net.Inet6Address
import org.koin.core.annotation.Singleton

/** [NetworkLinkProbe] on `ConnectivityManager`: the active network first, then every other one. */
@Singleton(binds = [NetworkLinkProbe::class])
class AndroidNetworkLinkProbe(private val context: Context) : NetworkLinkProbe {
    override fun links(): List<NetworkLink> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val active = connectivityManager.activeNetwork

        @Suppress("DEPRECATION")
        val networks = (listOfNotNull(active) + connectivityManager.allNetworks).distinct()
        return networks.map { network -> connectivityManager.link(network, isDefault = network == active) }
    }

    private fun ConnectivityManager.link(network: Network, isDefault: Boolean): NetworkLink {
        val capabilities = getNetworkCapabilities(network)
        val linkProperties = getLinkProperties(network)
        return NetworkLink(
            isDefault = isDefault,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            isVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            addresses = linkProperties?.linkAddresses.orEmpty().map { it.address },
            hasIpv6DefaultRoute = linkProperties?.hasIpv6DefaultRoute() == true,
            socketFactory = network.socketFactory,
        )
    }

    private fun LinkProperties.hasIpv6DefaultRoute(): Boolean = routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
}
