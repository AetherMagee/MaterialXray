package com.material.xray.core.model

import kotlinx.serialization.Serializable

/** How root TPROXY shares the device with another app's VPN. */
@Serializable
enum class OtherVpnMode(val persistedValue: String) {
    /** MXray keeps the internet; the other VPN keeps its own networks and the apps MXray does not proxy. */
    AutoRouting("auto"),

    /** Apps the other VPN covers reach its routes through it, and its own traffic goes through MXray. */
    TunnelInTunnel("tunnel"),

    /** MXray steps aside while the other VPN claims the whole internet, and otherwise auto-routes. */
    StandDown("stand_down"),
    ;

    companion object {
        val default = AutoRouting

        fun fromValue(value: String?): OtherVpnMode = entries.firstOrNull { it.persistedValue == value } ?: default
    }
}
