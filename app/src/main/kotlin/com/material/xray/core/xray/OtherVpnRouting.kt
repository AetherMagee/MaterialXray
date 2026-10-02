package com.material.xray.core.xray

import java.math.BigInteger
import java.net.InetAddress

/** An address block in either family, as iptables and ip rules take it. */
internal data class Cidr(val address: ByteArray, val prefixLength: Int) {
    val isIpv6: Boolean get() = address.size == IPV6_BYTES

    /** Whether every address in [other] falls inside this block. */
    fun contains(other: Cidr): Boolean {
        if (other.address.size != address.size || other.prefixLength < prefixLength) return false
        val fullBytes = prefixLength / Byte.SIZE_BITS
        for (index in 0 until fullBytes) {
            if (address[index] != other.address[index]) return false
        }
        val remainingBits = prefixLength % Byte.SIZE_BITS
        if (remainingBits == 0) return true
        val mask = (BYTE_MASK shl (Byte.SIZE_BITS - remainingBits)) and BYTE_MASK
        return (address[fullBytes].toInt() and mask) == (other.address[fullBytes].toInt() and mask)
    }

    override fun toString(): String = "${InetAddress.getByAddress(address).hostAddress}/$prefixLength"

    override fun equals(other: Any?): Boolean = other is Cidr && prefixLength == other.prefixLength && address.contentEquals(other.address)

    override fun hashCode(): Int = 31 * address.contentHashCode() + prefixLength

    companion object {
        private const val IPV4_BYTES = 4
        private const val IPV6_BYTES = 16
        private const val BYTE_MASK = 0xff
        private val LITERAL = Regex("""[0-9A-Fa-f.:]+""")

        /** Parses "address/length" with the host bits cleared, or null for anything but a numeric block. */
        fun parse(value: String): Cidr? {
            val address = value.substringBefore('/').substringBefore('%')
            val prefixLength = value.substringAfter('/', "").toIntOrNull() ?: return null
            // A numeric literal never reaches DNS; this check keeps hostnames out entirely.
            if (!LITERAL.matches(address) || (':' !in address && address.count { it == '.' } != 3)) return null
            val bytes = runCatching { InetAddress.getByName(address).address }.getOrNull() ?: return null
            if (bytes.size != IPV4_BYTES && bytes.size != IPV6_BYTES) return null
            if (prefixLength !in 0..bytes.size * Byte.SIZE_BITS) return null
            val masked = bytes.copyOf()
            for (bit in prefixLength until masked.size * Byte.SIZE_BITS) {
                val index = bit / Byte.SIZE_BITS
                masked[index] = (masked[index].toInt() and (1 shl (Byte.SIZE_BITS - 1 - bit % Byte.SIZE_BITS)).inv()).toByte()
            }
            return Cidr(masked, prefixLength)
        }
    }
}

/**
 * The routes another VPN publishes that Auto-routing leaves to it: anything narrower than a
 * default route, minus what the TPROXY output chain already returns as local or LAN traffic.
 */
fun otherVpnBypassRoutes(routes: List<String>, bypassLan: Boolean, ipv6Enabled: Boolean): List<String> {
    val alreadyReturned = (
        TproxyManager.alwaysReturnedCidrs + TproxyManager.lanReturnedCidrs.takeIf { bypassLan }.orEmpty()
        ).mapNotNull(Cidr::parse)
    // Default routes stay with the core, which is the point of Auto-routing.
    val candidates = routes.mapNotNull(Cidr::parse).filter { it.prefixLength > 0 && (ipv6Enabled || !it.isIpv6) }
    // A VPN can also claim the whole internet without one, as two halves or as everything but the
    // private ranges. That is a full tunnel too, so none of that family is left to it.
    val fullTunnelFamilies = candidates.groupBy { it.isIpv6 }.filterValues(::coversHalfOfFamily).keys
    return candidates.asSequence()
        .filterNot { it.isIpv6 in fullTunnelFamilies }
        .filterNot { route -> alreadyReturned.any { it.contains(route) } }
        .map(Cidr::toString)
        .distinct()
        .sorted()
        .toList()
}

private fun coversHalfOfFamily(routes: List<Cidr>): Boolean {
    val outermost = routes.filterNot { route -> routes.any { it != route && it.contains(route) } }.distinct()
    val bits = outermost.firstOrNull()?.let { it.address.size * Byte.SIZE_BITS } ?: return false
    val covered = outermost.fold(BigInteger.ZERO) { sum, route -> sum + BigInteger.ONE.shiftLeft(bits - route.prefixLength) }
    return covered >= BigInteger.ONE.shiftLeft(bits - 1)
}

/** Matches traffic TPROXY marked for the core, except sockets a VPN app protected. */
internal fun otherVpnMarkSelector(markPrefix: Int, markMask: Int): String = "0x${markPrefix.toUInt().toString(16)}/0x${(markMask or PROTECTED_FROM_VPN_MARK).toUInt().toString(16)}"

internal fun isOtherVpnMirrorRule(rule: FwmarkRule): Boolean = rule.priority == TproxyManager.OTHER_VPN_RULE_PRIORITY &&
    rule.value == TproxyCompatibilityDetector.MARK_PREFIX.toUInt() &&
    rule.mask == (TproxyCompatibilityDetector.MARK_MASK or PROTECTED_FROM_VPN_MARK).toUInt()

/**
 * One of the uid-range rules Android installs for a VPN that apps cannot bypass, mirrored so
 * that traffic TPROXY marked for the core reaches that VPN instead.
 */
internal data class MirroredVpnRule(val firstUid: Int, val lastUid: Int, val table: String) {
    fun selector(markSelector: String): String = "fwmark $markSelector iif lo uidrange $firstUid-$lastUid lookup $table pref ${TproxyManager.OTHER_VPN_RULE_PRIORITY}"

    companion object {
        private val VPN_RULE = Regex("""^\d+:\s+from all fwmark 0x0/0x20000 (?:iif lo )?uidrange (\d+)-(\d+) lookup ([A-Za-z0-9_.-]{1,32})\s*$""")

        /** The VPN rules in an `ip rule show` listing, ignoring the copies made from them. */
        fun vpnRules(listing: String): Set<MirroredVpnRule> = parse(listing, VPN_RULE)

        /** The copies already installed with [markSelector], so a sync only touches what changed. */
        fun mirroredRules(listing: String, markSelector: String): Set<MirroredVpnRule> {
            val pattern = Regex(
                """^${TproxyManager.OTHER_VPN_RULE_PRIORITY}:\s+from all fwmark ${Regex.escape(markSelector)} """ +
                    """iif lo uidrange (\d+)-(\d+) lookup ([A-Za-z0-9_.-]{1,32})\s*$""",
            )
            return parse(listing, pattern)
        }

        private fun parse(listing: String, pattern: Regex): Set<MirroredVpnRule> = listing.lineSequence().mapNotNull { line ->
            val match = pattern.matchEntire(line.trim()) ?: return@mapNotNull null
            val (first, last, table) = match.destructured
            MirroredVpnRule(first.toIntOrNull() ?: return@mapNotNull null, last.toIntOrNull() ?: return@mapNotNull null, table)
        }.toSet()
    }
}
