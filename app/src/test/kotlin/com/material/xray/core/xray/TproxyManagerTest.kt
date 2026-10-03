package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TproxyManagerTest {
    private val extensionError = "Warning: Extension addrtype revision 0 not supported, missing kernel module?\n" +
        "iptables-restore: line 19 failed"

    @Test
    fun `loopback only listeners do not reserve the same port on other local addresses`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        assertTrue(command.contains("-A MXP278bL -d 127.0.0.0/8 -p tcp --dport 48321"))
        assertFalse(command.contains("-A MXP278bL -p tcp --dport 48321"))
        assertTrue(command.contains("-A MXOA278b -d 127.0.0.0/8 -p tcp --dport 48321 -j DROP"))
    }

    @Test
    fun `tether rules need no addrtype for either IP family or LAN bypass setting`() {
        for (ipv6 in listOf(false, true)) {
            for (bypassLan in listOf(false, true)) {
                val plan = plan(ipv6, "wlan0", bypassLan)
                val commands = listOf(
                    TproxyManager.guardInstallCommand(plan, APP_UID),
                    TproxyManager.guardRestoreCommand(plan, APP_UID),
                    TproxyManager.activationCommand(plan, APP_UID),
                    TproxyManager.activationRestoreCommand(plan, APP_UID),
                    TproxyManager.updateCommand(plan, APP_UID, "a", "b"),
                    TproxyManager.verifyCommand(plan.runtimeState, APP_UID),
                    tproxyListenerVerifyCommand(plan.runtimeState),
                    TproxyManager.cleanupCommand(plan.runtimeState, APP_UID),
                )
                commands.forEach { command ->
                    assertFalse(command.contains("addrtype"))
                    assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
                }
            }
        }
    }

    @Test
    fun `dynamic local destination matching removes address snapshot rules`() {
        val base = plan(tetherUpstreamInterface = "wlan0")
        val dynamic = base.copy(runtimeState = base.runtimeState.copy(localAddresses = emptyList(), dynamicLocalAddresses = true))
        val command = TproxyManager.activationCommand(dynamic, APP_UID)
        val verification = TproxyManager.verifyCommand(dynamic.runtimeState, APP_UID)

        assertTrue(command.contains("-A MXP278bA -m addrtype --dst-type LOCAL -j RETURN"))
        assertFalse(command.contains("-A MXP278bA -d 192.168.43.1/32 -j RETURN"))
        assertTrue(verification.contains("has_v4 '-A MXP278bA -m addrtype --dst-type LOCAL -j RETURN'"))
    }

    @Test
    fun `local destination probe checks kernel support and can fall back`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(if (command.startsWith("ip6tables")) 1 else 0, "", "")
        }

        assertFalse(manager.supportsDynamicLocalAddresses(includeIpv6 = true))
        assertEquals(2, commands.size)
        assertTrue(commands.all { it.contains("-m addrtype --dst-type LOCAL") && it.contains("-X MXD278b") })
    }

    @Test
    fun `INPUT guard preserves device services and blocks intercepted traffic until activation`() {
        val command = TproxyManager.guardInstallCommand(plan(tetherUpstreamInterface = "wlan0", bypassLan = false), APP_UID)
        val inputRules = command.substringAfter("iptables -w 2 -t filter -N MXG278bI")
            .substringBefore("iptables -w 2 -t filter -I INPUT")

        assertTrue(inputRules.contains("--mark 0x10000000/0x10000000 -j DROP"))
        assertTrue(inputRules.contains("--dport 53 -j DROP"))
        assertFalse(inputRules.contains("-A MXG278bI -j RETURN"))
        assertFalse(inputRules.contains("-A MXG278bI -j DROP"))
        assertTrue(command.contains("-A MXG278b -j DROP"))
    }

    @Test
    fun `local listener ports are protected without blocking the same port on remote hosts`() {
        val command = TproxyManager.activationCommand(plan(tetherUpstreamInterface = "wlan0", bypassLan = false), APP_UID)
        assertTrue(command.contains("-A MXP278bA -d 192.168.43.1/32 -j RETURN"))
        assertFalse(command.contains("-A MXP278bA -d 192.168.43.1/32 -p tcp --dport 48322 -j DROP"))
        assertTrue(command.contains("-A MXP278bA -d 127.0.0.0/8 -j RETURN"))
        assertFalse(command.contains("-A MXP278bA -p tcp --dport 48322 -j DROP"))
        // Incoming direct connections (including unmarked self-dials) are blocked;
        // remote traffic intercepted into a managed group retains its mark and passes INPUT.
        assertTrue(command.contains("-A MXP278bL -p tcp --dport 48322 -m mark ! --mark 0x10000000/0x10000000 -j DROP"))
        assertTrue(command.contains("-A MXOA278b -o lo -p tcp --dport 48322 -j DROP"))
        assertTrue(command.indexOf("--dport 53 -j TPROXY") < command.indexOf("-d 192.168.43.1/32 -j RETURN"))
        assertFalse(command.contains("-d 192.168.43.0/24 -j RETURN"))
    }

    @Test
    fun `address refresh detects hotspot appearance and IPv6 removal on unchanged upstream`() = runTest {
        var output = "1: lo inet 127.0.0.1/8\n2: rmnet1 inet 198.51.100.2/30"
        val initial = LocalAddresses.parse(output, includeIpv6 = true)
        val manager = TproxyManager(APP_UID) { command ->
            when {
                command == LocalAddresses.COMMAND -> RootShell.Result(0, output, "")
                command.startsWith("ip rule show") -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                else -> RootShell.Result(0, "", "")
            }
        }
        val base = plan(allowIpv6 = true, tetherUpstreamInterface = "rmnet1")
        assertTrue(manager.activate(base.copy(runtimeState = base.runtimeState.copy(localAddresses = initial))).success)
        assertFalse(manager.localAddressesChanged())
        output += "\n3: ap0 inet 192.168.43.1/24\n3: ap0 inet6 2001:db8::1/64"
        assertFalse(manager.localAddressesChanged())
        assertTrue(manager.localAddressesChanged())
        assertTrue(
            manager.activate(
                base.copy(runtimeState = base.runtimeState.copy(localAddresses = LocalAddresses.parse(output, includeIpv6 = true))),
            ).success,
        )
        output = output.substringBefore("\n3: ap0 inet6")
        assertFalse(manager.localAddressesChanged())
        assertTrue(manager.localAddressesChanged())
    }

    @Test
    fun `tether address refresh replaces only prerouting rules and keeps the core ports`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(0, "", "")
        }
        val base = plan(tetherUpstreamInterface = "wlan0")
        val updated = base.copy(runtimeState = base.runtimeState.copy(localAddresses = listOf("127.0.0.1/32", "192.168.44.1/32"), tetherChainSlot = "b"))

        assertTrue(manager.updateTetherAddresses(updated).success)
        val command = commands.single()
        assertTrue(command.contains("iptables-restore --noflush -w 2"))
        assertTrue(command.contains("-F MXP278bB"))
        assertTrue(command.contains("-C MXP278b -j MXP278bA"))
        assertTrue(command.contains("-A MXP278bB -d 192.168.44.1/32 -j RETURN"))
        assertTrue(command.contains("-R MXP278b 1 -j MXP278bB"))
        assertFalse(command.contains("-A MXP278bB -d 192.168.43.1/32 -j RETURN"))
        assertFalse(command.contains("-I PREROUTING"))
        assertFalse(command.contains("-N MXP278bL"))
    }

    @Test
    fun `tether upstream refresh guards traffic and swaps rules without changing listeners`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(0, "", "")
        }
        val base = plan(tetherUpstreamInterface = "rmnet0")
        val updated = base.copy(runtimeState = base.runtimeState.copy(tetherChainSlot = "b"))

        assertTrue(manager.updateTetherUpstream(updated, previousUpstream = "wlan0").success)
        assertEquals(6, commands.size)
        assertTrue(commands[0].contains("-A MXG278bP -i wlan0 -j ACCEPT"))
        assertTrue(commands[0].contains("-A MXG278bP -i rmnet0 -j ACCEPT"))
        assertTrue(commands[1].contains("-I FORWARD 1 -j MXG278b"))
        assertTrue(commands[2].contains("-F MXP278bB"))
        assertTrue(commands[2].contains("-A MXP278bB -i rmnet0 -j RETURN"))
        assertTrue(commands[2].contains("-R MXP278b 1 -j MXP278bB"))
        assertTrue(commands[2].contains("-F MXP278bBI"))
        assertTrue(commands[2].contains("-C MXP278bI -j MXP278bAI"))
        assertTrue(commands[2].contains("-A MXP278bBI -i rmnet0 -j RETURN"))
        assertTrue(commands[4].contains("-D PREROUTING -j MXG278bP"))
        assertTrue(commands[5].contains("-D FORWARD -j MXG278b"))
        assertFalse(commands[2].contains("-I PREROUTING"))
    }

    @Test
    fun `failed upstream refresh retains transition guard for reconnect`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(if (commands.size == 3) 1 else 0, "", "update failed")
        }

        val base = plan(tetherUpstreamInterface = "rmnet0")
        assertFalse(manager.updateTetherUpstream(base.copy(runtimeState = base.runtimeState.copy(tetherChainSlot = "b")), previousUpstream = "wlan0").success)
        assertEquals(3, commands.size)
        assertTrue(commands.first().contains("-I PREROUTING 1 -j MXG278bP"))
        assertTrue(commands[1].contains("-I FORWARD 1 -j MXG278b"))
    }

    @Test
    fun `IPv6 changes are ignored when IPv6 routing is disabled`() = runTest {
        var output = "1: lo inet 127.0.0.1/8\n2: rmnet1 inet 198.51.100.2/30\n2: rmnet1 inet6 2001:db8::1/64"
        val manager = TproxyManager(APP_UID) { command ->
            if (command == LocalAddresses.COMMAND) {
                RootShell.Result(0, output, "")
            } else {
                RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
            }
        }
        val base = plan(tetherUpstreamInterface = "rmnet1")
        val state = base.runtimeState.copy(localAddresses = LocalAddresses.parse(output, includeIpv6 = false))
        assertTrue(manager.activate(base.copy(runtimeState = state)).success)

        output = output.replace("2001:db8::1", "2001:db8::2")
        assertFalse(manager.localAddressesChanged())
        assertFalse(manager.localAddressesChanged())
    }

    @Test
    fun `verification leaves local address refresh to the stable background monitor`() = runTest {
        var output = "1: lo inet 127.0.0.1/8\n2: rmnet1 inet 198.51.100.2/30"
        val initial = LocalAddresses.parse(output)
        val manager = TproxyManager(APP_UID) { command ->
            when {
                command == LocalAddresses.COMMAND -> RootShell.Result(0, output, "")
                command.startsWith("ip rule show") -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                else -> RootShell.Result(0, "", "")
            }
        }
        val base = plan(tetherUpstreamInterface = "rmnet1")
        val state = base.runtimeState.copy(localAddresses = initial)
        assertTrue(manager.activate(base.copy(runtimeState = state)).success)

        output += "\n3: ap0 inet 192.168.43.1/24"
        assertTrue(manager.verify(state).success)
        assertTrue(manager.verify(state).success)
        assertFalse(manager.localAddressesChanged())
        assertTrue(manager.localAddressesChanged())
    }

    @Test
    fun `restored runtime seeds local address tracking from persisted state`() = runTest {
        var output = "1: lo inet 127.0.0.1/8\n2: rmnet1 inet 198.51.100.2/30\n2: rmnet1 inet6 2001:db8::1/64"
        val manager = TproxyManager(APP_UID) { command ->
            if (command == LocalAddresses.COMMAND) {
                RootShell.Result(0, output, "")
            } else {
                RootShell.Result(0, "", "")
            }
        }
        val state = plan(tetherUpstreamInterface = "rmnet1").runtimeState.copy(
            localAddresses = LocalAddresses.parse(output, includeIpv6 = false),
        )

        assertTrue(manager.verify(state).success)
        assertFalse(manager.localAddressesChanged())
        output = output.replace("2001:db8::1", "2001:db8::2")
        assertFalse(manager.localAddressesChanged())
    }

    @Test
    fun `bulk activation is confirmed by checking only the core listeners`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            if (command.startsWith("ip rule show")) {
                RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
            } else {
                RootShell.Result(0, "", "")
            }
        }
        val plan = plan()
        assertTrue(manager.activate(plan).success)

        assertTrue(manager.verifyActivation(plan.runtimeState).success)

        assertEquals(tproxyListenerVerifyCommand(plan.runtimeState), commands.last())
        assertFalse(commands.last().contains(" -S"))
        assertTrue(commands.last().contains("has_port \"\$tcp_listeners\" 48321"))
        assertTrue(commands.last().contains("has_port \"\$udp_listeners\" 48321"))
    }

    @Test
    fun `activation applied rule by rule is verified in full`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when {
                command.startsWith("ip rule show") -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                command.contains("-restore") -> RootShell.Result(127, "", "")
                else -> RootShell.Result(0, "", "")
            }
        }
        val plan = plan()
        assertTrue(manager.activate(plan).success)

        assertTrue(manager.verifyActivation(plan.runtimeState).success)

        assertEquals(TproxyManager.verifyCommand(plan.runtimeState, APP_UID), commands.last())
    }

    @Test
    fun `legacy tether runtime without address snapshot is not restored as healthy`() = runTest {
        val manager = TproxyManager(APP_UID) { RootShell.Result(0, "1: lo inet 127.0.0.1/8", "") }
        val state = plan(tetherUpstreamInterface = "wlan0").runtimeState.copy(localAddresses = emptyList())
        assertFalse(manager.verify(state).success)
    }

    @Test
    fun `activation extension fallback preserves startup guard and rechecks route ownership`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when (commands.size) {
                1, 4 -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                2 -> RootShell.Result(1, "", extensionError)
                else -> RootShell.Result(0, "", "")
            }
        }

        assertTrue(manager.activate(plan()).success)
        assertEquals(5, commands.size)
        assertFalse(commands[2].contains("-D OUTPUT -j MXG278b"))
        assertFalse(commands[2].contains("-F MXG278b 2>/dev/null"))
        assertEquals(commands[0], commands[3])
        assertFalse(commands[4].contains("iptables-restore"))
    }

    @Test
    fun `IPv4 only activation does not inspect unused IPv6 routing`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
        }

        assertTrue(manager.activate(plan(allowIpv6 = false)).success)
        assertFalse(commands.first().contains("ip -6 rule show"))
        assertFalse(commands.first().contains("ip -6 route show"))
    }

    @Test
    fun `activation fallback stops if rollback leaves a conflicting route`() = runTest {
        var calls = 0
        val manager = TproxyManager(APP_UID) {
            calls++
            when (calls) {
                1 -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                2 -> RootShell.Result(1, "", extensionError)
                4 -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\nlocal default dev lo", "")
                else -> RootShell.Result(0, "", "")
            }
        }

        assertFalse(manager.activate(plan()).success)
        assertEquals(4, calls)
    }

    @Test
    fun `cleanup rejects failed inspection and orphan chains but permits similarly named chains`() {
        val command = TproxyManager.guardCleanupCommand(APP_UID)
        for ((snapshot, expected) in listOf("-N MXG278b" to 1, "-N MXG278b0" to 0, "-N other" to 0)) {
            val stub = "iptables() { case \"\$*\" in *' -S') printf '%s\\n' '$snapshot';; *) return 1;; esac; }; " +
                "ip6tables() { iptables \"\$@\"; }; "
            assertEquals(snapshot, expected, ProcessBuilder("sh", "-c", stub + command).start().waitFor())
        }
        val failedInspection = "iptables() { return 1; }; ip6tables() { return 1; }; "
        assertEquals(1, ProcessBuilder("sh", "-c", failedInspection + command).start().waitFor())
    }

    @Test
    fun `non tether cleanup cannot hide a failed IPv4 guard restore`() {
        val stubs = """
            iptables-restore() {
                case "${'$'}*" in *--help*) printf '%s\n' '--noflush --wait';; *) cat >/dev/null; return 1;; esac
            }
            ip6tables-restore() { iptables-restore "${'$'}@"; }
            iptables() { case "${'$'}*" in *' -S') return 0;; *) return 1;; esac; }
            ip6tables() { iptables "${'$'}@"; }
        """.trimIndent()
        val command = TproxyManager.guardRestoreCommand(plan(), APP_UID)
        // bash permits command-name functions containing hyphens, like the Android tools.
        assertEquals(1, ProcessBuilder("bash", "-c", "$stubs\n$command").start().waitFor())
    }

    @Test
    fun `non tether cleanup cannot hide a failed standalone guard setup`() {
        val stubs = "iptables() { return 1; }; ip6tables() { return 1; }; "
        val command = TproxyManager.guardInstallCommand(plan(), APP_UID)
        assertEquals(1, ProcessBuilder("sh", "-c", stubs + command).start().waitFor())
    }

    @Test
    fun `extension restore failure retries after rollback and keeps standalone mode`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when {
                commands.size == 1 -> RootShell.Result(1, "", extensionError)
                command.contains("grep '^-A OUTPUT '") -> RootShell.Result(1, "", "guard absent")
                command.startsWith("ip rule show") -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                else -> RootShell.Result(0, "", "")
            }
        }

        assertTrue(manager.installGuard(plan()).success)
        assertTrue(manager.activate(plan()).success)
        assertEquals(6, commands.size)
        assertTrue(commands[2].contains("rules=\$(iptables -w 2 -t mangle -S) || exit 1"))
        assertTrue(commands[3].contains("iptables -w 2 -t mangle -N"))
        assertFalse(commands[5].contains("iptables-restore"))
    }

    @Test
    fun `extension fallback stops when guard rollback fails`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(1, "", if (commands.size == 1) extensionError else "inspection failed")
        }

        val result = manager.installGuard(plan())

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("rollback"))
        assertEquals(3, commands.size)
    }

    @Test
    fun `missing extension in standalone setup remains fatal and retains restore diagnostics`() = runTest {
        var calls = 0
        val manager = TproxyManager(APP_UID) { command ->
            calls++
            when {
                calls == 1 || (command.contains("-N MXG278b") && command.contains("-I OUTPUT 1")) ->
                    RootShell.Result(1, "", extensionError)
                command.contains("grep '^-A OUTPUT '") -> RootShell.Result(1, "", "guard absent")
                else -> RootShell.Result(0, "", "")
            }
        }

        val result = manager.installGuard(plan())

        assertFalse(result.success)
        assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("restore failure:"))
        assertEquals(5, calls)
    }

    @Test
    fun `restore payloads wait for lock without embedding wait flags in rules`() {
        val command = TproxyManager.guardRestoreCommand(plan(), APP_UID)

        assertTrue(command.contains("iptables-restore --noflush -w 2"))
        assertTrue(command.contains("ip6tables-restore --noflush -w 2"))
        assertFalse(command.contains("\n-w 2"))
        assertTrue(command.contains("\n-A MXG278b"))
    }

    @Test
    fun `activation routes marks locally before hooking output interception`() {
        val plan = plan()

        val command = TproxyManager.activationCommand(plan, APP_UID)

        assertTrue(command.indexOf("ip route replace local") < command.indexOf("-I OUTPUT 2"))
        assertTrue(command.indexOf("-I PREROUTING 1") < command.indexOf("-I OUTPUT 2"))
        assertTrue(command.contains("--mark 0x10000000/0x10000000"))
        assertTrue(command.contains("--tproxy-mark 0x10200000/0x1fe00000"))
        assertTrue(command.contains("--on-ip 127.0.0.1"))
        assertTrue(command.contains("--on-port 48321"))
    }

    @Test
    fun `activation leaves startup guard installed until verification`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)

        assertFalse(command.contains("MXG278b"))
        assertTrue(command.contains("-I OUTPUT 2 -j MXO278b"))
    }

    @Test
    fun `restore activation checks support and cleans partial state before fallback`() {
        val command = TproxyManager.activationRestoreCommand(plan(), APP_UID)

        assertTrue(command.contains("command -v iptables-restore"))
        assertTrue(command.contains("command -v ip6tables-restore"))
        assertTrue(command.contains("iptables-restore --help"))
        assertTrue(command.contains("ip6tables-restore --help"))
        assertTrue(command.contains("iptables-restore --noflush"))
        assertTrue(command.contains("ip6tables-restore --noflush"))
        assertTrue(command.contains("status=\$?"))
        assertTrue(command.contains("iptables -w 2 -t mangle -D OUTPUT -j MXO278b"))
        assertTrue(command.contains("exit \$status"))
    }

    @Test
    fun `activation falls back to individual commands when restore fails`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when (commands.size) {
                1, 4 -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                2 -> RootShell.Result(127, "", "restore unavailable")
                else -> RootShell.Result(0, "", "")
            }
        }

        val result = manager.activate(plan())

        assertTrue(result.success)
        assertEquals(5, commands.size)
        assertTrue(commands[1].contains("iptables-restore"))
        assertTrue(commands[2].contains("rules=\$(iptables"))
        assertTrue(commands[4].contains("iptables -w 2 -t mangle -N"))
    }

    @Test
    fun `activation does not fall back over a failed restore transaction`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when (commands.size) {
                1 -> RootShell.Result(0, "\n__MXRAY_TPROXY_ROUTES__\n", "")
                else -> RootShell.Result(1, "", "restore failed")
            }
        }

        val result = manager.activate(plan())

        assertFalse(result.success)
        assertEquals(2, commands.size)
    }

    @Test
    fun `output rules exempt xray process app and bypass uids before assigning marks`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        val clearXrayMark = command.indexOf(
            "--gid-owner $APP_UID -m mark --mark 0x10000000/0x10000000 " +
                "-j MARK --set-xmark 0x0/0x1fe00000",
        )
        val exemptXray = command.indexOf("--gid-owner $APP_UID -j RETURN")
        val exemptApp = command.indexOf("--uid-owner $APP_UID -j RETURN")
        val exemptBypass = command.indexOf("--uid-owner 10020 -j RETURN")
        val groupMark = command.indexOf("--uid-owner 10030 -p tcp -j MARK")
        val groupReturn = command.indexOf("--mark 0x10000000/0x10000000 -j RETURN", groupMark)
        val profileMark = command.indexOf("--uid-owner 10000-99999 -p tcp -j MARK")

        assertTrue(clearXrayMark in 0..<exemptXray)
        assertTrue(exemptXray < exemptApp)
        assertTrue(exemptApp < exemptBypass)
        assertTrue(exemptBypass < groupMark)
        assertTrue(groupMark < groupReturn)
        assertTrue(groupReturn < profileMark)
        assertTrue(command.contains("--uid-owner 10000-99999 -j DROP"))
        assertTrue(command.split("--uid-owner $APP_UID -j RETURN").size - 1 == 2)
        assertFalse(command.contains("--mark 255"))
        assertFalse(command.contains("/0xffffffff"))
    }

    @Test
    fun `verification requires xray marks to be cleared before the gid exemption`() {
        val command = TproxyManager.verifyCommand(plan().runtimeState, APP_UID)
        val clearXrayMark = command.indexOf(
            "--gid-owner $APP_UID -m mark --mark 0x10000000/0x10000000 " +
                "-j MARK --set-xmark 0x0/0x1fe00000",
        )
        val exemptXray = command.indexOf("--gid-owner $APP_UID -j RETURN")

        assertTrue(clearXrayMark in 0..<exemptXray)
        assertTrue(command.contains("has_v4_order"))
    }

    @Test
    fun `output rules route resolver DNS through the base inbound`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        val bypass = command.indexOf("--uid-owner 10020 -j RETURN")
        val udpDns = command.indexOf("-p udp --dport 53 -j MARK --set-xmark 0x10200000/0x1fe00000")
        val appRoute = command.indexOf("--uid-owner 10030 -p tcp -j MARK")

        assertTrue(bypass in 0..<udpDns)
        assertTrue(udpDns < appRoute)
        assertTrue(command.contains("-p tcp --dport 53 -j MARK --set-xmark 0x10200000/0x1fe00000"))
    }

    @Test
    fun `forced app DNS is marked before the base resolver rule`() {
        val original = plan()
        val forcedState = original.runtimeState.groups[1].copy(routeKey = -7L)
        val forcedPlan = original.copy(
            runtimeState = original.runtimeState.copy(groups = listOf(original.runtimeState.groups[0], forcedState)),
            groups = listOf(original.groups[0], original.groups[1].copy(state = forcedState)),
        )
        val command = TproxyManager.activationCommand(forcedPlan, APP_UID)
        val forcedMark = command.indexOf("--uid-owner 10030 -p udp --dport 53 -j MARK --set-xmark 0x10400000/0x1fe00000")
        val forcedReturn = command.indexOf("--uid-owner 10030 -p udp --dport 53 -j RETURN")
        val baseMark = command.indexOf("-p udp --dport 53 -j MARK --set-xmark 0x10200000/0x1fe00000")

        assertTrue(forcedMark in 0..<forcedReturn)
        assertTrue(forcedReturn < baseMark)
    }

    @Test
    fun `LAN destinations skip the core after DNS is captured`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        val dnsMark = command.indexOf("-p udp --dport 53 -j MARK --set-xmark 0x10200000/0x1fe00000")
        val lanReturn = command.indexOf("-A MXOA278b -d 192.168.0.0/16 -m mark ! --mark 0x10000000/0x10000000 -j RETURN")
        val groupMark = command.indexOf("--uid-owner 10030 -p tcp -j MARK")
        val profileMark = command.indexOf("--uid-owner 10000-99999 -p tcp -j MARK")

        assertTrue(dnsMark in 0..<lanReturn)
        assertTrue(lanReturn < groupMark)
        assertTrue(groupMark < profileMark)
        listOf("10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12", "169.254.0.0/16").forEach { cidr ->
            assertTrue(command.contains("-A MXOA278b -d $cidr -m mark ! --mark 0x10000000/0x10000000 -j RETURN"))
        }
    }

    @Test
    fun `LAN destinations stay intercepted when LAN bypass is disabled`() {
        val command = TproxyManager.activationCommand(plan(allowIpv6 = true, bypassLan = false), APP_UID)

        assertFalse(command.contains("-A MXOA278b -d 192.168.0.0/16"))
        assertFalse(command.contains("-A MXOA278b -d fc00::/7"))
    }

    @Test
    fun `forced apps keep sending LAN traffic to their server`() {
        val original = plan()
        val forcedState = original.runtimeState.groups[1].copy(routeKey = -7L)
        val forcedPlan = original.copy(
            runtimeState = original.runtimeState.copy(groups = listOf(original.runtimeState.groups[0], forcedState)),
            groups = listOf(original.groups[0], original.groups[1].copy(state = forcedState)),
        )
        val command = TproxyManager.activationCommand(forcedPlan, APP_UID)
        val forcedMark = command.indexOf("--uid-owner 10030 -p tcp -j MARK --set-xmark 0x10400000/0x1fe00000")
        val lanReturn = command.indexOf("-A MXOA278b -d 192.168.0.0/16")
        val forcedReject = command.indexOf("-A MXOA278b -m owner --uid-owner 10030 -j REJECT --reject-with icmp6-no-route")
        val ipv6LanReturn = command.indexOf("-A MXOA278b -d fc00::/7 -j RETURN")

        assertTrue(forcedMark in 0..<lanReturn)
        assertTrue(forcedReject in 0..<ipv6LanReturn)
    }

    @Test
    fun `default selected apps follow routing rules and skip the core for LAN`() {
        val original = plan()
        val defaultState = original.runtimeState.groups[1].copy(routeKey = Long.MIN_VALUE)
        val defaultPlan = original.copy(
            runtimeState = original.runtimeState.copy(groups = listOf(original.runtimeState.groups[0], defaultState)),
            groups = listOf(original.groups[0], original.groups[1].copy(state = defaultState)),
        )
        val command = TproxyManager.activationCommand(defaultPlan, APP_UID)
        val lanReturn = command.indexOf("-A MXOA278b -d 192.168.0.0/16")
        val groupMark = command.indexOf("--uid-owner 10030 -p tcp -j MARK")

        assertTrue(lanReturn in 0..<groupMark)
        assertFalse(command.contains("--uid-owner 10030 -j REJECT"))
    }

    @Test
    fun `disabled IPv6 still allows LAN addresses without leaking DNS`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        val bypassExempt = command.indexOf("ip6tables -w 2 -t filter -A MXOA278b -m owner --uid-owner 10020 -j RETURN")
        val dnsReject = command.indexOf(
            "ip6tables -w 2 -t filter -A MXOA278b -m owner --uid-owner 10000-99999 -p udp --dport 53 " +
                "-j REJECT --reject-with icmp6-no-route",
        )
        val ulaReturn = command.indexOf("ip6tables -w 2 -t filter -A MXOA278b -d fc00::/7 -j RETURN")
        val linkLocalReturn = command.indexOf("ip6tables -w 2 -t filter -A MXOA278b -d fe80::/10 -j RETURN")
        val profileReject = command.indexOf(
            "ip6tables -w 2 -t filter -A MXOA278b -m owner --uid-owner 10000-99999 -j REJECT --reject-with icmp6-no-route",
        )

        assertTrue(bypassExempt in 0..<dnsReject)
        assertTrue(dnsReject < ulaReturn)
        assertTrue(ulaReturn < linkLocalReturn)
        assertTrue(linkLocalReturn < profileReject)
        assertFalse(
            TproxyManager.activationCommand(plan(bypassLan = false), APP_UID)
                .contains("ip6tables -w 2 -t filter -A MXOA278b -d fc00::/7"),
        )
    }

    @Test
    fun `enabled IPv6 skips the core for IPv6 LAN destinations`() {
        val command = TproxyManager.activationCommand(plan(allowIpv6 = true), APP_UID)

        assertTrue(command.contains("ip6tables -w 2 -t mangle -A MXOA278b -d fc00::/7 -m mark ! --mark 0x10000000/0x10000000 -j RETURN"))
        assertTrue(command.contains("ip6tables -w 2 -t mangle -A MXOA278b -d fe80::/10 -m mark ! --mark 0x10000000/0x10000000 -j RETURN"))
        assertFalse(command.contains("ip6tables -w 2 -t mangle -A MXOA278b -d 192.168.0.0/16"))
    }

    @Test
    fun `disabled IPv6 rejects managed apps instead of blackholing them`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)

        assertTrue(command.contains("ip6tables -w 2 -t filter -I OUTPUT 1"))
        assertTrue(command.contains("-j REJECT --reject-with icmp6-no-route"))
        assertFalse(command.contains("ip6tables -w 2 -t mangle -I OUTPUT 1"))
        assertFalse(command.contains("ip6tables -w 2 -t mangle -I PREROUTING 1"))
        assertFalse(command.contains("ip -6 rule add"))
        assertFalse(command.contains("ip -6 route replace"))
    }

    @Test
    fun `disabled IPv6 keeps bypassed apps and xray itself on IPv6`() {
        val command = TproxyManager.activationCommand(plan(), APP_UID)
        val appExempt = command.indexOf("ip6tables -w 2 -t filter -A MXOA278b -m owner --uid-owner $APP_UID -j RETURN")
        val bypassExempt = command.indexOf("ip6tables -w 2 -t filter -A MXOA278b -m owner --uid-owner 10020 -j RETURN")
        val reject = command.indexOf("-j REJECT --reject-with icmp6-no-route")

        assertTrue(appExempt in 0..<reject)
        assertTrue(bypassExempt in 0..<reject)
    }

    @Test
    fun `startup guard always covers IPv6`() {
        val command = TproxyManager.guardInstallCommand(plan(), APP_UID)

        assertTrue(command.contains("ip6tables -w 2 -t mangle -I OUTPUT 1"))
    }

    @Test
    fun `guard removal batches both families when restore is available`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(0, "", "")
        }

        assertTrue(manager.installGuard(plan()).success)
        assertTrue(manager.removeGuard())
        assertEquals(2, commands.size)
        assertTrue(commands.last().contains("iptables-restore --noflush -w 2"))
        assertTrue(commands.last().contains("ip6tables-restore --noflush -w 2"))
        assertFalse(commands.last().contains("-t filter -S"))
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", commands.last()).start().waitFor())
    }

    @Test
    fun `tether guard removal batches filter hooks before mangle guard`() {
        val manager = TproxyManager(APP_UID) { RootShell.Result(0, "", "") }
        val command = manager.guardRemovalRestoreCommand(includeFilterTables = true)

        assertTrue(command.indexOf("*filter") < command.indexOf("*mangle"))
        assertTrue(command.contains("-D INPUT -j MXG278bI"))
        assertTrue(command.contains("-D FORWARD -j MXG278b"))
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
    }

    @Test
    fun `failed bulk guard removal falls back to audited cleanup`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(if (commands.size == 2) 1 else 0, "", "")
        }

        assertTrue(manager.installGuard(plan()).success)
        assertTrue(manager.removeGuard())
        assertEquals(3, commands.size)
        assertTrue(commands.last().contains("rules=\$(iptables -w 2 -t mangle -S) || exit 1"))
    }

    @Test
    fun `startup guard exempts xray without blocking the shared resolver`() {
        val command = TproxyManager.guardInstallCommand(plan(), APP_UID)
        val clearXrayMark = command.indexOf(
            "--gid-owner $APP_UID -m mark --mark 0x10000000/0x10000000 " +
                "-j MARK --set-xmark 0x0/0x1fe00000",
        )
        val exemptXray = command.indexOf("--gid-owner $APP_UID -j RETURN")
        val profileDrop = command.indexOf("--uid-owner 10000-99999 -j DROP")

        assertTrue(clearXrayMark in 0..<exemptXray)
        assertTrue(exemptXray < profileDrop)
        assertFalse(command.contains("--dport 53 -j DROP"))
    }

    @Test
    fun `restore refreshes an existing guard atomically and verifies its content`() {
        val command = TproxyManager.guardRestoreCommand(plan(), APP_UID)

        assertTrue(command.contains("\n-D OUTPUT -j MXG278b\n-F MXG278b\n"))
        assertTrue(command.contains("\n-I OUTPUT 1 -j MXG278b\nCOMMIT"))
        assertTrue(command.contains("actual="))
        assertTrue(command.contains("-S MXG278b"))
    }

    @Test
    fun `cold guard setup skips deletion when owned chains are absent`() {
        val command = TproxyManager.guardRestoreCommand(plan(), APP_UID)

        for (tool in FirewallCommands.tools) {
            assertTrue(
                command.contains(
                    "if $tool -t mangle -S MXG278b >/dev/null 2>&1; then " +
                        "if $tool -t mangle -C OUTPUT -j MXG278b 2>/dev/null; then",
                ),
            )
            assertTrue(command.contains("else $tool -t mangle -D OUTPUT -j MXG278b"))
            assertTrue(
                command.contains(
                    "if $tool -t filter -S MXG278b >/dev/null 2>&1 || " +
                        "$tool -t filter -S MXG278bI >/dev/null 2>&1; then " +
                        "$tool -t filter -D INPUT -j MXG278b",
                ),
            )
        }
    }

    @Test
    fun `tether guard verification covers both families and forwarding hooks`() {
        val tetherPlan = plan(tetherUpstreamInterface = "wlan0")
        val command = TproxyManager.guardPlanVerifyCommand(tetherPlan, APP_UID)

        assertTrue(command.contains("iptables -w 2 -t mangle -S OUTPUT"))
        assertTrue(command.contains("ip6tables -w 2 -t mangle -S OUTPUT"))
        assertTrue(command.contains("iptables -w 2 -t filter -S INPUT"))
        assertTrue(command.contains("ip6tables -w 2 -t filter -S FORWARD"))
        assertTrue(command.contains("actual=\$(iptables -w 2 -t filter -S MXG278b)"))
    }

    @Test
    fun `restore guard checks support and retains individual command fallback`() {
        val command = TproxyManager.guardRestoreCommand(plan(), APP_UID)

        assertTrue(command.contains("command -v iptables-restore"))
        assertTrue(command.contains("command -v ip6tables-restore"))
        assertTrue(command.contains("iptables-restore --noflush"))
        assertTrue(command.contains("ip6tables-restore --noflush"))
        assertTrue(command.contains("iptables -w 2 -t mangle -D OUTPUT -j MXG278b"))
    }

    @Test
    fun `successful guard restore capability check is reused`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            RootShell.Result(0, "", "")
        }

        assertTrue(manager.installGuard(plan()).success)
        assertTrue(manager.installGuard(plan()).success)

        assertTrue(commands[0].contains("command -v iptables-restore"))
        assertFalse(commands[1].contains("command -v iptables-restore"))
        assertTrue(commands[1].contains("iptables-restore --noflush"))
    }

    @Test
    fun `non tether guard setup removes stale tether hooks`() {
        val restore = TproxyManager.guardRestoreCommand(plan(), APP_UID)
        val fallback = TproxyManager.guardInstallCommand(plan(), APP_UID)

        for (command in listOf(restore, fallback)) {
            assertTrue(command.contains("iptables -w 2 -t filter -D INPUT -j MXG278b"))
            assertTrue(command.contains("iptables -w 2 -t filter -D FORWARD -j MXG278b"))
            assertTrue(command.contains("ip6tables -w 2 -t filter -D INPUT -j MXG278b"))
            assertTrue(command.contains("ip6tables -w 2 -t filter -D FORWARD -j MXG278b"))
        }
    }

    @Test
    fun `failed guard fallback removes partial guards from both families`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when {
                commands.size == 1 -> RootShell.Result(127, "", "restore unavailable")
                command.contains("grep '^-A OUTPUT '") -> RootShell.Result(1, "", "guard absent")
                command.contains("-N MXG278b") && command.contains("-I OUTPUT 1") ->
                    RootShell.Result(1, "", "IPv6 setup failed")
                else -> RootShell.Result(0, "", "")
            }
        }

        val result = manager.installGuard(plan())

        assertFalse(result.success)
        assertEquals(5, commands.size)
        assertTrue(commands[4].contains("iptables -w 2 -t mangle -D OUTPUT -j MXG278b"))
        assertTrue(commands[4].contains("ip6tables -w 2 -t mangle -D OUTPUT -j MXG278b"))
    }

    @Test
    fun `standalone fallback preserves an existing stale guard instead of rebuilding it with a gap`() = runTest {
        val commands = mutableListOf<String>()
        val manager = TproxyManager(APP_UID) { command ->
            commands += command
            when (commands.size) {
                1 -> RootShell.Result(127, "", "restore unavailable")
                2 -> RootShell.Result(0, "", "")
                else -> RootShell.Result(1, "", "guard content changed")
            }
        }

        val result = manager.installGuard(plan())

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("cannot be refreshed safely"))
        assertEquals(3, commands.size)
        assertFalse(commands.drop(1).any { it.contains("-F MXG278b") })
    }

    @Test
    fun `fast update builds inactive slot before replacing the active jump`() {
        val command = TproxyManager.updateCommand(plan(), APP_UID, "a", "b")

        assertTrue(command.indexOf("-N MXOB") < command.indexOf("-R MXO"))
        assertTrue(command.indexOf("-R MXO") < command.indexOf("-F MXOA"))
    }

    @Test
    fun `cleanup only names owned chains and packet marks`() {
        val command = TproxyManager.cleanupCommand(plan().runtimeState, APP_UID)

        assertTrue(command.contains("MXO${APP_UID.toString(16)}"))
        assertTrue(command.contains("fwmark 0x10000000/0x10000000"))
        assertTrue(command.contains("ip6tables -w 2 -t filter -D OUTPUT -j MXO278b"))
        for (chain in listOf("MXP278bA", "MXP278bB", "MXP278bAF", "MXP278bAI", "MXP278bBF", "MXP278bBI")) {
            assertTrue(command.contains("-N $chain"))
        }
        assertFalse(command.contains("iptables-save"))
        assertFalse(command.contains("-F OUTPUT"))
        assertFalse(command.contains("route flush table"))
    }

    @Test
    fun `cleanup skips firewall mutations when reboot cleared owned state`() {
        val command = TproxyManager.cleanupCommand(plan().runtimeState, APP_UID)
        val stubs = """
            iptables() { case "${'$'}*" in *' -S') return 0;; *) echo mutated;; esac; }
            ip6tables() { iptables "${'$'}@"; }
            ip() { case "${'$'}*" in *' del '*) return 1;; *) return 0;; esac; }
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", "$stubs\n$command").start()

        assertEquals(0, process.waitFor())
        assertEquals("", process.inputStream.bufferedReader().readText().trim())
    }

    @Test
    fun `cleanup runs full teardown when an owned chain remains`() {
        val command = TproxyManager.cleanupCommand(plan().runtimeState, APP_UID)
        val stubs = """
            iptables() { case "${'$'}*" in *' -S') echo '-N MXO278b';; *) echo mutated;; esac; }
            ip6tables() { iptables "${'$'}@"; }
            ip() { case "${'$'}*" in *' del '*) return 1;; *) return 0;; esac; }
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", "$stubs\n$command").start()

        assertEquals(1, process.waitFor())
        assertEquals(6, process.inputStream.bufferedReader().readLines().count { it == "mutated" })
    }

    @Test
    fun `cleanup fails when a policy rule remains after teardown`() {
        val command = TproxyManager.cleanupCommand(plan().runtimeState, APP_UID)
        val stubs = """
            iptables() { return 0; }
            ip6tables() { return 0; }
            ip() {
                case "${'$'}*" in
                    'rule show') echo '9990: from all fwmark 0x10000000/0x10000000 lookup 300';;
                    *' del '*) return 1;;
                esac
            }
        """.trimIndent()

        assertEquals(1, ProcessBuilder("sh", "-c", "$stubs\n$command").start().waitFor())
    }

    @Test
    fun `enabled IPv6 uses transparent proxying rather than rejection`() {
        val command = TproxyManager.activationCommand(plan(allowIpv6 = true), APP_UID)

        assertTrue(command.contains("ip -6 route replace local ::/0 dev lo table 300"))
        assertTrue(command.contains("ip6tables -w 2 -t mangle -I PREROUTING 1"))
        assertTrue(command.contains("--on-ip 0.0.0.0"))
        assertTrue(command.contains("--on-ip ::"))
        assertFalse(command.contains("addrtype"))
        assertEquals(2, command.split("-p udp --dport 53 -j MARK --set-xmark 0x10200000/0x1fe00000").size - 1)
        assertFalse(command.contains("ip6tables -w 2 -t filter"))
        assertFalse(command.contains("icmp6-no-route"))
    }

    @Test
    fun `tether traffic uses base inbound without intercepting upstream or LAN`() {
        val command = TproxyManager.activationCommand(plan(tetherUpstreamInterface = "wlan0"), APP_UID)
        val upstreamReturn = command.indexOf("-i wlan0 -j RETURN")
        val dnsCapture = command.indexOf("--dport 53 -j TPROXY --on-ip 0.0.0.0 --on-port 48321")
        val lanReturn = command.indexOf("-d 192.168.0.0/16 -j RETURN")
        val publicCapture = command.indexOf("-p tcp -j TPROXY --on-ip 0.0.0.0 --on-port 48321")

        assertTrue(upstreamReturn in 0..<dnsCapture)
        assertTrue(dnsCapture < lanReturn)
        assertTrue(lanReturn < publicCapture)
        assertTrue(command.contains("ip6tables -w 2 -t filter -I INPUT 1 -j MXP278bI"))
        assertTrue(command.contains("ip6tables -w 2 -t filter -I FORWARD 1 -j MXP278b"))
        assertTrue(command.contains("ip6tables -w 2 -t filter -A MXP278bAF -j REJECT --reject-with icmp6-no-route"))
    }

    @Test
    fun `tether startup guard blocks public forwarding until activation`() {
        val command = TproxyManager.guardInstallCommand(plan(tetherUpstreamInterface = "wlan0"), APP_UID)

        assertTrue(command.contains("iptables -w 2 -t filter -I INPUT 1 -j MXG278bI"))
        assertTrue(command.contains("iptables -w 2 -t filter -I FORWARD 1 -j MXG278b"))
        assertTrue(command.contains("iptables -w 2 -t filter -A MXG278b -i wlan0 -j RETURN"))
        assertTrue(command.contains("iptables -w 2 -t filter -A MXG278b -j DROP"))
    }

    @Test
    fun `tether traffic does not bypass private destinations when LAN bypass is disabled`() {
        val command = TproxyManager.activationCommand(
            plan(tetherUpstreamInterface = "wlan0", bypassLan = false),
            APP_UID,
        )

        assertFalse(command.contains("MXP278b -d 192.168.0.0/16 -j RETURN"))
    }

    @Test
    fun `tether restore payloads use validated interface without shell quotes`() {
        val plan = plan(tetherUpstreamInterface = "wlan0")
        val activation = TproxyManager.activationRestoreCommand(plan, APP_UID)
        val guard = TproxyManager.guardRestoreCommand(plan, APP_UID)

        assertTrue(activation.contains("-i wlan0 -j RETURN"))
        assertTrue(guard.contains("-i wlan0 -j RETURN"))
        assertFalse(activation.contains("-i '\\''wlan0'\\'' -j RETURN"))
        assertFalse(guard.contains("-i '\\''wlan0'\\'' -j RETURN"))
    }

    @Test
    fun `health verification covers marks UDP and live listeners`() {
        val command = TproxyManager.verifyCommand(plan().runtimeState, APP_UID)

        assertTrue(command.contains("--set-xmark 0x10200000/0x1fe00000"))
        assertTrue(command.contains("-p udp -m mark --mark 0x10200000/0x1fe00000"))
        assertTrue(command.contains("-d 127.0.0.0/8 -p udp -m udp --dport 48321 -j DROP"))
        assertTrue(
            command.contains(
                "has_v4 '-A MXOA278b -p udp -m udp --dport 53 " +
                    "-j MARK --set-xmark 0x10200000/0x1fe00000",
            ),
        )
        assertTrue(command.contains("listeners=\$(ss -lntu)"))
        assertEquals(1, command.split("ss -lntu").size - 1)
        assertFalse(command.contains("ss -lnu"))
        assertEquals(2, command.split("iptables -w 2 -t mangle -S").size - 1)
        assertTrue(command.contains("v4_slot_rules=\$(iptables -w 2 -t mangle -S MXOA278b)"))
        assertTrue(command.contains("*\"\$newline\$1\$newline\$2\$newline\"*"))
        assertFalse(command.contains("*\"\$newline\$1\$newline\"*\"\$newline\$2\$newline\"*"))
        assertTrue(command.contains("has_v6 '-A OUTPUT"))
        assertTrue(command.contains("--reject-with icmp6-no-route"))
        assertTrue(command.contains("v6_slot_rules=\$(ip6tables -w 2 -t filter -S MXOA278b)"))
        assertFalse(command.contains("ip6tables -w 2 -t mangle -S"))
        assertFalse(command.contains("ip -6 rule show"))
        assertTrue(command.contains("lookup 300([[:space:]]|$)"))
        assertTrue(command.contains("^local default dev lo([[:space:]]|$)"))
    }

    @Test
    fun `periodic health check detects missing hooks`() {
        val command = TproxyManager(APP_UID) { RootShell.Result(0, "", "") }.healthCheckCommand(plan().runtimeState)
        val stubs = """
            iptables() { printf '%s\n' '-A OUTPUT -j MXO278b' '-A PREROUTING -j MXP278b'; }
            ip6tables() { echo '-A OUTPUT -j MXO278b'; }
            ip() {
                case "${'$'}*" in
                    'rule show') echo '9990: from all fwmark 0x10000000/0x10000000 lookup 300';;
                    'route show table 300') echo 'local default dev lo table 300';;
                esac
            }
            ss() { printf '%s\n' 'tcp LISTEN 0 5 127.0.0.1:48321 0.0.0.0:*' 'udp UNCONN 0 0 127.0.0.1:48321 0.0.0.0:*' 'tcp LISTEN 0 5 127.0.0.1:48322 0.0.0.0:*' 'udp UNCONN 0 0 127.0.0.1:48322 0.0.0.0:*'; }
        """.trimIndent()

        assertEquals(0, ProcessBuilder("sh", "-c", "$stubs\n$command").start().waitFor())
        val missingHook = stubs.replace("'-A PREROUTING -j MXP278b'", "'-A OTHER -j MXP278b'")
        assertEquals(1, ProcessBuilder("sh", "-c", "$missingHook\n$command").start().waitFor())
        val missingListener = stubs.replace("'udp UNCONN 0 0 127.0.0.1:48322 0.0.0.0:*'", "")
        assertEquals(1, ProcessBuilder("sh", "-c", "$missingListener\n$command").start().waitFor())
        val splitRule = stubs.replace(
            "echo '9990: from all fwmark 0x10000000/0x10000000 lookup 300'",
            "printf '%s\\n' '9990: from all fwmark 0x10000000/0x10000000 lookup 301' '9991: from all lookup 300'",
        )
        assertEquals(1, ProcessBuilder("sh", "-c", "$splitRule\n$command").start().waitFor())
        val splitRoute = stubs.replace(
            "echo 'local default dev lo table 300'",
            "printf '%s\\n' 'local default dev eth0 table 300' 'unicast default dev lo table 300'",
        )
        assertEquals(1, ProcessBuilder("sh", "-c", "$splitRoute\n$command").start().waitFor())
        val wrongTable = stubs.replace("lookup 300'", "lookup 3000'")
        assertEquals(1, ProcessBuilder("sh", "-c", "$wrongTable\n$command").start().waitFor())
        val nonDefaultRoute = stubs.replace("local default dev lo", "local 127.0.0.1 dev lo")
        assertEquals(1, ProcessBuilder("sh", "-c", "$nonDefaultRoute\n$command").start().waitFor())
    }

    @Test
    fun `tether health verification matches canonical loopback output rule order`() {
        val command = TproxyManager.verifyCommand(
            plan(allowIpv6 = true, tetherUpstreamInterface = "wlan0").runtimeState,
            APP_UID,
        )

        assertTrue(
            command.contains(
                "has_v4 '-A MXOA278b -o lo -p tcp -m tcp --dport 48321 -j DROP'",
            ),
        )
        assertTrue(
            command.contains(
                "has_v6 '-A MXOA278b -o lo -p udp -m udp --dport 48321 -j DROP'",
            ),
        )
        assertTrue(
            command.contains(
                "has_v4 '-A MXP278bA -p tcp -m tcp --dport 53 -j TPROXY --on-port 48321 " +
                    "--on-ip 0.0.0.0 --tproxy-mark 0x10200000/0x1fe00000'",
            ),
        )
        assertTrue(
            command.contains(
                "has_v4 '-A MXP278bA -p udp -j TPROXY --on-port 48321 --on-ip 0.0.0.0 " +
                    "--tproxy-mark 0x10200000/0x1fe00000'",
            ),
        )
    }

    private fun plan(
        allowIpv6: Boolean = false,
        tetherUpstreamInterface: String? = null,
        bypassLan: Boolean = true,
    ): TproxyTrafficPlan {
        val state = TproxyManager.createRuntimeState(
            routeTable = 300,
            groups = listOf(Long.MAX_VALUE to "tproxy-in-default", 7L to "app-in-7"),
            ports = listOf(48_321, 48_322),
            allowIpv6 = allowIpv6,
            tetherUpstreamInterface = tetherUpstreamInterface,
            bypassLan = bypassLan,
            localAddresses = listOf("127.0.0.1/32", "192.168.43.1/32", "2001:db8:0:0:0:0:0:1/128"),
        )
        return TproxyTrafficPlan(
            runtimeState = state,
            groups = listOf(
                TproxyTrafficGroup(state.groups[0], emptySet(), isBase = true),
                TproxyTrafficGroup(state.groups[1], setOf(10_030)),
            ),
            bypassUids = setOf(APP_UID, 10_020),
            routeProfileIds = setOf(0),
        )
    }

    private companion object {
        const val APP_UID = 10_123
    }
}
