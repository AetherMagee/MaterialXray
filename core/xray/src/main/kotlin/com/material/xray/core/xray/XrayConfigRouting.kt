package com.material.xray.core.xray

import com.material.xray.model.RoutingRule
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.isIpv4DnsServerLiteral
import com.material.xray.model.isIpv6DnsServerLiteral
import com.material.xray.model.resolveDnsServersForIpv6
import com.material.xray.model.toXrayRules
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val TUN_INBOUND_TAG = "tun-in"
private const val DEFAULT_DNS_TAG = "default-dns"
private const val DOMESTIC_DNS_TAG = "domestic-dns"
private const val OTHER_VPN_DNS_TAG = "other-vpn-dns"
private const val SYSTEM_DNS_SERVER = "localhost"

internal sealed interface XrayRouteTarget {
    data class Outbound(val tag: String) : XrayRouteTarget

    data class Balancer(val tag: String) : XrayRouteTarget
}

fun buildDns(
    servers: String,
    domesticServers: String = "",
    bootstrapHosts: Map<String, List<String>> = emptyMap(),
    routingRules: List<RoutingRule> = emptyList(),
    bypassLan: Boolean = false,
    allowIpv6: Boolean = false,
    otherVpnDns: OtherVpnDns? = null,
) = buildJsonObject {
    val domesticDomains = directDomains(routingRules, bypassLan)
    val defaultServers = resolveDnsServersForIpv6(servers, allowIpv6).map(String::toReliableXrayDnsAddress)
    if (!allowIpv6) {
        put("queryStrategy", "UseIPv4")
    }
    if (defaultServers.isNotEmpty()) {
        put("tag", DEFAULT_DNS_TAG)
    }
    if (bootstrapHosts.isNotEmpty()) {
        put(
            "hosts",
            buildJsonObject {
                bootstrapHosts.toSortedMap().forEach { (host, addresses) ->
                    put(host, buildJsonArray { addresses.distinct().forEach { add(it) } })
                }
            },
        )
    }
    put(
        "servers",
        buildJsonArray {
            // Listed first so the VPN's own zone wins over any other server matching the same names.
            otherVpnDns?.serversFor(allowIpv6)?.forEach { server ->
                add(
                    buildJsonObject {
                        put("address", server)
                        put("domains", buildJsonArray { otherVpnDns.domains.forEach { add("domain:$it") } })
                        put("skipFallback", true)
                        put("tag", OTHER_VPN_DNS_TAG)
                    },
                )
            }
            if (defaultServers.isEmpty()) {
                add(SYSTEM_DNS_SERVER)
            } else {
                defaultServers.forEach { add(it) }
            }
            if (domesticDomains.isNotEmpty()) {
                resolveDnsServersForIpv6(domesticServers, allowIpv6)
                    .map(String::toReliableXrayDnsAddress)
                    .forEach { domesticServer ->
                        add(
                            buildJsonObject {
                                put("address", domesticServer)
                                put("domains", buildJsonArray { domesticDomains.forEach { add(it) } })
                                put("skipFallback", true)
                                put("tag", DOMESTIC_DNS_TAG)
                            },
                        )
                    }
            }
        },
    )
}

