package com.material.xray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TproxyCompatibilityDetectorTest {
    @Test
    fun `dual stack probe exercises production firewall hooks and policy routing`() {
        val command = TproxyCompatibilityDetector.probeCommand("abc123", allowIpv6 = true, appUid = APP_UID)

        assertTrue(command.contains("-p tcp -m mark --mark 0x8200000/0x1fe00000 -j TPROXY"))
        assertTrue(command.contains("-p udp -m mark --mark 0x8200000/0x1fe00000 -j TPROXY"))
        assertTrue(command.contains("su -g $APP_UID 0 -c"))
        assertTrue(command.contains("--gid-owner $APP_UID"))
        assertTrue(
            command.contains(
                "--gid-owner $APP_UID -m mark --mark 0x8000000/0x18000000 " +
                    "-j MARK --set-xmark 0x0/0x1fe00000",
            ),
        )
        assertTrue(
            command.indexOf(
                "--gid-owner $APP_UID -m mark --mark 0x8000000/0x18000000 " +
                    "-j MARK --set-xmark 0x0/0x1fe00000",
            ) < command.indexOf("--gid-owner $APP_UID -j RETURN"),
        )
        assertTrue(command.contains("iptables -w 2 -t mangle -I PREROUTING 1 -j MXPabc1234P"))
        assertTrue(command.contains("iptables -w 2 -t mangle -I OUTPUT 1 -j MXPabc1234O"))
        assertTrue(command.contains("ip6tables -w 2 -t mangle -I PREROUTING 1 -j MXPabc1236P"))
        assertTrue(command.contains("ip6tables -w 2 -t mangle -I OUTPUT 1 -j MXPabc1236O"))
        assertTrue(command.contains("iptables -w 2 -t mangle -A MXPabc1234O -o lo"))
        assertTrue(command.contains("ip6tables -w 2 -t mangle -A MXPabc1236O -o lo"))
        assertTrue(command.contains("ip route get 192.0.2.1 mark"))
        assertTrue(command.contains("ip -6 route get 2001:db8::1 mark"))
        assertTrue(command.contains("ip6tables -w 2 -t mangle -X MXPabc1236P"))
        assertTrue(command.contains("fail cleanup"))
        assertFalse(command.contains("curl"))
        assertFalse(command.contains("ping"))
    }

    @Test
    fun `IPv4 only probe covers loopback binding IPv6 blocking and listener checks`() {
        val command = TproxyCompatibilityDetector.probeCommand("abc123", allowIpv6 = false, appUid = APP_UID)

        assertTrue(command.contains("--on-ip 127.0.0.1"))
        assertTrue(command.contains("-d 127.0.0.0/8 -p tcp --dport 9 -j DROP"))
        assertTrue(command.contains("ip6tables -w 2 -t mangle -I OUTPUT 1 -j MXPabc1236O"))
        assertTrue(command.contains("ip6tables -w 2 -t filter -I OUTPUT 1 -j MXPabc1236F"))
        assertTrue(command.contains("-j REJECT --reject-with icmp6-no-route"))
        assertTrue(command.contains("--uid-owner 0-1"))
        assertTrue(
            command.contains(
                "ip6tables -w 2 -t mangle -A MXPabc1236O -m owner --gid-owner $APP_UID " +
                    "-m mark --mark 0x8000000/0x18000000 -j MARK --set-xmark 0x0/0x1fe00000",
            ),
        )
        assertTrue(command.contains("ss -lnt >/dev/null && ss -lnu >/dev/null"))
        assertFalse(command.contains("addrtype"))
        assertFalse(command.contains("-m socket"))
    }

    @Test
    fun `probe waits for the xtables lock on every firewall command`() {
        val commands = listOf(
            TproxyCompatibilityDetector.probeCommand("abc123", allowIpv6 = false, appUid = APP_UID),
            TproxyCompatibilityDetector.probeCommand("abc123", allowIpv6 = true, appUid = APP_UID),
            TproxyCompatibilityDetector.markCollisionCommand(10_123),
        )

        commands.forEach { command ->
            assertFalse(Regex("(?<![\\w-])ip6?tables -t").containsMatchIn(command))
        }
    }

    @Test
    fun `probe mark cannot be captured by the production policy rule`() {
        val command = TproxyCompatibilityDetector.probeCommand("abc123", allowIpv6 = true, appUid = APP_UID)

        assertTrue(command.contains("0x8000000/0x18000000"))
        assertFalse(command.contains("0x10000000"))
    }

    @Test
    fun `probe rule outruns Android policy routing and the production rule`() {
        val priorities = (0..0xfff).map { value ->
            val suffix = value.toString(16).padStart(3, '0')
            val command = TproxyCompatibilityDetector.probeCommand(suffix, allowIpv6 = false, appUid = APP_UID)
            Regex("ip rule add fwmark \\S+ table \\d+ pref (\\d+)").find(command)!!.groupValues[1].toInt()
        }

        assertTrue(priorities.all { it > TproxyManager.RULE_PRIORITY })
        assertTrue(priorities.all { it < ANDROID_FIRST_FWMARK_RULE_PRIORITY })
    }

    @Test
    fun `collision check accepts only the apps owned output chain`() {
        val command = TproxyCompatibilityDetector.markCollisionCommand(10_123)

        assertTrue(command.contains("fwmark 0x10000000/0x10000000"))
        assertTrue(command.contains("iptables -w 2 -t mangle -C OUTPUT -j MXO278b"))
        assertTrue(command.contains("exit 42"))
    }

    @Test
    fun `overlap detection catches broader narrower and exact rules`() {
        val output = """
            100: from all fwmark 0x10000000/0xf0000000 lookup 1
            101: from all fwmark 0x10200000/0x1fe00000 lookup 2
            102: from all fwmark 0x08000000/0x18000000 lookup 3
            103: from all fwmark 0x10000000/0x10000000 lookup 4
        """.trimIndent()

        val overlaps = overlappingFwmarkRules(
            output,
            TproxyCompatibilityDetector.MARK_PREFIX,
            TproxyCompatibilityDetector.MARK_MASK,
        )

        assertEquals(listOf(100, 101, 103), overlaps.map { it.priority })
    }

    @Test
    fun `low-bit fwmark predicates can overlap while preserving Android fields`() {
        val output = """
            9999: from all fwmark 0x20000/0xfffff lookup 1027
            10000: from all fwmark 0xc0000/0xd0000 lookup 99
        """.trimIndent()

        val overlaps = overlappingFwmarkRules(
            output,
            TproxyCompatibilityDetector.MARK_PREFIX,
            TproxyCompatibilityDetector.MARK_MASK,
        )

        assertEquals(listOf(9999, 10000), overlaps.map { it.priority })
    }

    @Test
    fun `IPv6-only failure preserves IPv4 TPROXY support`() {
        val ipv4 = TproxyCompatibility.Supported(ipv6 = false)

        val result = resolveDualStackCompatibility(
            ipv4,
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv6Unavailable),
        )

        assertEquals(ipv4, result)
    }

    @Test
    fun `only kernel capability verdicts count as a real statement about the device`() {
        assertTrue(TproxyCompatibility.Supported(ipv6 = true).isConclusive())
        assertTrue(
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv6Unavailable).isConclusive(),
        )
        assertTrue(
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.Ipv6BlockingUnavailable).isConclusive(),
        )
        assertTrue(
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.ProcessGroupUnavailable).isConclusive(),
        )
        assertFalse(TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.RootUnavailable).isConclusive())
        assertFalse(TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.CommandTimedOut).isConclusive())
        assertFalse(TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.MarkNamespaceConflict).isConclusive())
        assertFalse(TproxyCompatibility.Unknown.isConclusive())
    }

    @Test
    fun `cache round trips conclusive compatibility verdicts`() {
        val verdicts = listOf(
            TproxyCompatibility.Supported(ipv6 = true),
            TproxyCompatibility.Supported(ipv6 = false),
            TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.TproxyIpv4Unavailable),
        )

        verdicts.forEach { verdict ->
            assertEquals(verdict, decodeCachedTproxyCompatibility(encodeCachedTproxyCompatibility(verdict)))
        }
    }

    @Test
    fun `cache rejects transient and malformed compatibility verdicts`() {
        assertEquals(
            null,
            encodeCachedTproxyCompatibility(
                TproxyCompatibility.Unsupported(TproxyCompatibility.Reason.CommandTimedOut),
            ),
        )
        assertEquals(null, decodeCachedTproxyCompatibility("0|supported|1"))
        assertEquals(null, decodeCachedTproxyCompatibility("1|supported|maybe"))
        assertEquals(null, decodeCachedTproxyCompatibility("1|unsupported|CommandTimedOut"))
    }

    private companion object {
        const val APP_UID = 10_123
        const val ANDROID_FIRST_FWMARK_RULE_PRIORITY = 10_000
    }
}
