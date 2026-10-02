package com.material.xray.core.xray

import com.material.xray.model.ProfileRoutingOverrideEngine
import com.material.xray.model.RoutingRule
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ConfigGenerator {
    private val json = Json

    fun generate(
        server: ServerConfig,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "https://1.1.1.1/dns-query,https://1.0.0.1/dns-query",
        domesticDnsServers: String = "",
        preferProfileDns: Boolean = false,
        syntheticDnsAddress: String? = null,
        logLevel: XrayLogLevel = XrayLogLevel.default,
        defaultOutbound: XrayOutbound = XrayOutbound.default,
        bypassLan: Boolean = true,
        allowIpv6: Boolean = false,
        routingRules: List<RoutingRule> = emptyList(),
        routingDomainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
        routingDomainMatcher: String? = null,
        routingFallbackOutbound: XrayOutbound? = null,
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        physicalInterface: String? = null,
        xrayApiEndpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
        xrayBufferSizeKiB: Int = XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
        tunMtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
        inbounds: List<XrayInbound>? = null,
        otherVpnDns: OtherVpnDns? = null,
    ): String {
        val effectiveInbounds = inbounds ?: listOf(XrayInbound.Tun(tunName, TUN_INBOUND_TAG, tunMtu))
        val dataInboundTags = effectiveInbounds.map { it.tag }
        val bootstrapDnsHosts = bootstrapDnsHosts(server, appProxyRoutes)
        if (server.rawConfigJson.isNotBlank()) {
            return injectTunIntoRawConfig(
                rawJson = ProfileRoutingOverrideEngine.apply(
                    server.rawConfigJson,
                    server.profileRoutingOverrides,
                ),
                tunName = tunName,
                fwmark = fwmark,
                dnsServers = dnsServers,
                domesticDnsServers = domesticDnsServers,
                preferProfileDns = preferProfileDns,
                syntheticDnsAddress = syntheticDnsAddress,
                bootstrapDnsHosts = bootstrapDnsHosts,
                logLevel = logLevel,
                defaultOutbound = defaultOutbound,
                bypassLan = bypassLan,
                allowIpv6 = allowIpv6,
                routingRules = routingRules,
                routingDomainStrategy = routingDomainStrategy,
                routingDomainMatcher = routingDomainMatcher,
                routingFallbackOutbound = routingFallbackOutbound,
                appProxyRoutes = appProxyRoutes,
                physicalInterface = physicalInterface,
                xrayApiEndpoint = xrayApiEndpoint,
                xrayBufferSizeKiB = xrayBufferSizeKiB,
                tunMtu = tunMtu,
                inbounds = effectiveInbounds,
                otherVpnDns = otherVpnDns,
            )
        }

        val config = buildJsonObject {
            put("log", buildLogConfig(logLevel))
            put("dns", buildDns(dnsServers, domesticDnsServers, bootstrapDnsHosts, routingRules, bypassLan, allowIpv6, otherVpnDns))
            put(
                "inbounds",
                buildJsonArray {
                    effectiveInbounds.forEach { add(it.toJson()) }
                },
            )
            put(
                "outbounds",
                buildJsonArray {
                    buildCoreOutbounds(
                        defaultOutbound = routingFallbackOutbound ?: defaultOutbound,
                        proxyOutbound = buildProxyOutbound(server, fwmark, physicalInterface, tag = "proxy", allowIpv6 = allowIpv6),
                        directOutbound = buildDirectOutbound(fwmark, physicalInterface, allowIpv6),
                        dnsOutbound = buildDnsOutbound(fwmark, physicalInterface, allowIpv6),
                        blockOutbound = buildBlockOutbound(),
                        appProxyOutbounds = appProxyRoutes.filter { it.outboundTag != "proxy" }.map { route ->
                            buildProxyOutbound(route.server, fwmark, physicalInterface, tag = route.outboundTag, allowIpv6 = allowIpv6)
                        },
                    ).forEach { add(it) }
                    otherVpnDns?.let { add(buildOtherVpnOutbound(it, allowIpv6)) }
                },
            )
            put("api", buildStatsApi(xrayApiEndpoint))
            put("stats", buildStatsConfig())
            put("policy", buildStatsPolicy(xrayBufferSizeKiB))
            put(
                "routing",
                buildRouting(
                    routingRules = routingRules,
                    appProxyRoutes = appProxyRoutes,
                    bypassLan = bypassLan,
                    dnsServers = dnsServers,
                    domesticDnsServers = domesticDnsServers,
                    syntheticDnsAddress = syntheticDnsAddress,
                    domainStrategy = routingDomainStrategy,
                    domainMatcher = routingDomainMatcher,
                    allowIpv6 = allowIpv6,
                    dataInboundTags = dataInboundTags,
                    otherVpnDns = otherVpnDns,
                ),
            )
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    fun injectTunIntoRawConfig(
        rawJson: String,
        tunName: String = "xray0",
        fwmark: Int = 255,
        dnsServers: String = "https://1.1.1.1/dns-query,https://1.0.0.1/dns-query",
        domesticDnsServers: String = "",
        preferProfileDns: Boolean = false,
        syntheticDnsAddress: String? = null,
        bootstrapDnsHosts: Map<String, List<String>> = emptyMap(),
        logLevel: XrayLogLevel = XrayLogLevel.default,
        defaultOutbound: XrayOutbound = XrayOutbound.default,
        bypassLan: Boolean = true,
        allowIpv6: Boolean = false,
        routingRules: List<RoutingRule> = emptyList(),
        routingDomainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
        routingDomainMatcher: String? = null,
        routingFallbackOutbound: XrayOutbound? = null,
        appProxyRoutes: List<AppProxyRoute> = emptyList(),
        physicalInterface: String? = null,
        xrayApiEndpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
        xrayBufferSizeKiB: Int = XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
        tunMtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
        inbounds: List<XrayInbound>? = null,
        otherVpnDns: OtherVpnDns? = null,
    ): String = RawConfigTunInjector(json).inject(
        rawJson = rawJson,
        tunName = tunName,
        fwmark = fwmark,
        dnsServers = dnsServers,
        domesticDnsServers = domesticDnsServers,
        preferProfileDns = preferProfileDns,
        syntheticDnsAddress = syntheticDnsAddress,
        bootstrapDnsHosts = bootstrapDnsHosts,
        logLevel = logLevel,
        defaultOutbound = routingFallbackOutbound ?: defaultOutbound,
        bypassLan = bypassLan,
        allowIpv6 = allowIpv6,
        routingRules = routingRules,
        routingDomainStrategy = routingDomainStrategy,
        routingDomainMatcher = routingDomainMatcher,
        appProxyRoutes = appProxyRoutes,
        physicalInterface = physicalInterface,
        xrayApiEndpoint = xrayApiEndpoint,
        xrayBufferSizeKiB = xrayBufferSizeKiB,
        tunMtu = tunMtu,
        inbounds = inbounds,
        otherVpnDns = otherVpnDns,
    )

    /**
     * Rewrites the keys the app owns per connect into a hand-edited config, leaving every other key
     * exactly as the user wrote it.
     *
     * A hand-edited config is captured from one connect but replayed on all later ones, and the
     * runtime identity is not stable between them: the control API endpoint is a fresh abstract
     * socket name or loopback port every time, and the TUN name is whatever was free. Replaying the
     * captured values would point the stats and routing clients at an endpoint nothing is listening
     * on, and would leave the port the core actually opens outside the loopback firewall rule that
     * was installed for the fresh one.
     *
     * Returns null when [configJson] is not a JSON object, so the caller can fall back rather than
     * hand the core a document it cannot patch.
     */
    fun applyRuntimeIdentity(
        configJson: String,
        tunName: String,
        xrayApiEndpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
        tunMtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
        inbounds: List<XrayInbound>? = null,
        outboundMark: Int? = null,
        clearOutboundInterfaces: Boolean = false,
    ): String? {
        val original = runCatching { json.parseToJsonElement(configJson) as? JsonObject }.getOrNull() ?: return null
        val effectiveInbounds = inbounds ?: listOf(XrayInbound.Tun(tunName, TUN_INBOUND_TAG, tunMtu))

        val patched = original.toMutableMap()
        patched["inbounds"] = buildJsonArray { effectiveInbounds.forEach { add(it.toJson()) } }
        if (outboundMark != null || clearOutboundInterfaces) {
            (original["outbounds"] as? JsonArray)?.let { outbounds ->
                patched["outbounds"] = patchSockoptRouting(
                    outbounds = outbounds,
                    mark = outboundMark,
                    clearInterfaces = clearOutboundInterfaces,
                )
            }
        }
        patched["api"] = buildStatsApi(
            endpoint = xrayApiEndpoint,
            enableObservatory = original["observatory"] is JsonObject || original["burstObservatory"] is JsonObject,
        )
        return json.encodeToString(JsonObject.serializer(), JsonObject(patched))
    }

    // A mark is set on every outbound, even ones without sockopt, since any of them may dial out.
    private fun patchSockoptRouting(
        outbounds: JsonArray,
        mark: Int?,
        clearInterfaces: Boolean,
    ): JsonArray = buildJsonArray {
        outbounds.forEach { outbound ->
            val outboundObject = outbound as? JsonObject
            val streamSettings = outboundObject?.get("streamSettings") as? JsonObject
            val sockopt = streamSettings?.get("sockopt") as? JsonObject
            val needsPatch = mark != null || (clearInterfaces && sockopt?.containsKey("interface") == true)
            if (outboundObject == null || !needsPatch) {
                add(outbound)
            } else {
                val patchedSockopt = sockopt.orEmpty().toMutableMap().apply {
                    if (mark != null) put("mark", JsonPrimitive(mark))
                    if (clearInterfaces) remove("interface")
                }
                val patchedStream = streamSettings.orEmpty().toMutableMap().apply {
                    put("sockopt", JsonObject(patchedSockopt))
                }
                add(JsonObject(outboundObject.toMutableMap().apply { put("streamSettings", JsonObject(patchedStream)) }))
            }
        }
    }

    /** Whether a raw profile's own DNS replaces the generated one, which leaves another VPN's zone out. */
    fun usesProfileDns(server: ServerConfig, preferProfileDns: Boolean): Boolean = preferProfileDns &&
        server.rawConfigJson.isNotBlank() &&
        runCatching { json.parseToJsonElement(server.rawConfigJson).jsonObject["dns"] is JsonObject }.getOrDefault(false)

    private fun bootstrapDnsHosts(
        server: ServerConfig,
        appProxyRoutes: List<AppProxyRoute>,
    ): Map<String, List<String>> = (listOf(server) + appProxyRoutes.map { it.server })
        .flatMap { it.bootstrapDnsHosts.entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, addressLists) -> addressLists.flatten().distinct() }
}
