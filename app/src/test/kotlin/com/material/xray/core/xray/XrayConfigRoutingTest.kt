package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.ServerConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigRoutingTest {

    @Test
    fun `buildDns uses system fallback and domestic servers for direct domains`() {
        val dns = buildDns(
            servers = "",
            domesticServers = "77.88.8.8, 77.88.8.1",
            routingRules = listOf(directRule()),
            bypassLan = true,
        )

        assertEquals("UseIPv4", dns.getValue("queryStrategy").jsonPrimitive.content)
        assertTrue("tag" !in dns)
        val servers = dns.getValue("servers").jsonArray
        assertEquals("localhost", servers.first().jsonPrimitive.content)
        val domesticServers = servers.drop(1).map { it.jsonObject }
        assertEquals(
            listOf("77.88.8.8", "77.88.8.1"),
            domesticServers.map { it["address"]!!.jsonPrimitive.content },
        )
        domesticServers.forEach { server ->
            val domains = server.getValue("domains").jsonArray.map { it.jsonPrimitive.content }
            assertTrue("geosite:private" in domains)
            assertTrue("domain:example" in domains)
            assertEquals("domestic-dns", server.getValue("tag").jsonPrimitive.content)
        }
    }

    @Test
    fun `buildDns tags configured default servers for routing`() {
        val dns = buildDns(servers = "1.1.1.1, 8.8.8.8, 77.88.8.8, 77.88.8.1")

        assertEquals("default-dns", dns.getValue("tag").jsonPrimitive.content)
        assertEquals(
            listOf("https://1.1.1.1/dns-query", "8.8.8.8", "77.88.8.8", "77.88.8.1"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns preserves explicit DNS endpoint URLs`() {
        val dns = buildDns(servers = "https://dns.quad9.net/dns-query,tcp://9.9.9.9:53")

        assertEquals(
            listOf("https://dns.quad9.net/dns-query", "tcp://9.9.9.9:53"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns omits ipv4-only query strategy and keeps the stored ipv6 resolvers when ipv6 is allowed`() {
        val dns = buildDns(servers = "1.1.1.1,2606:4700:4700::1111", allowIpv6 = true)

        assertTrue("queryStrategy" !in dns)
        assertEquals(
            listOf("https://1.1.1.1/dns-query", "https://[2606:4700:4700::1111]/dns-query"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns drops ipv6 resolvers from both lists when ipv6 is not allowed`() {
        val dns = buildDns(
            servers = "8.8.8.8,2001:4860:4860::8888",
            domesticServers = "77.88.8.8,2a02:6b8::feed:0ff",
            routingRules = listOf(directRule()),
            bypassLan = true,
            allowIpv6 = false,
        )

        val servers = dns.getValue("servers").jsonArray
        assertEquals("8.8.8.8", servers.first().jsonPrimitive.content)
        assertEquals(
            listOf("77.88.8.8"),
            servers.drop(1).map { it.jsonObject.getValue("address").jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns keeps ipv6 resolvers in the domestic list when ipv6 is allowed`() {
        val dns = buildDns(
            servers = "",
            domesticServers = "77.88.8.8,2a02:6b8::feed:0ff",
            routingRules = listOf(directRule()),
            bypassLan = true,
            allowIpv6 = true,
        )

        assertEquals(
            listOf("77.88.8.8", "2a02:6b8::feed:0ff"),
            dns.getValue("servers").jsonArray.drop(1).map { it.jsonObject.getValue("address").jsonPrimitive.content },
        )
    }

    @Test
    fun `an ipv6-only resolver list with ipv6 off leaves no dns tag and no rule addressing it`() {
        // buildDns falls back to the system resolver here, so a rule pointing at default-dns would
        // address a tag that no longer exists.
        val dns = buildDns(
            servers = "2606:4700:4700::1111",
            domesticServers = "2a02:6b8::feed:0ff",
            routingRules = listOf(directRule()),
            bypassLan = true,
            allowIpv6 = false,
        )
        val routing = buildRouting(
            routingRules = listOf(directRule()),
            bypassLan = true,
            dnsServers = "2606:4700:4700::1111",
            domesticDnsServers = "2a02:6b8::feed:0ff",
            allowIpv6 = false,
        )

        assertTrue("tag" !in dns)
        assertEquals("localhost", dns.getValue("servers").jsonArray.single().jsonPrimitive.content)
        val inboundTags = routing.getValue("rules").jsonArray.flatMap { rule ->
            rule.jsonObject["inboundTag"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }
        assertTrue("default-dns" !in inboundTags)
        assertTrue("domestic-dns" !in inboundTags)
    }

    @Test
    fun `an ipv6-only resolver list with ipv6 on is tagged and routed`() {
        val dns = buildDns(
            servers = "2606:4700:4700::1111",
            domesticServers = "2a02:6b8::feed:0ff",
            routingRules = listOf(directRule()),
            bypassLan = true,
            allowIpv6 = true,
        )
        val routing = buildRouting(
            routingRules = listOf(directRule()),
            bypassLan = true,
            dnsServers = "2606:4700:4700::1111",
            domesticDnsServers = "2a02:6b8::feed:0ff",
            allowIpv6 = true,
        )

        assertEquals("default-dns", dns.getValue("tag").jsonPrimitive.content)
        val inboundTags = routing.getValue("rules").jsonArray.flatMap { rule ->
            rule.jsonObject["inboundTag"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }
        assertTrue("default-dns" in inboundTags)
        assertTrue("domestic-dns" in inboundTags)
    }

    @Test
    fun `buildDns uses bracketed DNS-over-HTTPS endpoints for Cloudflare IPv6 resolvers`() {
        val dns = buildDns(servers = "2606:4700:4700::1111, [2a02:6b8::feed:0ff]:53", allowIpv6 = true)

        assertEquals(
            listOf("https://[2606:4700:4700::1111]/dns-query", "[2a02:6b8::feed:0ff]:53"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns preserves explicit ports when switching Cloudflare to DNS-over-TCP`() {
        val dns = buildDns(servers = "1.1.1.1:5353,8.8.8.8:5353")

        assertEquals(
            listOf("tcp://1.1.1.1:5353", "8.8.8.8:5353"),
            dns.getValue("servers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `buildDns includes pre-resolved proxy endpoint hosts`() {
        val dns = buildDns(
            servers = "1.1.1.1",
            bootstrapHosts = mapOf(
                "second.example" to listOf("2001:db8::2"),
                "first.example" to listOf("192.0.2.1", "192.0.2.1"),
            ),
        )

        val hosts = dns.getValue("hosts").jsonObject
        assertEquals(listOf("first.example", "second.example"), hosts.keys.toList())
        assertEquals(listOf("192.0.2.1"), hosts.getValue("first.example").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("2001:db8::2"), hosts.getValue("second.example").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `buildRouting adds forced dns lan custom and apply-rules routes in order`() {
        val appRoutes = listOf(
            appProxyRoute(inboundTag = "app-in-direct", outboundTag = "app-proxy-direct", applyRoutingRules = false),
            appProxyRoute(inboundTag = "app-in-rules", outboundTag = "proxy", applyRoutingRules = true),
        )
        val routing = buildRouting(
            routingRules = listOf(orRule()),
            appProxyRoutes = appRoutes,
            bypassLan = true,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "77.88.8.8",
            syntheticDnsAddress = "10.10.14.2",
            dataInboundTags = tproxyInboundTags(appRoutes),
        )

        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("IPOnDemand", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals(listOf("app-in-direct"), rules[0].array("inboundTag"))
        assertEquals(listOf("10.10.14.2"), rules[0].array("ip"))
        assertEquals("block", rules[0].getValue("outboundTag").jsonPrimitive.content)
        assertEquals(listOf("app-in-direct"), rules[1].array("inboundTag"))
        assertEquals("app-proxy-direct", rules[1].getValue("outboundTag").jsonPrimitive.content)
        assertEquals(listOf("tun-in", "app-in-rules"), rules[2].array("inboundTag"))
        assertEquals("dns-out", rules[2].getValue("outboundTag").jsonPrimitive.content)
        assertEquals(listOf("tun-in", "app-in-rules"), rules[3].array("inboundTag"))
        assertEquals(listOf("10.10.14.2"), rules[3].array("ip"))
        assertEquals("block", rules[3].getValue("outboundTag").jsonPrimitive.content)
        assertEquals(listOf("tun-in", "app-in-rules"), rules[4].array("inboundTag"))
        assertEquals("853", rules[4].getValue("port").jsonPrimitive.content)
        assertEquals("tcp", rules[4].getValue("network").jsonPrimitive.content)
        assertEquals("direct", rules[4].getValue("outboundTag").jsonPrimitive.content)
        assertEquals("default-dns", rules[5].array("inboundTag").single())
        assertEquals("proxy", rules[5].getValue("outboundTag").jsonPrimitive.content)
        assertEquals("domestic-dns", rules[6].array("inboundTag").single())
        assertEquals("geoip:private", rules[7].array("ip").single())
        assertEquals("geosite:private", rules[8].array("domain").single())
        assertEquals(listOf("domain:one", "domain:two"), rules[9].array("domain"))
        assertEquals(listOf("geoip:one"), rules[10].array("ip"))
        assertEquals("443", rules[11].getValue("port").jsonPrimitive.content)
        assertEquals(listOf("tcp", "udp"), rules[12].array("protocol"))
        assertEquals("app-in-rules", rules.last().array("inboundTag").single())
    }

    @Test
    fun `TUN app groups are matched by their source addresses`() {
        val routing = buildRouting(
            routingRules = listOf(RoutingRule(id = "proxy-site", name = "Proxy site", outboundTag = "proxy", domains = listOf("domain:example.com"))),
            appProxyRoutes = listOf(
                appProxyRoute(inboundTag = "app-in-forced-3", outboundTag = "app-proxy-forced-3", applyRoutingRules = false, routeIndex = 1),
                appProxyRoute(inboundTag = "app-in-7", outboundTag = "app-proxy-7", applyRoutingRules = true, routeIndex = 2),
            ),
            syntheticDnsAddress = "10.10.14.2",
        )

        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        assertTrue(rules.none { rule -> rule.array("inboundTag").any { it.startsWith("app-in") } })
        assertEquals(listOf("tun-in"), rules[0].array("inboundTag"))
        assertEquals(listOf("10.0.1.1", "fd10:10:14:1::1"), rules[0].array("source"))
        assertEquals("block", rules[0].getValue("outboundTag").jsonPrimitive.content)
        assertEquals(listOf("10.0.1.1", "fd10:10:14:1::1"), rules[1].array("source"))
        assertEquals("app-proxy-forced-3", rules[1].getValue("outboundTag").jsonPrimitive.content)
        // The forced group shares the TUN inbound but already left above, so DNS stays shared.
        assertEquals(listOf("tun-in"), rules[2].array("inboundTag"))
        assertEquals("dns-out", rules[2].getValue("outboundTag").jsonPrimitive.content)
        assertTrue("source" !in rules[2])

        val scoped = rules.single {
            it["source"] != null && it["domain"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "domain:example.com"
        }
        assertEquals(listOf("10.0.2.1", "fd10:10:14:2::1"), scoped.array("source"))
        assertEquals("app-proxy-7", scoped.getValue("outboundTag").jsonPrimitive.content)
        assertEquals(listOf("10.0.2.1", "fd10:10:14:2::1"), rules.last().array("source"))
        assertEquals("app-proxy-7", rules.last().getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `specific server keeps proxy-targeting rules on its selected outbound`() {
        val routing = buildRouting(
            routingRules = listOf(RoutingRule(id = "proxy-site", name = "Proxy site", outboundTag = "proxy", domains = listOf("domain:example.com"))),
            appProxyRoutes = listOf(
                appProxyRoute(inboundTag = "app-in-7", outboundTag = "app-proxy-7", applyRoutingRules = true),
            ),
            dataInboundTags = listOf("tun-in", "app-in-7"),
        )
        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        val scopedIndex = rules.indexOfFirst {
            it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "app-in-7" &&
                it["domain"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "domain:example.com"
        }
        val globalIndex = rules.indexOfFirst {
            it["inboundTag"] == null &&
                it["domain"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "domain:example.com"
        }
        assertTrue(scopedIndex in 0 until globalIndex)
        assertEquals("app-proxy-7", rules[scopedIndex].getValue("outboundTag").jsonPrimitive.content)
        assertEquals("proxy", rules[globalIndex].getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `forced app inbound does not use direct DNS over TLS rule`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                appProxyRoute(inboundTag = "app-in-forced-7", outboundTag = "app-proxy-forced-7", applyRoutingRules = false),
            ),
            dataInboundTags = listOf("tun-in", "app-in-forced-7"),
        )
        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        val dotRule = rules.first { it["port"]?.jsonPrimitive?.content == "853" }
        assertEquals(listOf("tun-in"), dotRule.array("inboundTag"))
        val forcedIndex = rules.indexOfFirst {
            it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "app-in-forced-7"
        }
        val dotIndex = rules.indexOf(dotRule)
        assertTrue(forcedIndex in 0 until dotIndex)
    }

    @Test
    fun `buildRouting applies provider domain settings`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            domainStrategy = "IPIfNonMatch",
            domainMatcher = "hybrid",
        )

        assertEquals("IPIfNonMatch", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals("hybrid", routing.getValue("domainMatcher").jsonPrimitive.content)
    }

    @Test
    fun `buildRouting omits synthetic DNS peer block outside rootless mode`() {
        val rules = buildRouting(routingRules = emptyList()).getValue("rules").jsonArray.map { it.jsonObject }

        assertTrue(rules.none { it["outboundTag"]?.jsonPrimitive?.content == "block" })
    }

    @Test
    fun `buildRouting routes default DNS through a balancer target`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            dnsServers = "1.1.1.1",
            defaultRouteTarget = XrayRouteTarget.Balancer("balance"),
        )

        val defaultDnsRule = routing.getValue("rules").jsonArray
            .map { it.jsonObject }
            .first { it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "default-dns" }
        assertEquals("balance", defaultDnsRule.getValue("balancerTag").jsonPrimitive.content)
        assertTrue("outboundTag" !in defaultDnsRule)
    }

    @Test
    fun `buildRouting routes apply-rules app fallback through default balancer`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                appProxyRoute(inboundTag = "app-in-default-selected", outboundTag = "proxy", applyRoutingRules = true),
            ),
            defaultRouteTarget = XrayRouteTarget.Balancer("balance"),
            dataInboundTags = listOf("tun-in", "app-in-default-selected"),
        )

        val appFallback = routing.getValue("rules").jsonArray
            .map { it.jsonObject }
            .first { it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "app-in-default-selected" }
        assertEquals("balance", appFallback.getValue("balancerTag").jsonPrimitive.content)
        assertTrue("outboundTag" !in appFallback)
    }

    @Test
    fun `buildRouting omits domestic upstream rule without direct domains`() {
        val routing = buildRouting(
            routingRules = emptyList(),
            bypassLan = false,
            domesticDnsServers = "77.88.8.8",
        )

        val inboundTags = routing.getValue("rules").jsonArray.mapNotNull { rule ->
            rule.jsonObject["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content
        }
        assertTrue("domestic-dns" !in inboundTags)
    }

    private fun JsonObject.array(key: String): List<String> = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun directRule() = RoutingRule(
        id = "direct",
        name = "Direct",
        outboundTag = "direct",
        domains = listOf("domain:example"),
    )

    private fun orRule() = RoutingRule(
        id = "or",
        name = "OR",
        outboundTag = "direct",
        domains = listOf("domain:one", " domain:two "),
        ips = listOf("geoip:one"),
        port = "443",
        protocols = listOf("tcp", "udp"),
        operator = RoutingRuleOperator.OR,
    )

    private fun tproxyInboundTags(routes: List<AppProxyRoute>) = listOf("tun-in") + routes.map { it.inboundTag }

    private fun appProxyRoute(
        inboundTag: String,
        outboundTag: String,
        applyRoutingRules: Boolean,
        routeIndex: Int = 1,
    ) = AppProxyRoute(
        inboundTag = inboundTag,
        routeIndex = routeIndex,
        outboundTag = outboundTag,
        server = ServerConfig(
            protocol = Protocol.VLESS,
            name = inboundTag,
            address = "203.0.113.8",
            port = 443,
            password = "uuid",
        ),
        applyRoutingRules = applyRoutingRules,
    )
}
