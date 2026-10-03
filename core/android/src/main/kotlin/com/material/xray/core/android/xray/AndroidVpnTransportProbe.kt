package com.material.xray.core.android.xray

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.material.xray.core.xray.VpnTransportProbe
import org.koin.core.annotation.Singleton

/** [VpnTransportProbe] on `ConnectivityManager`: true when any network has the VPN transport. */
@Singleton(binds = [VpnTransportProbe::class])
class AndroidVpnTransportProbe(private val context: Context) : VpnTransportProbe {
    override fun isVpnActive(): Boolean = runCatching {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)
}
