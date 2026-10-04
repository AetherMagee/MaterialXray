package com.material.xray.core.xray

import com.material.xray.core.model.Protocol
import com.material.xray.core.model.RoutingRule
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.model.XrayLogLevel
import com.material.xray.core.model.XrayOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawConfigTunInjectorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val injector = RawConfigTunInjector(json)

    @Test
    fun `provider DNS keeps bootstrap addresses for every balancer endpoint`() {
        val rawJson = """
            {
              "dns":{"servers":["https://1.1.1.1/dns-query"],"queryStrategy":"UseIP"},
              "outbounds":[
                {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"one.example"}]}},
                {"tag":"proxy-2","protocol":"vless","settings":{"vnext":[{"address":"two.example"}]}}
              ],
              "routing":{
                "rules":[{"network":"tcp,udp","balancerTag":"pool"}],
                "balancers":[{"tag":"pool","selector":["proxy"],"strategy":{"type":"leastLoad"}}]
              },
              "burstObservatory":{"subjectSelector":["proxy"]}
            }
        """.trimIndent()
        val server = ServerConfig(
            name = "Pool",
            protocol = Protocol.RAW,
            address = "",
            port = 0,
            password = "",
            rawConfigJson = rawJson,
            bootstrapDnsHosts = mapOf(
                "one.example" to listOf("192.0.2.1", "192.0.2.1"),
                "two.example" to listOf("192.0.2.2", "192.0.2.3"),
            ),
        )

        val root = json.parseToJsonElement(ConfigGenerator().generate(server, preferProfileDns = true)).jsonObject
        val original = json.parseToJsonElement(rawJson).jsonObject
        val dns = root.getValue("dns").jsonObject
        val hosts = dns.getValue("hosts").jsonObject
        assertEquals(listOf("192.0.2.1"), hosts.getValue("one.example").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("192.0.2.2", "192.0.2.3"), hosts.getValue("two.example").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(original.getValue("dns"), JsonObject(dns - "hosts"))
        assertEquals(
            original.getValue("routing").jsonObject.getValue("balancers"),
            root.getValue("routing").jsonObject.getValue("balancers"),
        )
        assertEquals(original.getValue("burstObservatory"), root.getValue("burstObservatory"))
    }

    @Test
    fun `profile DNS retains resolver options and routing without MX upstream overrides`() {
        val rawJson = """
            {
              "dns": {
                "tag":"default-dns",
                "hosts":{"proxy.example":"192.0.2.10"},
                "queryStrategy":"UseIP",
                "disableCache":true,
                "disableFallback":true,
                "servers":[
                  "1.1.1.1",
                  {"address":"https://dns.example/dns-query","domains":["domain:example"],
                   "tag":"domestic-dns","skipFallback":true,"expectIPs":["192.0.2.0/24"]}
                ]
              },
              "outbounds":[
                {"tag":"proxy","protocol":"vless","settings":{}},
                {"tag":"resolver-proxy","protocol":"vless","settings":{}},
                {"tag":"dns-out","protocol":"dns","settings":{"nonIPQuery":"drop"}}
              ],
              "routing":{
                "rules":[
                  {"inboundTag":["default-dns","domestic-dns"],"outboundTag":"resolver-proxy"},
                  {"ip":["1.1.1.1"],"outboundTag":"resolver-proxy"},
                  {"port":"853","outboundTag":"proxy"},
                  {"network":"tcp,udp","outboundTag":"proxy"}
                ]
              }
            }
        """.trimIndent()
        val raw = json.parseToJsonElement(rawJson).jsonObject
        val result = injector.inject(
            rawJson = rawJson,
            tunName = "xray0",
            fwmark = 255,
            dnsServers = "8.8.8.8",
            domesticDnsServers = "223.5.5.5",
            preferProfileDns = true,
            syntheticDnsAddress = "10.10.14.2",
            bootstrapDnsHosts = mapOf("proxy.example" to listOf("192.0.2.20")),
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = true,
            routingRules = listOf(RoutingRule("all", "All direct", "direct")),
            appProxyRoutes = emptyList(),
            physicalInterface = "wlan0",
        )
        val root = json.parseToJsonElement(result).jsonObject
        assertEquals(raw.getValue("dns"), root.getValue("dns"))
        val rules = root.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val rawRules = raw.getValue("routing").jsonObject.getValue("rules").jsonArray
        assertEquals(rawRules.toList(), rules.takeLast(rawRules.size))
        val generatedRules = rules.dropLast(rawRules.size)
        assertTrue(generatedRules.all { it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "tun-in" })
        assertTrue(generatedRules.none { it["port"]?.jsonPrimitive?.content == "853" })
        assertEquals("53", rules.first().getValue("port").jsonPrimitive.content)
        assertEquals("dns-out", rules.first().getValue("outboundTag").jsonPrimitive.content)
        assertEquals("block", rules[1].getValue("outboundTag").jsonPrimitive.content)
        assertEquals("10.10.14.2", rules[1].getValue("ip").jsonArray.single().jsonPrimitive.content)
        val dnsOutbound = root.getValue("outbounds").jsonArray.single {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "dns-out"
        }.jsonObject
        assertEquals("drop", dnsOutbound.getValue("settings").jsonObject.getValue("nonIPQuery").jsonPrimitive.content)
        assertEquals(
            "255",
            dnsOutbound.getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject.getValue("mark").jsonPrimitive.content,
        )
    }

    @Test
    fun `inject replaces provider inbounds with managed tun inbounds`() {
        val result = injector.inject(
            rawJson = """
                {
                  "inbounds": [
                    {"tag":"socks-in","listen":"127.0.0.1","port":10808,"protocol":"socks"},
                    {"tag":"http-in","listen":"127.0.0.1","port":10809,"protocol":"http"}
                  ],
                  "outbounds": [
                    {"protocol":"vless","settings":{}},
                    {"tag":"legacy-block","protocol":"blackhole"}
                  ]
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 255,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            syntheticDnsAddress = "10.10.14.2",
            logLevel = XrayLogLevel.Debug,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = true,
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                AppProxyRoute(
                    inboundTag = "app-in-7",
                    routeIndex = 1,
                    outboundTag = "app-proxy-7",
                    server = server("App route"),
                ),
            ),
            physicalInterface = "wlan0",
            xrayApiEndpoint = XrayApiEndpoint.LoopbackTcp(48_123),
            xrayBufferSizeKiB = 1024,
            tunMtu = 1400,
        )

        val root = json.parseToJsonElement(result).jsonObject
        assertEquals(
            listOf("StatsService", "RoutingService"),
            root.getValue("api").jsonObject.getValue("services").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("127.0.0.1:48123", root.getValue("api").jsonObject.getValue("listen").jsonPrimitive.content)
        val inbounds = root.getValue("inbounds").jsonArray
        val inbound = inbounds.single().jsonObject
        assertEquals("tun-in", inbound["tag"]!!.jsonPrimitive.content)
        assertEquals("tun", inbound["protocol"]!!.jsonPrimitive.content)
        assertTrue("listen" !in inbound)
        assertEquals("0", inbound["port"]!!.jsonPrimitive.content)
        assertEquals("1400", inbound["settings"]!!.jsonObject["MTU"]!!.jsonPrimitive.content)
        assertEquals("xray0", inbound["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(
            1024,
            root.getValue("policy").jsonObject
                .getValue("levels").jsonObject
                .getValue("0").jsonObject
                .getValue("bufferSize").jsonPrimitive.content.toInt(),
        )

        val outbounds = root.getValue("outbounds").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("proxy", "app-proxy-7", "direct", "block", "dns-out", "legacy-block"),
            outbounds.map { it["tag"]!!.jsonPrimitive.content },
        )
        val proxySockopt = outbounds.first().getValue("streamSettings").jsonObject.getValue("sockopt").jsonObject
        assertEquals(255, proxySockopt.getValue("mark").jsonPrimitive.content.toInt())
        assertEquals("wlan0", proxySockopt.getValue("interface").jsonPrimitive.content)
        assertEquals("debug", root.getValue("log").jsonObject.getValue("loglevel").jsonPrimitive.content)
        val syntheticDnsRule = root.getValue("routing").jsonObject.getValue("rules").jsonArray
            .map { it.jsonObject }
            .first { rule ->
                rule["ip"]?.jsonArray?.any { it.jsonPrimitive.content == "10.10.14.2" } == true
            }
        assertEquals("block", syntheticDnsRule.getValue("outboundTag").jsonPrimitive.content)
        assertTrue("port" !in syntheticDnsRule)
        assertTrue("network" !in syntheticDnsRule)
    }

    @Test
    fun `inject replaces provider tun inbound with app managed tun`() {
        val result = injector.inject(
            rawJson = """
                {
                  "inbounds": [{"tag":"tun-in","protocol":"tun","settings":{"name":"existing0"}}],
                  "outbounds": [{"tag":"proxy","protocol":"vless","settings":{}}]
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = emptyList(),
            physicalInterface = null,
        )

        val inbounds = json.parseToJsonElement(result).jsonObject.getValue("inbounds").jsonArray
        assertEquals(1, inbounds.size)
        assertEquals("tun-in", inbounds.single().jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("xray0", inbounds.single().jsonObject["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `inject routes default DNS through case-variant proxy tags`() {
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [{"tag":"Proxy","protocol":"vless","settings":{}}]
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = emptyList(),
            physicalInterface = null,
        )

        val root = json.parseToJsonElement(result).jsonObject
        val outboundTags = root.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
        assertEquals(1, outboundTags.count { it.equals("proxy", ignoreCase = true) })
        assertEquals("Proxy", outboundTags.first())
        val defaultDnsRule = root.getValue("routing").jsonObject.getValue("rules").jsonArray.first {
            it.jsonObject["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "default-dns"
        }
        assertEquals("Proxy", defaultDnsRule.jsonObject.getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `inject preserves a provider proxy tag and its routing references`() {
        val providerTag = "usual-proxy-40"
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [
                    {"tag":"$providerTag","protocol":"vless","settings":{}},
                    {"tag":"direct","protocol":"freedom"},
                    {"tag":"block","protocol":"blackhole"}
                  ],
                  "routing": {
                    "rules": [
                      {"domain":["example.org"],"outboundTag":"direct"},
                      {"network":"tcp,udp","outboundTag":"$providerTag"}
                    ]
                  }
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                AppProxyRoute(
                    inboundTag = "app-in-default-selected",
                    routeIndex = 1,
                    outboundTag = "proxy",
                    server = server("Default selected"),
                    applyRoutingRules = true,
                ),
                AppProxyRoute(
                    inboundTag = "app-in-always-proxied",
                    routeIndex = 2,
                    outboundTag = "proxy",
                    server = server("Default selected"),
                ),
            ),
            physicalInterface = null,
        )

        val root = json.parseToJsonElement(result).jsonObject
        val outboundTags = root.getValue("outbounds").jsonArray
            .map { it.jsonObject.getValue("tag").jsonPrimitive.content }
        assertEquals(listOf(providerTag, "direct", "block", "dns-out"), outboundTags)

        val rules = root.getValue("routing").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val appFallback = rules.first {
            it.sourceAddresses() == appRouteSourceAddresses(1)
        }
        assertEquals(providerTag, appFallback.getValue("outboundTag").jsonPrimitive.content)
        val forcedRule = rules.first {
            it.sourceAddresses() == appRouteSourceAddresses(2)
        }
        assertEquals(providerTag, forcedRule.getValue("outboundTag").jsonPrimitive.content)
        assertTrue(rules.any { it["network"]?.jsonPrimitive?.content == "tcp,udp" && it["outboundTag"]?.jsonPrimitive?.content == providerTag })
    }

    @Test
    fun `inject preserves provider tags used by balancer and observatory selectors`() {
        val rawJson = """
            {
              "outbounds": [
                {"tag":"usual-proxy-01","protocol":"vless","settings":{}},
                {"tag":"usual-proxy-02","protocol":"vless","settings":{}}
              ],
              "routing": {
                "rules": [{"network":"tcp,udp","balancerTag":"balance"}],
                "balancers": [
                  {"tag":"balance","selector":["usual-"],"fallbackTag":"usual-proxy-01"}
                ]
              },
              "burstObservatory":{"subjectSelector":["usual-"]}
            }
        """.trimIndent()
        val original = json.parseToJsonElement(rawJson).jsonObject

        val result = injector.inject(
            rawJson = rawJson,
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = emptyList(),
            physicalInterface = null,
        )

        val root = json.parseToJsonElement(result).jsonObject
        assertEquals(
            listOf("usual-proxy-01", "direct", "block", "dns-out", "usual-proxy-02"),
            root.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content },
        )
        assertEquals(
            original.getValue("routing").jsonObject.getValue("balancers"),
            root.getValue("routing").jsonObject.getValue("balancers"),
        )
        assertEquals(original.getValue("burstObservatory"), root.getValue("burstObservatory"))
    }

    @Test
    fun `inject preserves raw routing for multi-outbound profiles`() {
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [
                    {"tag":"proxy","protocol":"vless","settings":{}},
                    {"tag":"proxy-2","protocol":"vless","settings":{}},
                    {"tag":"direct","protocol":"freedom"},
                    {"tag":"block","protocol":"blackhole"}
                  ],
                  "routing": {
                    "domainStrategy": "IPIfNonMatch",
                    "rules": [
                      {"ip":["1.1.1.1"],"network":"tcp","port":"443","outboundTag":"proxy-2"},
                      {"network":"tcp,udp","balancerTag":"balance"}
                    ],
                    "balancers": [{"tag":"balance","selector":["proxy"],"strategy":{"type":"leastLoad"}}]
                  },
                  "burstObservatory": {"subjectSelector":["proxy"]}
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 255,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = emptyList(),
            physicalInterface = null,
        )

        val root = json.parseToJsonElement(result).jsonObject
        val routing = root.getValue("routing").jsonObject
        assertEquals(
            listOf("StatsService", "RoutingService", "ObservatoryService"),
            root.getValue("api").jsonObject.getValue("services").jsonArray.map { it.jsonPrimitive.content },
        )
        val rules = routing.getValue("rules").jsonArray.map { it.jsonObject }
        assertEquals("IPIfNonMatch", routing.getValue("domainStrategy").jsonPrimitive.content)
        assertEquals("dns-out", rules.first().getValue("outboundTag").jsonPrimitive.content)
        assertEquals("default-dns", root.getValue("dns").jsonObject.getValue("tag").jsonPrimitive.content)
        val defaultDnsRule = rules.first {
            it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "default-dns"
        }
        assertEquals("balance", defaultDnsRule.getValue("balancerTag").jsonPrimitive.content)
        assertTrue("outboundTag" !in defaultDnsRule)
        val rawEndpointRuleIndex = rules.indexOfFirst {
            it["ip"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "1.1.1.1"
        }
        val rawBalancerRuleIndex = rules.indexOfFirst {
            it["balancerTag"]?.jsonPrimitive?.content == "balance" && "inboundTag" !in it
        }
        val defaultDnsRuleIndex = rules.indexOf(defaultDnsRule)
        assertTrue("Raw endpoint route must be preserved", rawEndpointRuleIndex >= 0)
        assertTrue("Raw balancer route must be preserved", rawBalancerRuleIndex >= 0)
        assertTrue(
            "The app-managed DNS route must run before raw rules that could capture resolver traffic",
            defaultDnsRuleIndex < rawEndpointRuleIndex,
        )
        assertTrue(
            "The app-managed DNS route must run before raw catch-all rules",
            defaultDnsRuleIndex < rawBalancerRuleIndex,
        )
        assertEquals("balance", routing.getValue("balancers").jsonArray.single().jsonObject.getValue("tag").jsonPrimitive.content)
        assertEquals(
            listOf("proxy", "direct", "block", "dns-out", "proxy-2"),
            root.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content },
        )
        assertEquals(
            "proxy",
            root.getValue("burstObservatory").jsonObject
                .getValue("subjectSelector").jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun `inject routes default DNS through raw catch-all outbound`() {
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [
                    {"tag":"proxy","protocol":"vless","settings":{}},
                    {"tag":"proxy-2","protocol":"vless","settings":{}}
                  ],
                  "routing": {
                    "rules": [
                      {"domain":["example.com"],"outboundTag":"proxy"},
                      {"network":"tcp,udp","outboundTag":"proxy-2"}
                    ]
                  }
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = emptyList(),
            physicalInterface = null,
        )

        val defaultDnsRule = json.parseToJsonElement(result).jsonObject
            .getValue("routing").jsonObject
            .getValue("rules").jsonArray
            .map { it.jsonObject }
            .first { it["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "default-dns" }
        assertEquals("proxy-2", defaultDnsRule.getValue("outboundTag").jsonPrimitive.content)
        assertTrue("balancerTag" !in defaultDnsRule)
    }

    @Test
    fun `inject routes default-selected app fallback through raw catch-all balancer`() {
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [{"tag":"proxy","protocol":"vless","settings":{}}],
                  "routing": {
                    "rules": [{"network":"tcp,udp","balancerTag":"balance"}],
                    "balancers": [{"tag":"balance","selector":["proxy"]}]
                  }
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                AppProxyRoute(
                    inboundTag = "app-in-default-selected",
                    routeIndex = 1,
                    outboundTag = "proxy",
                    server = server("Default selected"),
                    applyRoutingRules = true,
                ),
            ),
            physicalInterface = null,
        )

        val appFallback = json.parseToJsonElement(result).jsonObject
            .getValue("routing").jsonObject
            .getValue("rules").jsonArray
            .map { it.jsonObject }
            .first { it.sourceAddresses() == appRouteSourceAddresses(1) }
        assertEquals("balance", appFallback.getValue("balancerTag").jsonPrimitive.content)
        assertTrue("outboundTag" !in appFallback)
    }

    @Test
    fun `inject fails when raw config has no proxy-capable outbound`() {
        val failure = runCatching {
            injector.inject(
                rawJson = """
                    {
                      "outbounds": [
                        {"tag":"direct","protocol":"freedom"},
                        {"tag":"dns-out","protocol":"dns"},
                        {"tag":"block","protocol":"blackhole"}
                      ]
                    }
                """.trimIndent(),
                tunName = "xray0",
                fwmark = 1,
                dnsServers = "",
                domesticDnsServers = "",
                logLevel = XrayLogLevel.Error,
                defaultOutbound = XrayOutbound.Proxy,
                bypassLan = false,
                routingRules = emptyList(),
                appProxyRoutes = emptyList(),
                physicalInterface = null,
            )
        }

        assertTrue(failure.isFailure)
        assertEquals("Raw JSON config has no proxy outbound", failure.exceptionOrNull()?.message)
    }

    @Test
    fun `specific server fallback follows raw profile rules and keeps proxy matches on that server`() {
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [{"tag":"proxy","protocol":"vless","settings":{}}],
                  "routing": {"rules": [
                    {"domain":["domain:direct.example"],"outboundTag":"direct"},
                    {"domain":["domain:proxy.example"],"outboundTag":"proxy"}
                  ]}
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                AppProxyRoute(
                    inboundTag = "app-in-7",
                    routeIndex = 1,
                    outboundTag = "app-proxy-7",
                    server = server("Specific"),
                    applyRoutingRules = true,
                ),
            ),
            physicalInterface = null,
        )
        val rules = json.parseToJsonElement(result).jsonObject.getValue("routing").jsonObject
            .getValue("rules").jsonArray.map { it.jsonObject }
        val directIndex = rules.indexOfFirst {
            it["domain"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "domain:direct.example"
        }
        val scopedProxyIndex = rules.indexOfFirst {
            it["domain"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "domain:proxy.example" &&
                it.sourceAddresses() == appRouteSourceAddresses(1)
        }
        val fallbackIndex = rules.indexOfLast {
            it.sourceAddresses() == appRouteSourceAddresses(1) && it["domain"] == null
        }
        assertTrue(directIndex in 0 until scopedProxyIndex)
        assertTrue(scopedProxyIndex in 0 until fallbackIndex)
        assertEquals("app-proxy-7", rules[scopedProxyIndex].getValue("outboundTag").jsonPrimitive.content)
    }

    @Test
    fun `raw proxy rule with its own source is not scoped to a source matched app group`() {
        val result = injector.inject(
            rawJson = """
                {
                  "outbounds": [{"tag":"proxy","protocol":"vless","settings":{}}],
                  "routing": {"rules": [
                    {"source":["192.0.2.0/24"],"domain":["domain:proxy.example"],"outboundTag":"proxy"}
                  ]}
                }
            """.trimIndent(),
            tunName = "xray0",
            fwmark = 1,
            dnsServers = "",
            domesticDnsServers = "",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = false,
            routingRules = emptyList(),
            appProxyRoutes = listOf(
                AppProxyRoute(
                    inboundTag = "app-in-7",
                    routeIndex = 1,
                    outboundTag = "app-proxy-7",
                    server = server("Specific"),
                    applyRoutingRules = true,
                ),
            ),
            physicalInterface = null,
        )
        val rules = json.parseToJsonElement(result).jsonObject.getValue("routing").jsonObject
            .getValue("rules").jsonArray.map { it.jsonObject }

        val proxyRules = rules.filter { it["domain"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "domain:proxy.example" }
        assertEquals(listOf("192.0.2.0/24"), proxyRules.single().sourceAddresses())
    }

    private fun server(name: String) = ServerConfig(
        protocol = Protocol.VLESS,
        name = name,
        address = "203.0.113.8",
        port = 443,
        password = "uuid",
        transport = ServerConfig.Transport(type = "tcp"),
        security = ServerConfig.Security(type = "none"),
    )

    private fun JsonObject.sourceAddresses(): List<String> = this["source"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
}
