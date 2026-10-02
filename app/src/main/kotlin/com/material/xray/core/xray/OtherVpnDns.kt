package com.material.xray.core.xray

import com.material.xray.model.isIpv6DnsServerLiteral

/**
 * The private DNS zone another app's VPN publishes, such as Tailscale's MagicDNS: its search
 * domains and the resolvers that answer for them, reached through that VPN's network [netId].
 */
data class OtherVpnDns(
    val netId: Int,
    val servers: List<String>,
    val domains: List<String>,
) {
    /** Android's fwmark for a privileged socket bound to the VPN network, as Network.bindSocket would mark it. */
    val socketMark: Int get() = SYSTEM_PERMISSION_MARK or netId

    fun serversFor(allowIpv6: Boolean): List<String> = if (allowIpv6) servers else servers.filterNot(::isIpv6DnsServerLiteral)

    /** This zone as a config can use it, or null when IPv6 being off leaves no resolver. */
    fun forIpv6(allowIpv6: Boolean): OtherVpnDns? = copy(servers = serversFor(allowIpv6)).takeIf { it.servers.isNotEmpty() }

    companion object {
        private const val SYSTEM_PERMISSION_MARK = 0xc0000

        /** Returns null when the VPN publishes no private zone to route. */
        fun of(netId: Int, servers: List<String>, searchDomains: String?): OtherVpnDns? {
            val domains = searchDomains.orEmpty()
                .split(' ', ',')
                .map { it.trim().trimEnd('.').lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
            val usableServers = servers.map { it.substringBefore('%') }.filter { it.isNotBlank() }.distinct()
            if (netId <= 0 || domains.isEmpty() || usableServers.isEmpty()) return null
            return OtherVpnDns(netId, usableServers, domains)
        }
    }
}
