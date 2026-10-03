package com.material.xray.core.network

import javax.net.SocketFactory
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NetworkLinkProbeTest {

    @Test
    fun `the default network wins when it is physical`() {
        val default = link(isDefault = true, isCellular = true)
        val wifi = link(isValidated = true)

        assertSame(default, listOf(wifi, default).physicalLink())
    }

    @Test
    fun `behind our VPN the validated unmetered network is preferred`() {
        val vpn = link(isDefault = true, isVpn = true, isValidated = true)
        val cellular = link(isValidated = true, isCellular = true)
        val unvalidatedWifi = link()
        val wifi = link(isValidated = true)

        assertSame(wifi, listOf(vpn, unvalidatedWifi, cellular, wifi).physicalLink())
    }

    @Test
    fun `networks without internet or only VPNs leave no physical network`() {
        assertNull(listOf(link(isDefault = true, isVpn = true), link(hasInternet = false)).physicalLink())
        assertNull(emptyList<NetworkLink>().physicalLink())
    }

    private fun link(
        isDefault: Boolean = false,
        hasInternet: Boolean = true,
        isVpn: Boolean = false,
        isValidated: Boolean = false,
        isCellular: Boolean = false,
    ) = NetworkLink(
        isDefault = isDefault,
        hasInternet = hasInternet,
        isVpn = isVpn,
        isValidated = isValidated,
        isCellular = isCellular,
        addresses = emptyList(),
        hasIpv6DefaultRoute = false,
        socketFactory = SocketFactory.getDefault(),
    )
}
