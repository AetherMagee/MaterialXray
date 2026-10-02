package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherVpnDnsTest {
    private val generator = ConfigGenerator()
    private val tailscale = OtherVpnDns(
        netId = 161,
        servers = listOf("100.100.100.100", "fd7a:115c:a1e0::53"),
        domains = listOf("van-morpho.ts.net"),
    )
    private val server = ServerConfig(
        protocol = Protocol.VLESS,
        name = "Test",
        address = "1.2.3.4",
        port = 443,
        password = "test-uuid",
    )

    @Test
    fun `search domains are normalized and a VPN without a zone yields nothing`() {
        val dns = OtherVpnDns.of(161, listOf("100.100.100.100", "fe80::1%tun1"), "Corp.Example. van-morpho.ts.net corp.example")

        assertEquals(listOf("corp.example", "van-morpho.ts.net"), dns?.domains)
        assertEquals(listOf("100.100.100.100", "fe80::1"), dns?.servers)
        assertNull(OtherVpnDns.of(161, listOf("100.100.100.100"), null))
        assertNull(OtherVpnDns.of(161, listOf("100.100.100.100"), " "))
        assertNull(OtherVpnDns.of(161, emptyList(), "van-morpho.ts.net"))
        assertNull(OtherVpnDns.of(0, listOf("100.100.100.100"), "van-morpho.ts.net"))
    }

    @Test
    fun `a VPN with only IPv6 resolvers is unusable while IPv6 is off`() {
        val ipv6Only = tailscale.copy(servers = listOf("fd7a:115c:a1e0::53"))

        assertNull(ipv6Only.forIpv6(allowIpv6 = false))
        assertEquals(ipv6Only, ipv6Only.forIpv6(allowIpv6 = true))
        assertEquals(listOf("100.100.100.100"), tailscale.forIpv6(allowIpv6 = false)?.servers)
    }

    @Test
    fun `sockets reach the VPN through its own network mark`() {
        assertEquals(0xc00a1, tailscale.socketMark)
    }

    @Test
    fun `the VPN zone resolves through the VPN and nothing else`() {
        val config = generate(otherVpnDns = tailscale)

        val servers = config.getValue("dns").jsonObject.getValue("servers").jsonArray
        val vpnServer = servers.first().jsonObject
        assertEquals("100.100.100.100", vpnServer.getValue("address").jsonPrimitive.content)
        assertEquals(listOf("domain:van-morpho.ts.net"), vpnServer.getValue("domains").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(vpnServer.getValue("skipFallback").jsonPrimitive.boolean)
        // IPv6 is off, so the VPN's IPv6 resolver is left out.
        assertEquals(1, servers.count { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.content == "other-vpn-dns" })

        val outbound = config.getValue("outbounds").jsonArray.map { it.jsonObject }.single { it.tag() == OTHER_VPN_OUTBOUND_TAG }
        assertEquals("freedom", outbound.getValue("protocol").jsonPrimitive.content)
        val sockopt = outbound.getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject
        assertEquals(0xc00a1, sockopt.getValue("mark").jsonPrimitive.int)

        val rules = config.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val vpnRule = rules.indexOfFirst { it["outboundTag"]?.jsonPrimitive?.content == OTHER_VPN_OUTBOUND_TAG }
        assertEquals(listOf("other-vpn-dns"), rules[vpnRule].getValue("inboundTag").jsonArray.map { it.jsonPrimitive.content })
        // The VPN's resolver is a private address, which the LAN rule would otherwise send direct.
        val lanRule = rules.indexOfFirst { it["ip"]?.jsonArray?.any { ip -> ip.jsonPrimitive.content == "geoip:private" } == true }
        assertTrue(vpnRule in 0 until lanRule)
    }

    @Test
    fun `the VPN IPv6 resolver is used when IPv6 is allowed`() {
        val servers = generate(otherVpnDns = tailscale, allowIpv6 = true)
            .getValue("dns").jsonObject.getValue("servers").jsonArray
            .mapNotNull { it as? JsonObject }
            .filter { it["tag"]?.jsonPrimitive?.content == "other-vpn-dns" }

        assertEquals(listOf("100.100.100.100", "fd7a:115c:a1e0::53"), servers.map { it.getValue("address").jsonPrimitive.content })
    }

    @Test
    fun `without another VPN the config is unchanged`() {
        assertEquals(generator.generate(server), generator.generate(server, otherVpnDns = null))
        assertTrue(generate(otherVpnDns = null).getValue("outbounds").jsonArray.none { it.jsonObject.tag() == OTHER_VPN_OUTBOUND_TAG })
    }

    @Test
    fun `raw profiles get the VPN zone unless they bring their own DNS`() {
        val rawBase = """"outbounds":[{"tag":"proxy","protocol":"vless","settings":{}}]"""
        val raw = server.copy(protocol = Protocol.RAW, rawConfigJson = "{$rawBase,\"dns\":{\"servers\":[\"9.9.9.9\"]}}")

        val managed = Json.parseToJsonElement(generator.generate(raw, otherVpnDns = tailscale)).jsonObject
        assertTrue(managed.getValue("outbounds").jsonArray.any { it.jsonObject.tag() == OTHER_VPN_OUTBOUND_TAG })
        assertEquals(
            "other-vpn-dns",
            managed.getValue("dns").jsonObject.getValue("servers").jsonArray.first().jsonObject.getValue("tag").jsonPrimitive.content,
        )

        val profileDns = Json.parseToJsonElement(generator.generate(raw, preferProfileDns = true, otherVpnDns = tailscale)).jsonObject
        assertEquals(Json.parseToJsonElement("""{"servers":["9.9.9.9"]}"""), profileDns.getValue("dns"))
        assertTrue(profileDns.getValue("outbounds").jsonArray.none { it.jsonObject.tag() == OTHER_VPN_OUTBOUND_TAG })
    }

    private fun generate(otherVpnDns: OtherVpnDns?, allowIpv6: Boolean = false): JsonObject = Json.parseToJsonElement(
        generator.generate(server, fwmark = PROTECTED_FROM_VPN_MARK, allowIpv6 = allowIpv6, otherVpnDns = otherVpnDns),
    ).jsonObject

    private fun JsonObject.tag(): String? = this["tag"]?.jsonPrimitive?.content
}