internal fun buildRouting(
    routingRules: List<RoutingRule>,
    appProxyRoutes: List<AppProxyRoute> = emptyList(),
    bypassLan: Boolean = true,
    dnsServers: String = "",
    domesticDnsServers: String = "",
    syntheticDnsAddress: String? = null,
    domainStrategy: String = SubscriptionRouting.DEFAULT_DOMAIN_STRATEGY,
    domainMatcher: String? = null,
    defaultRouteTarget: XrayRouteTarget = XrayRouteTarget.Outbound("proxy"),
    allowIpv6: Boolean = false,
    dataInboundTags: List<String> = listOf(TUN_INBOUND_TAG),
    manageDns: Boolean = true,
    otherVpnDns: OtherVpnDns? = null,
) = buildJsonObject {
    val hasDomesticDomains = directDomains(routingRules, bypassLan).isNotEmpty()
    val forcedRoutes = appProxyRoutes.filterNot { it.applyRoutingRules }
    val matchers = appProxyRoutes.associateWith { it.trafficMatcher(dataInboundTags) }
    // A group that shares the TUN inbound still has to reach the rules below, so only groups with
    // an inbound of their own leave the shared DNS handling.
    val forcedInboundTags = forcedRoutes.mapNotNullTo(mutableSetOf()) { route ->
        route.inboundTag.takeIf { it in dataInboundTags }
    }
    val interceptedDnsInboundTags = dataInboundTags.filterNot { it in forcedInboundTags }
    val specificServerRuleRoutes = appProxyRoutes.filter { it.applyRoutingRules && it.outboundTag != "proxy" }
    put("domainStrategy", SubscriptionRouting.normalizeDomainStrategy(domainStrategy))
    SubscriptionRouting.normalizeDomainMatcher(domainMatcher)?.let { put("domainMatcher", it) }
    put(
        "rules",
        buildJsonArray {
            // Forced groups come first: under TUN they share the inbound the generic rules match.
            forcedRoutes.forEach { route ->
                val matcher = matchers.getValue(route)
                syntheticDnsAddress?.let { add(syntheticDnsPeerBlockRule(matcher, it)) }
                add(appProxyRoutingRule(matcher, XrayRouteTarget.Outbound(route.outboundTag)))
            }
            if (interceptedDnsInboundTags.isNotEmpty()) add(dnsRoutingRule(interceptedDnsInboundTags))
            if (syntheticDnsAddress != null && interceptedDnsInboundTags.isNotEmpty()) {
                add(syntheticDnsPeerBlockRule(inboundTagMatcher(interceptedDnsInboundTags), syntheticDnsAddress))
            }
            if (manageDns && interceptedDnsInboundTags.isNotEmpty()) add(dnsOverTlsRoutingRule(interceptedDnsInboundTags))
            if (manageDns && otherVpnDns?.serversFor(allowIpv6)?.isNotEmpty() == true) {
                add(otherVpnDnsRoutingRule())
            }
            // These two rules address the tags buildDns emits, so they have to be decided from the
            // same resolved lists. A stored list that IPv6 filtering empties leaves no tag to route.
            if (manageDns && resolveDnsServersForIpv6(dnsServers, allowIpv6).isNotEmpty()) {
                add(defaultDnsRoutingRule(defaultRouteTarget))
            }
            if (
                manageDns &&
                hasDomesticDomains &&
                resolveDnsServersForIpv6(domesticDnsServers, allowIpv6).isNotEmpty()
            ) {
                add(domesticDnsRoutingRule())
            }
            if (bypassLan) {
                add(lanIpRoutingRule())
                add(lanDomainRoutingRule())
            }
            routingRules.filter { it.enabled }.forEach { rule ->
                rule.toXrayRules().forEach { xrayRule ->
                    if (rule.outboundTag == "proxy") {
                        specificServerRuleRoutes.forEach { route ->
                            add(
                                JsonObject(
                                    xrayRule + matchers.getValue(route) +
                                        ("outboundTag" to JsonPrimitive(route.outboundTag)),
                                ),
                            )
                        }
                    }
                    add(xrayRule)
                }
            }
            appProxyRoutes.filter { it.applyRoutingRules }.forEach { route ->
                val target = if (route.outboundTag == "proxy") defaultRouteTarget else XrayRouteTarget.Outbound(route.outboundTag)
                add(appProxyRoutingRule(matchers.getValue(route), target))
            }
        },
    )
}

private fun directDomains(routingRules: List<RoutingRule>, bypassLan: Boolean): List<String> = buildSet {
    if (bypassLan) add("geosite:private")
    routingRules
        .asSequence()
        .filter { it.enabled && it.outboundTag == "direct" }
        .flatMap { it.domains.asSequence() }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach(::add)
}.toList()

private fun dnsRoutingRule(dataInboundTags: List<String>) = buildJsonObject {
    put("type", "field")
    put(
        "inboundTag",
        buildJsonArray {
            dataInboundTags.forEach { add(it) }
        },
    )
    put("port", "53")
    put("outboundTag", "dns-out")
}

private fun syntheticDnsPeerBlockRule(matcher: Map<String, JsonElement>, address: String) = buildJsonObject {
    put("type", "field")
    matcher.forEach { (key, value) -> put(key, value) }
    put("ip", buildJsonArray { add(address) })
    put("outboundTag", "block")
}

private fun dnsOverTlsRoutingRule(dataInboundTags: List<String>) = buildJsonObject {
    put("type", "field")
    put(
        "inboundTag",
        buildJsonArray {
            dataInboundTags.forEach { add(it) }
        },
    )
    put("network", "tcp")
    put("port", "853")
    put("outboundTag", "direct")
}

