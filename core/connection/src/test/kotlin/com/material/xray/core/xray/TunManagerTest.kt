package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunManagerTest {
    @Test
    fun `automatic name uses wlan0 when no wlan names exist`() {
        val name = TunManager.nextAvailableWlanName(sequenceOf("lo", "rmnet0"))

        assertEquals("wlan0", name)
    }

    @Test
    fun `automatic name uses the lowest gap in occupied wlan names`() {
        val name = TunManager.nextAvailableWlanName(
            sequenceOf("lo", "wlan0", "wlan1", "wlan3", "rmnet0"),
        )

        assertEquals("wlan2", name)
    }

    @Test
    fun `link parser handles peer suffix from one-line ip output`() {
        val name = TunManager.parseLinkInterfaceName(
            "37: wlan1@if5: <POINTOPOINT,UP> mtu 1500 qdisc pfifo_fast state UNKNOWN",
        )

        assertEquals("wlan1", name)
    }

    @Test
    fun `link parser rejects malformed output`() {
        assertNull(TunManager.parseLinkInterfaceName("not an ip link line"))
    }

    @Test
    fun `TUN setup assigns an address per family to the main route and every app group`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            when {
                command.startsWith("ip link show") -> successfulCommand(output = "wlan1")
                command.startsWith("ip -6 addr show") -> successfulCommand(
                    output = """
                        inet6 fd10:10:14::1/64 scope global nodad
                        inet6 fd10:10:14:1::1/64 scope global nodad
                        inet6 fd10:10:14:2::1/64 scope global nodad
                    """.trimIndent(),
                )
                else -> successfulCommand()
            }
        }

        val result = manager.configureTun(tunName = "wlan1", appRouteCount = 2, allowIpv6 = true)

        assertTrue(result.success)
        assertEquals(2, commands.size)
        listOf("10.0.0.1/30", "10.0.1.1/30", "10.0.2.1/30").forEach { cidr ->
            assertTrue(commands[0].contains("ip addr replace '$cidr' dev 'wlan1'"))
        }
        listOf("fd10:10:14::1/64", "fd10:10:14:1::1/64", "fd10:10:14:2::1/64").forEach { cidr ->
            assertTrue(commands[0].contains("ip -6 addr replace '$cidr' dev 'wlan1' nodad"))
        }
        assertEquals("ip -6 addr show dev 'wlan1'", commands[1])
    }

    @Test
    fun `TUN setup rejects a missing app group IPv6 address`() = runTest {
        val manager = TunManager { command ->
            when {
                command.startsWith("ip link show") -> successfulCommand(output = "wlan1")
                command.startsWith("ip -6 addr show") -> successfulCommand(
                    output = "    inet6 fd10:10:14::1/64 scope global nodad",
                )
                else -> successfulCommand()
            }
        }

        val result = manager.configureTun(tunName = "wlan1", appRouteCount = 1, allowIpv6 = true)

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("fd10:10:14:1::1/64"))
    }

    @Test
    fun `TUN setup leaves IPv6 untouched when IPv6 is disabled`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            successfulCommand(output = "wlan1")
        }

        val result = manager.configureTun(tunName = "wlan1")

        assertTrue(result.success)
        assertEquals(1, commands.size)
        assertTrue("ip -6" !in commands[0])
    }

    @Test
    fun `TUN setup rejects an IPv6 address that remains tentative`() = runTest {
        val manager = TunManager { command ->
            when {
                command.startsWith("ip link show") -> successfulCommand(output = "wlan1")
                command.startsWith("ip -6 addr show") -> successfulCommand(
                    output = "    inet6 fd10:10:14::1/64 scope global tentative",
                )
                else -> successfulCommand()
            }
        }

        val result = manager.configureTun(tunName = "wlan1", allowIpv6 = true)

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains(TunManager.DEFAULT_TUN_IPV6_ADDRESS_CIDR))
    }

    @Test
    fun `TUN setup stops waiting when Xray exits`() = runTest {
        var processChecks = 0
        val manager = TunManager {
            RootShell.Result(124, "", "")
        }

        val result = manager.configureTun(tunName = "wlan1") {
            processChecks++
            false
        }

        assertFalse(result.success)
        assertTrue(result.processExited)
        assertEquals(1, processChecks)
    }

    @Test
    fun `app TUN IPv6 addresses use distinct bounded subnets`() {
        assertEquals("fd10:10:14:1::1/64", appTunIpv6AddressCidr(1))
        assertEquals("fd10:10:14:fe::1/64", appTunIpv6AddressCidr(254))
        assertEquals(appTunIpv6AddressCidr(1), appTunIpv6AddressCidr(0))
        assertEquals(appTunIpv6AddressCidr(254), appTunIpv6AddressCidr(255))
    }

    @Test
    fun `IPv6 route follows the IPv6 setting`() {
        assertEquals(
            "ip -6 route replace default dev wlan1 src fd10:10:14:1::1 table 110",
            TunManager.ipv6TunRouteCommand("wlan1", "fd10:10:14:1::1/64", 110, allowIpv6 = true),
        )
        assertEquals(
            "ip -6 route replace unreachable default table 110",
            TunManager.ipv6TunRouteCommand("wlan1", "fd10:10:14:1::1/64", 110, allowIpv6 = false),
        )
    }

    @Test
    fun `app group source addresses are the group's TUN addresses`() {
        assertEquals(listOf("10.0.3.1", "fd10:10:14:3::1"), appRouteSourceAddresses(3))
    }

    @Test
    fun `routing update guards both address families until complete policy is installed`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command == LocalAddresses.COMMAND) {
                successfulCommand("1: lo inet 127.0.0.1/8 scope host lo\n2: ap0 inet 192.168.43.1/24 scope global ap0")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = setOf(10_001),
            appTunRoutes = listOf(TunManager.AppTunRoute(index = 1, routeTable = 110, uids = setOf(10_002))),
        )

        assertTrue(result.success)
        assertTrue(commands.any { it.contains("iptables-save") && it.contains("*MXTP*") })
        assertEquals(4, commands.size)
        assertTrue(commands[0].contains("route replace unreachable default table 102"))
        assertTrue(commands[0].contains("rule add iif lo uidrange 10000-10000 table 102 prio 11999"))
        assertTrue(commands[0].contains("rule add iif lo uidrange 10002-99999 table 102 prio 11999"))
        assertTrue(commands[0].contains("| ip -batch -"))
        assertTrue(commands[0].contains("| ip -6 -batch -"))
        assertTrue(commands[1].contains("ip route flush table 100"))
        assertTrue(commands[1].contains("ip -6 route flush table 100"))
        assertTrue(commands[2].contains("route replace unreachable default table 100"))
        assertTrue(commands[2].contains("route replace unreachable default table 110"))
        assertTrue(commands[2].contains("route replace default dev wlan1 src 10.0.0.1 table 100"))
        assertTrue(commands[2].contains("route replace default dev wlan1 src 10.0.1.1 table 110"))
        assertTrue(commands[2].contains("rule add iif lo uidrange 10000-10000 table 100 prio 12010"))
        assertTrue(commands[2].contains("rule add iif lo uidrange 10002-10002 table 110 prio 12000"))
        assertTrue(commands[3].contains("rule del iif lo uidrange 10000-10000 table 102 prio 11999"))
        assertTrue(commands[3].contains("v4_remaining=\$(ip rule show table 102)"))
        assertTrue(commands[3].contains("v6_remaining=\$(ip -6 rule show table 102)"))
        commands.forEach { command ->
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
        }
    }

    @Test
    fun `tether routing captures DNS and public traffic while preserving upstream and LAN`() = runTest {
        val commands = mutableListOf<String>()
        var localAddresses = "1: lo inet 127.0.0.1/8 scope host lo\n2: ap0 inet 192.168.43.1/24 scope global ap0"
        val manager = TunManager { command ->
            commands += command
            if (command == LocalAddresses.COMMAND) {
                successfulCommand(localAddresses)
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "xray0",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
            tunnelTetheredClients = true,
        )

        assertTrue(result.success)
        val command = commands.joinToString("\n")
        assertTrue(command.contains("fwmark 0x10000000/0x10000000 table 100 prio 11998"))
        assertTrue(command.contains("iptables -w 2 -t mangle -A MXTP -i 'wlan0' -j RETURN"))
        assertTrue(command.indexOf("--dport 53 -j MARK") < command.indexOf("-d 192.168.0.0/16 -j RETURN"))
        assertTrue(command.contains("iptables -w 2 -t nat -A MXTD"))
        assertTrue(command.contains("--to-destination 198.18.0.1"))
        assertFalse(command.contains("ip6tables -w 2 -t mangle -N MXTP"))
        assertFalse(command.contains("ip6tables -w 2 -t nat -N MXTD"))
        assertTrue(command.contains("ip6tables -w 2 -t filter -A MXTF -j REJECT --reject-with icmp6-no-route"))
        commands.forEach { generated ->
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", generated).start().waitFor())
        }

        localAddresses += "\n2: ap0 inet6 2001:db8::1/64 scope global"
        assertFalse(manager.localAddressesChanged())
        assertFalse(manager.localAddressesChanged())
        localAddresses += "\n3: rndis0 inet 192.168.44.1/24 scope global rndis0"
        assertFalse(manager.localAddressesChanged())
        assertTrue(manager.localAddressesChanged())
    }

    @Test
    fun `tether routing sends private destinations through TUN when LAN bypass is disabled`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command == LocalAddresses.COMMAND) {
                successfulCommand("1: lo inet 127.0.0.1/8 scope host lo\n2: ap0 inet 192.168.43.1/24 scope global ap0")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "xray0",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
            tunnelTetheredClients = true,
            bypassLan = false,
        )

        assertTrue(result.success)
        val command = commands.joinToString("\n")
        assertFalse(command.contains("MXTP -d 192.168.0.0/16 -j RETURN"))
        assertFalse(command.contains("MXTF -d 192.168.0.0/16 -j RETURN"))
    }

    @Test
    fun `routing guard removal deletes exact rules in bounded batches`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command == LocalAddresses.COMMAND) {
                successfulCommand("1: lo inet 127.0.0.1/8 scope host lo\n2: ap0 inet 192.168.43.1/24 scope global ap0")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertTrue(result.success)
        val removal = commands.single { it.contains("remaining=\$(ip rule show table 102") }
        assertTrue(removal.contains("rule del iif lo uidrange 10000-99999 table 102 prio 11999"))
        assertTrue(removal.contains("ip -force -batch"))
        assertTrue(removal.contains("ip -6 -force -batch"))
        assertTrue(removal.contains("*'11999:'*) false"))
        commands.forEach { command ->
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
        }
    }

    @Test
    fun `routing guard removal retries and fails closed when guards remain`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command.contains("remaining=\$(ip rule show table 102")) {
                RootShell.Result(1, "", "guard remains")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("guard removal"))
        assertEquals(1, commands.count { it.contains("remaining=\$(ip rule show table 102") })
    }

    @Test
    fun `routing guard inspection failure stops without deleting or flushing`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command.contains("remaining=\$(ip rule show table 102")) {
                RootShell.Result(1, "", "inspection failed")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("inspection failed"))
        assertTrue(commands.any { it.contains("rule del iif lo uidrange") })
    }

    @Test
    fun `routing guard inspection ignores unrelated rules`() = runTest {
        val commands = mutableListOf<String>()
        val unrelatedRules = """
            12010:\tfrom all iif lo uidrange 10000-99999 lookup 100
            11999:\tfrom all iif lo uidrange 10000-99999 lookup 999
        """.trimIndent()
        val manager = TunManager { command ->
            commands += command
            when (command) {
                "ip rule show table 102", "ip -6 rule show table 102" -> successfulCommand(unrelatedRules)
                else -> successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertTrue(result.success)
        assertFalse(commands.any { it.contains("lookup 999") })
    }

    @Test
    fun `routing guard removal recognizes named kernel route tables`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command == LocalAddresses.COMMAND) {
                successfulCommand("1: lo inet 127.0.0.1/8 scope host lo\n2: ap0 inet 192.168.43.1/24 scope global ap0")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 251,
            bypassTable = 252,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertTrue(result.success)
        assertTrue(commands.any { it.contains("rule del iif lo uidrange") && it.contains("table 253") })
    }

    @Test
    fun `physical bypass update replaces only its default route`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command == LocalAddresses.COMMAND) {
                successfulCommand("1: lo inet 127.0.0.1/8 scope host lo\n2: ap0 inet 192.168.43.1/24 scope global ap0")
            } else {
                successfulCommand()
            }
        }

        val result = manager.replacePhysicalBypassRoute(
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.2", "wlan0"),
        )

        assertTrue(result.success)
        assertEquals(
            listOf("ip route replace default via 192.0.2.2 dev wlan0 table 101"),
            commands,
        )
    }

    @Test
    fun `failed routing update retains both address family guards`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (commands.size == 3) RootShell.Result(1, "", "rule failed") else successfulCommand()
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertEquals(3, commands.size)
        assertTrue(commands[0].contains("route replace unreachable default table 102"))
        assertTrue(commands[0].contains("table 102 prio 11999"))
        assertTrue(commands[0].contains("ip -6 -batch"))
        assertFalse(commands.any { it == "ip rule show table 102" })
    }

    @Test
    fun `failed stale rule deletion retains update guards`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            if (command.contains("rules=\$(ip rule show")) {
                RootShell.Result(1, "", "stale rule deletion failed")
            } else {
                successfulCommand()
            }
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertEquals(2, commands.size)
        assertFalse(commands.any { it == "ip rule show table 102" })
    }

    @Test
    fun `failed guard route installation stops before adding rules`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TunManager { command ->
            commands += command
            RootShell.Result(1, "", "guard failed")
        }

        val result = manager.applyRouting(
            tunName = "wlan1",
            fwmark = 255,
            routeTable = 100,
            bypassTable = 101,
            physicalRoute = TunManager.PhysicalRoute("wlan0", "192.0.2.1", "main"),
            allowIpv6 = false,
            bypassUids = emptySet(),
        )

        assertFalse(result.success)
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains("route replace unreachable default table 102"))
        assertTrue(commands.single().contains("table 102 prio 11999"))
    }

    @Test
    fun `routing cleanup removes IPv6 rules routes and update guard`() {
        val command = TunManager { successfulCommand() }.routingRemovalCommand(
            fwmark = 255,
            routeMark = 100,
            routeTable = 100,
            tunName = "wlan1",
            managedAppRouteCount = 1,
        )

        assertTrue(command.contains("ip -6 rule show"))
        assertTrue(command.contains("ip -6 route flush table 100"))
        assertTrue(command.contains("ip -6 route flush table 102"))
        assertTrue(command.contains("status=0"))
        assertTrue(command.endsWith("exit \$status"))
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
    }

    @Test
    fun `link cleanup tolerates interface disappearing after inspection`() {
        val command = managedLinkRemovalCommand(listOf("xray0"))
        val fakeIp = """
            present=1
            ip() {
                if [ "${'$'}1 ${'$'}2" = "link show" ]; then [ "${'$'}present" -eq 1 ]; return; fi
                if [ "${'$'}1 ${'$'}2" = "link del" ]; then present=0; return 1; fi
                return 1
            }
        """.trimIndent()

        assertEquals(0, ProcessBuilder("sh", "-c", "$fakeIp\n$command").start().waitFor())
    }

    @Test
    fun `link cleanup reports deletion failure while interface remains`() {
        val command = managedLinkRemovalCommand(listOf("xray0"))
        val fakeIp = """
            ip() {
                if [ "${'$'}1 ${'$'}2" = "link show" ]; then return 0; fi
                if [ "${'$'}1 ${'$'}2" = "link del" ]; then return 1; fi
                return 1
            }
        """.trimIndent()

        assertEquals(1, ProcessBuilder("sh", "-c", "$fakeIp\n$command").start().waitFor())
    }

    private fun successfulCommand(output: String = "") = RootShell.Result(0, output, "")
}
