package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherVpnRoutingTest {
    @Test
    fun `blocks parse with host bits cleared and reject hostnames`() {
        assertEquals("10.1.2.0/24", Cidr.parse("10.1.2.3/24").toString())
        assertEquals("fd7a:115c:a1e0:0:0:0:0:0/48", Cidr.parse("fd7a:115c:a1e0::53/48").toString())
        assertNull(Cidr.parse("example.com/24"))
        assertNull(Cidr.parse("10.0.0.0/33"))
        assertNull(Cidr.parse("10.0.0.0"))
    }

    @Test
    fun `containment follows the prefix within one family`() {
        val cgnat = Cidr.parse("100.64.0.0/10")!!
        assertTrue(cgnat.contains(Cidr.parse("100.67.155.120/32")!!))
        assertFalse(cgnat.contains(Cidr.parse("100.128.0.1/32")!!))
        assertFalse(cgnat.contains(Cidr.parse("0.0.0.0/0")!!))
        assertFalse(Cidr.parse("::/0")!!.contains(cgnat))
    }

    @Test
    fun `auto-routing keeps the other VPN's own networks but never its default route`() {
        val routes = listOf("0.0.0.0/0", "::/0", "100.67.155.120/32", "203.0.113.7/24", "fd7a:115c:a1e0::/48", "fe80::/64")

        assertEquals(
            listOf("203.0.113.0/24"),
            otherVpnBypassRoutes(routes, bypassLan = true, ipv6Enabled = true),
        )
        assertEquals(
            listOf("100.67.155.120/32", "203.0.113.0/24", "fd7a:115c:a1e0:0:0:0:0:0/48", "fe80:0:0:0:0:0:0:0/64"),
            otherVpnBypassRoutes(routes, bypassLan = false, ipv6Enabled = true),
        )
        assertEquals(
            listOf("100.67.155.120/32", "203.0.113.0/24"),
            otherVpnBypassRoutes(routes, bypassLan = false, ipv6Enabled = false),
        )
    }

    @Test
    fun `a full tunnel without a default route stays on the core`() {
        val halves = listOf("0.0.0.0/1", "128.0.0.0/1", "203.0.113.0/24", "2001:db8::/32")
        // WireGuard's "exclude private IPs" list, which is most of the IPv4 space.
        val allButPrivate = listOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3",
            "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12",
            "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7",
            "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13", "192.169.0.0/16",
            "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8",
            "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4",
        )

        assertEquals(listOf("2001:db8:0:0:0:0:0:0/32"), otherVpnBypassRoutes(halves, bypassLan = true, ipv6Enabled = true))
        assertEquals(emptyList<String>(), otherVpnBypassRoutes(allButPrivate, bypassLan = true, ipv6Enabled = false))
    }

    @Test
    fun `the other VPN's routes return before app groups are marked`() {
        val command = TproxyManager.activationCommand(plan(otherVpnRoutes = listOf("203.0.113.0/24", "2001:db8::/32")), APP_UID)
        val routeReturn = "-A MXOA278b -d 203.0.113.0/24 -m mark ! --mark 0x10000000/0x10000000 -j RETURN"

        assertTrue(command.contains(routeReturn))
        assertTrue(command.indexOf(routeReturn) < command.indexOf("-A MXOA278b -m owner --uid-owner 10030 -p tcp"))
        assertFalse(command.contains("2001:db8"))
    }

    @Test
    fun `only secure VPN rules are mirrored`() {
        val rules = MirroredVpnRule.vpnRules(DEVICE_RULES)

        assertEquals(
            setOf(MirroredVpnRule(0, 10143, "tun1"), MirroredVpnRule(10145, 99999, "tun1")),
            rules,
        )
    }

    @Test
    fun `rule sync adds missing copies before removing stale ones`() = runTest {
        val commands = mutableListOf<String>()
        val listing = DEVICE_RULES +
            "9980:\tfrom all fwmark 0x10000000/0x10020000 iif lo uidrange 0-10143 lookup tun1 \n" +
            "9980:\tfrom all fwmark 0x10000000/0x10020000 iif lo uidrange 0-99999 lookup tun0 \n"
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(0, if (command == "ip rule show") listing else "", "")
        }

        assertTrue(manager.syncOtherVpnRules(plan().runtimeState).success)

        assertEquals(
            "{ ip rule add fwmark 0x10000000/0x10020000 iif lo uidrange 10145-99999 lookup tun1 pref 9980; } && " +
                "{ ip rule del fwmark 0x10000000/0x10020000 iif lo uidrange 0-99999 lookup tun0 pref 9980; }",
            commands.last(),
        )
    }

    @Test
    fun `rule sync leaves matching copies alone`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(0, "", "")
        }

        assertTrue(manager.syncOtherVpnRules(plan(allowIpv6 = true).runtimeState).success)

        assertEquals(listOf("ip rule show", "ip -6 rule show"), commands)
    }

    @Test
    fun `cleanup removes mirrored rules and treats them as owned state`() {
        val command = TproxyManager.cleanupCommand(null, APP_UID)

        assertTrue(command.contains("while ip rule del fwmark 0x10000000/0x10020000 pref 9980 2>/dev/null; do :; done"))
        assertTrue(command.contains("while ip -6 rule del fwmark 0x10000000/0x10020000 pref 9980 2>/dev/null; do :; done"))
        assertTrue(command.contains("grep -Fq 'fwmark 0x10000000/0x10020000'"))
    }

    @Test
    fun `mirrored rules are not a mark namespace conflict`() {
        val rules = overlappingFwmarkRules(
            "9980:\tfrom all fwmark 0x10000000/0x10020000 iif lo uidrange 0-99999 lookup tun1",
            TproxyCompatibilityDetector.MARK_PREFIX,
            TproxyCompatibilityDetector.MARK_MASK,
        )

        assertTrue(isOtherVpnMirrorRule(rules.single()))
    }

    private fun plan(allowIpv6: Boolean = false, otherVpnRoutes: List<String> = emptyList()): TproxyTrafficPlan {
        val state = TproxyManager.createRuntimeState(
            routeTable = 300,
            groups = listOf(Long.MAX_VALUE to "tproxy-in-default", 7L to "app-in-7"),
            ports = listOf(48_321, 48_322),
            allowIpv6 = allowIpv6,
            otherVpnRoutes = otherVpnRoutes,
        )
        return TproxyTrafficPlan(
            runtimeState = state,
            groups = listOf(
                TproxyTrafficGroup(state.groups[0], emptySet(), isBase = true),
                TproxyTrafficGroup(state.groups[1], setOf(10_030)),
            ),
            bypassUids = setOf(APP_UID),
            routeProfileIds = setOf(0),
        )
    }

    private companion object {
        const val APP_UID = 10_123

        // Trimmed from a device running Tailscale, which excludes uid 10144.
        val DEVICE_RULES = """
            0:	from all lookup local 
            9990:	from all fwmark 0x10000000/0x10000000 lookup 300 
            13000:	from all fwmark 0x0/0x20000 iif lo uidrange 0-10143 lookup tun1 
            13000:	from all fwmark 0x0/0x20000 iif lo uidrange 10145-99999 lookup tun1 
            13000:	from all fwmark 0xc00a7/0xcffff lookup tun1 
            15040:	from all fwmark 0x10098/0x1ffff iif lo uidrange 10183-10183 lookup wlan0 
            16000:	from all fwmark 0x100a7/0x1ffff iif lo uidrange 0-10143 lookup tun1 
            25000:	from all fwmark 0x0/0x10000 iif lo uidrange 10183-10183 lookup wlan0_local 
            
        """.trimIndent()
    }
}
