package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class RawConfigTunInjector(
    private val json: Json,
) {
    fun inject(
        rawJson: String,
        tunName: String,
        fwmark: Int,
        dnsServers: String,
        domesticDnsServers: String,
        preferProfileDns: Boolean = false,
        syntheticDnsAddress: String? = null,
        bootstrapDnsHosts: Map<String, List<String>> = emptyMap(),
        logLevel: XrayLogLevel,
        defaultOutbound: XrayOutbound,
        bypassLan: Boolean,
        allowIpv6: Boolean = false,
        routingRules: List<RoutingRule>,
        routingDomainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
        routingDomainMatcher: String? = null,
        appProxyRoutes: List<AppProxyRoute>,
        physicalInterface: String?,
        xrayApiEndpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
        xrayBufferSizeKiB: Int = XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
        tunMtu: Int = XrayRuntimeSettings.DEFAULT_TUN_MTU,
        inbounds: List<XrayInbound>? = null,
    ): String {
        val original = Json.parseToJsonElement(rawJson).jsonObject.toMutableMap()
        val profileDns = (original["dns"] as? JsonObject)?.takeIf { preferProfileDns }
            ?.withBootstrapDnsHosts(bootstrapDnsHosts)
        val effectiveInbounds = inbounds ?: buildList {
            add(XrayInbound.Tun(tunName, "tun-in", tunMtu))
            appProxyRoutes.forEach { route -> add(XrayInbound.Tun(route.tunName, route.inboundTag, tunMtu)) }
        }
        original["inbounds"] = JsonArray(effectiveInbounds.map(XrayInbound::toJson))

        val normalizedOutbounds = normalizeOutbounds(
            original["outbounds"] as? JsonArray,
            fwmark,
            physicalInterface,
            allowIpv6,
        )
        val proxyOutbound = normalizedOutbounds.proxy
        val proxyOutboundTag = requireNotNull(proxyOutbound["tag"]?.jsonPrimitive?.contentOrNull)
        val rawRouting = original["routing"] as? JsonObject
        val defaultRouteTarget = rawRouting.defaultTcpRouteTarget()
            ?: XrayRouteTarget.Outbound(proxyOutboundTag)

        val appProxyOutbounds = appProxyRoutes.filterNot { it.applyRoutingRules }.map { route ->
            buildProxyOutbound(route.server, fwmark, physicalInterface, route.outboundTag, allowIpv6)
        }
        val managedOutboundTags = managedOutboundTags(appProxyRoutes)
        val unmanagedOutbounds = normalizedOutbounds.all.filterIndexed { index, outbound ->
            val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull
            index != normalizedOutbounds.proxyIndex &&
                (tag == null || managedOutboundTags.none { managedTag -> managedTag.equals(tag, ignoreCase = true) })
        }

        original["outbounds"] = JsonArray(
            buildCoreOutbounds(
                defaultOutbound = defaultOutbound,
                proxyOutbound = proxyOutbound,
                directOutbound = buildDirectOutbound(fwmark, physicalInterface, allowIpv6),
                dnsOutbound = normalizedOutbounds.all.firstOrNull { outbound ->
                    profileDns != null &&
                        outbound["tag"]?.jsonPrimitive?.contentOrNull == "dns-out" &&
                        outbound["protocol"]?.jsonPrimitive?.contentOrNull == "dns"
                } ?: buildDnsOutbound(fwmark, physicalInterface, allowIpv6),
                blockOutbound = buildBlockOutbound(),
                appProxyOutbounds = appProxyOutbounds,
            ) + unmanagedOutbounds,
        )
        original["log"] = buildLogConfig(logLevel)
        original["dns"] = profileDns ?: buildDns(
            dnsServers,
            domesticDnsServers,
            bootstrapDnsHosts,
            routingRules,
            bypassLan,
            allowIpv6,
        )
        original["api"] = buildStatsApi(
            endpoint = xrayApiEndpoint,
            enableObservatory = original["observatory"] is JsonObject || original["burstObservatory"] is JsonObject,
        )
        original["stats"] = buildStatsConfig()
        original["policy"] = buildStatsPolicy(xrayBufferSizeKiB)
        original["routing"] = mergeRouting(
            generated = buildRouting(
                routingRules = routingRules,
                appProxyRoutes = appProxyRoutes,
                bypassLan = bypassLan,
                dnsServers = dnsServers,
                domesticDnsServers = domesticDnsServers,
                syntheticDnsAddress = syntheticDnsAddress,
                domainStrategy = routingDomainStrategy,
                domainMatcher = routingDomainMatcher,
                defaultRouteTarget = defaultRouteTarget,
                allowIpv6 = allowIpv6,
                dataInboundTags = effectiveInbounds.map { it.tag },
                manageDns = profileDns == null,
            ),
            raw = rawRouting,
            profileDnsInboundTags = effectiveInbounds.map { it.tag }.takeIf { profileDns != null },
        )

        return json.encodeToString(JsonObject.serializer(), JsonObject(original))
    }

    private fun mergeRouting(generated: JsonObject, raw: JsonObject?, profileDnsInboundTags: List<String>?): JsonObject {
        // Keep MX app rules from capturing the profile resolver's own requests. DNS interception
        // still feeds Xray's DNS outbound, including the synthetic resolver used by Android VPN.
        val generatedRules = (generated["rules"] as? JsonArray).orEmpty().map { rule ->
            if (profileDnsInboundTags == null || "inboundTag" in rule.jsonObject) {
                rule
            } else {
                JsonObject(rule.jsonObject + ("inboundTag" to JsonArray(profileDnsInboundTags.map(::JsonPrimitive))))
            }
        }
        val rawRules = (raw?.get("rules") as? JsonArray).orEmpty()
        return JsonObject(
            generated.toMutableMap().apply {
                if (raw != null) putAll(raw)
                put("rules", JsonArray(generatedRules + rawRules))
            },
        )
    }

    private fun normalizeOutbounds(
        outbounds: JsonArray?,
        fwmark: Int,
        physicalInterface: String?,
        allowIpv6: Boolean,
    ): NormalizedOutbounds {
        val existingOutbounds = outbounds?.mapNotNull { it as? JsonObject }.orEmpty()
        val proxyIndex = proxyCandidateIndex(existingOutbounds)
        if (proxyIndex < 0) error("Raw JSON config has no proxy outbound")

        val normalized = existingOutbounds.mapIndexed { index, outbound ->
            val obj = outbound.toMutableMap()
            val stream = (obj["streamSettings"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            stream["sockopt"] = buildSockopt(fwmark, physicalInterface, allowIpv6)
            obj["streamSettings"] = JsonObject(stream)
            if (index == proxyIndex && obj["tag"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                obj["tag"] = JsonPrimitive("proxy")
            }
            JsonObject(obj)
        }
        return NormalizedOutbounds(normalized, proxyIndex)
    }

    private fun proxyCandidateIndex(outbounds: List<JsonObject>): Int {
        val canonicalIndex = outbounds.indexOfFirst { outbound ->
            outbound["tag"]?.jsonPrimitive?.contentOrNull.equals("proxy", ignoreCase = true)
        }
        if (canonicalIndex >= 0) return canonicalIndex

        return outbounds.indexOfFirst { outbound ->
            outbound["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase() !in SPECIAL_OUTBOUND_PROTOCOLS
        }
    }

    private fun managedOutboundTags(appProxyRoutes: List<AppProxyRoute>) = buildSet {
        add("proxy")
        add("direct")
        add("dns-out")
        add("block")
        appProxyRoutes.filterNot { it.applyRoutingRules }.forEach { add(it.outboundTag) }
    }

    private fun JsonObject?.defaultTcpRouteTarget(): XrayRouteTarget? = (this?.get("rules") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstNotNullOfOrNull { rule -> rule.takeIf { it.isCatchAllTcpRoute() }?.routeTarget() }

    private fun JsonObject.isCatchAllTcpRoute(): Boolean {
        if (keys.any { it !in DEFAULT_ROUTE_FIELDS }) return false
        val type = (get("type") as? JsonPrimitive)?.contentOrNull
        if (type != null && type != "field") return false

        val networks = when (val network = get("network")) {
            null -> return true
            is JsonPrimitive -> network.contentOrNull?.split(',').orEmpty()
            is JsonArray -> network.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> return false
        }
        return networks.any { it.trim().equals("tcp", ignoreCase = true) }
    }

    private fun JsonObject.routeTarget(): XrayRouteTarget? {
        val outboundTag = (get("outboundTag") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        val balancerTag = (get("balancerTag") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        return when {
            outboundTag != null && balancerTag == null -> XrayRouteTarget.Outbound(outboundTag)
            balancerTag != null && outboundTag == null -> XrayRouteTarget.Balancer(balancerTag)
            else -> null
        }
    }

    private companion object {
        val DEFAULT_ROUTE_FIELDS = setOf("type", "network", "outboundTag", "balancerTag", "ruleTag")
    }

    private data class NormalizedOutbounds(
        val all: List<JsonObject>,
        val proxyIndex: Int,
    ) {
        val proxy: JsonObject
            get() = all[proxyIndex]
    }
}
