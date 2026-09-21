package com.material.xray.core.xray

import com.material.xray.model.XrayRuntimeSettings
import kotlinx.serialization.json.JsonObject

sealed interface XrayInbound {
    val tag: String

    data class Tun(
        val name: String,
        override val tag: String,
        val mtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
    ) : XrayInbound

    data class Tproxy(
        val port: Int,
        override val tag: String,
        val allowIpv6: Boolean,
        val acceptNonLoopback: Boolean = false,
    ) : XrayInbound

    /** Loopback-only, authenticated HTTP proxy used by short-lived helper cores. */
    data class Http(
        val port: Int,
        override val tag: String,
        val username: String,
        val password: String,
    ) : XrayInbound {
        override fun toString(): String = "Http(port=$port, tag=$tag, username=$username, password=***)"
    }
}

internal fun XrayInbound.toJson(): JsonObject = when (this) {
    is XrayInbound.Tun -> buildTunInbound(name, tag, mtu)
    is XrayInbound.Tproxy -> buildTproxyInbound(port, tag, allowIpv6, acceptNonLoopback)
    is XrayInbound.Http -> buildHttpInbound(port, tag, username, password)
}