private fun defaultDnsRoutingRule(target: XrayRouteTarget) = buildJsonObject {
    put("type", "field")
    put("inboundTag", buildJsonArray { add(DEFAULT_DNS_TAG) })
    when (target) {
        is XrayRouteTarget.Outbound -> put("outboundTag", target.tag)
        is XrayRouteTarget.Balancer -> put("balancerTag", target.tag)
    }
}

private fun otherVpnDnsRoutingRule() = buildJsonObject {
    put("type", "field")
    put("inboundTag", buildJsonArray { add(OTHER_VPN_DNS_TAG) })
    put("outboundTag", OTHER_VPN_OUTBOUND_TAG)
}

private fun domesticDnsRoutingRule() = buildJsonObject {
    put("type", "field")
    put("inboundTag", buildJsonArray { add(DOMESTIC_DNS_TAG) })
    put("outboundTag", "direct")
}

/**
 * Rule fields that select one app group's traffic. A TPROXY group arrives on an inbound of its own;
 * a TUN group shares the TUN inbound and is told apart by the source address its routing table sets.
 */
internal fun AppProxyRoute.trafficMatcher(dataInboundTags: List<String>): Map<String, JsonElement> = if (inboundTag in dataInboundTags) {
    inboundTagMatcher(listOf(inboundTag))
} else {
    inboundTagMatcher(listOf(TUN_INBOUND_TAG)) +
        ("source" to buildJsonArray { appRouteSourceAddresses(routeIndex).forEach { add(it) } })
}

private fun inboundTagMatcher(tags: List<String>): Map<String, JsonElement> = mapOf("inboundTag" to buildJsonArray { tags.forEach { add(it) } })

private fun appProxyRoutingRule(matcher: Map<String, JsonElement>, target: XrayRouteTarget) = buildJsonObject {
    put("type", "field")
    matcher.forEach { (key, value) -> put(key, value) }
    when (target) {
        is XrayRouteTarget.Outbound -> put("outboundTag", target.tag)
        is XrayRouteTarget.Balancer -> put("balancerTag", target.tag)
    }
}

private fun lanIpRoutingRule() = buildJsonObject {
    put("type", "field")
    put("ip", buildJsonArray { add("geoip:private") })
    put("outboundTag", "direct")
}

private fun lanDomainRoutingRule() = buildJsonObject {
    put("type", "field")
    put("domain", buildJsonArray { add("geosite:private") })
    put("outboundTag", "direct")
}

// Xray-core #2248 can permanently cache empty Cloudflare UDP responses for individual names, so every
// bare Cloudflare resolver literal is rewritten to a non-UDP endpoint. Cloudflare serves its resolver
// IPs in the DoH certificate, so an address without an explicit port becomes a DoH URL; an address that
// pins a port keeps that port over TCP because DoH cannot honour it.
private fun String.toReliableXrayDnsAddress(): String {
    val host = numericDnsHostOrNull() ?: return this
    if (host !in CLOUDFLARE_IPV4_DNS && !host.lowercase().startsWith(CLOUDFLARE_IPV6_DNS_PREFIX)) return this

    val escapedAddress = replace("%", "%25")
    return when {
        hasExplicitDnsPort() -> "tcp://$escapedAddress"
        isIpv4DnsServerLiteral(this) || startsWith('[') -> "https://$escapedAddress/dns-query"
        else -> "https://[$escapedAddress]/dns-query"
    }
}

private fun String.hasExplicitDnsPort(): Boolean = isIpv4DnsServerWithPort() || (startsWith('[') && substringAfter(']').isNotEmpty())

private fun String.numericDnsHostOrNull(): String? = when {
    isIpv4DnsServerLiteral(this) -> this
    isIpv4DnsServerWithPort() -> substringBeforeLast(':')
    isIpv6DnsServerLiteral(this) && startsWith('[') -> substringAfter('[').substringBefore(']').substringBefore('%')
    isIpv6DnsServerLiteral(this) -> substringBefore('%')
    else -> null
}

private fun String.isIpv4DnsServerWithPort(): Boolean {
    if (count { it == ':' } != 1) return false
    val host = substringBeforeLast(':')
    val port = substringAfterLast(':').toIntOrNull()
    return isIpv4DnsServerLiteral(host) && port != null && port in 1..65535
}

private val CLOUDFLARE_IPV4_DNS = setOf("1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3")
private const val CLOUDFLARE_IPV6_DNS_PREFIX = "2606:4700:4700::"
