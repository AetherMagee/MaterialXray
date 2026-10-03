package com.material.xray.core.xray

/**
 * Whether any network on the device is a VPN. On Android, `:core:android` binds
 * `AndroidVpnTransportProbe` (`ConnectivityManager`).
 */
fun interface VpnTransportProbe {
    fun isVpnActive(): Boolean
}
