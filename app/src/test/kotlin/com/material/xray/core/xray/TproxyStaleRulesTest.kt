package com.material.xray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Test

class TproxyStaleRulesTest {

    @Test
    fun `only rules with the exact production signature count as stale`() {
        val output = """
            32765: from all fwmark 0x20000/0xfffff lookup 1027
            9989: from all fwmark 0x10000000/0x10000000 lookup 301
            9990: from all fwmark 0x10000000/0xf0000000 lookup 302
            9990: from all fwmark 0x10200000/0x1fe00000 lookup 303
            9990: from all fwmark 0x10000000 lookup 304
            9991: from all fwmark 0x10000000/0x10000000 lookup 305
            9990: from all fwmark 0x10000000/0x10000000 lookup 300
        """.trimIndent()

        assertEquals(listOf(300), staleTproxyRules(output).map(FwmarkRule::table))
    }

    @Test
    fun `duplicates from several dead installs are all collected`() {
        val output = """
            9990: from all fwmark 0x10000000/0x10000000 lookup 300
            9990: from all fwmark 0x10000000/0x10000000 lookup 301
        """.trimIndent()

        assertEquals(listOf(300, 301), staleTproxyRules(output).map(FwmarkRule::table))
    }

    @Test
    fun `a named lookup table leaves the table unknown instead of guessing`() {
        val output = "9990: from all fwmark 0x10000000/0x10000000 lookup main"

        assertEquals(listOf(null), staleTproxyRules(output).map(FwmarkRule::table))
    }

    @Test
    fun `cleanup deletes every stale rule and flushes the route tables it owns`() {
        val commands = staleTproxyCleanupCommands(
            ipv4Rules = staleTproxyRules("9990: from all fwmark 0x10000000/0x10000000 lookup 300"),
            ipv6Rules = staleTproxyRules("9990: from all fwmark 0x10000000/0x10000000 lookup 300"),
        )

        assertEquals(
            listOf(
                "while ip rule del fwmark 0x10000000/0x10000000 pref 9990 2>/dev/null; do :; done",
                "while ip -6 rule del fwmark 0x10000000/0x10000000 pref 9990 2>/dev/null; do :; done",
                "ip route flush table 300 2>/dev/null || true",
                "ip -6 route flush table 300 2>/dev/null || true",
            ),
            commands,
        )
    }

    @Test
    fun `one family without leftovers gets no delete command of its own`() {
        val commands = staleTproxyCleanupCommands(
            ipv4Rules = staleTproxyRules("9990: from all fwmark 0x10000000/0x10000000 lookup 300"),
            ipv6Rules = emptyList(),
        )

        assertEquals(
            listOf(
                "while ip rule del fwmark 0x10000000/0x10000000 pref 9990 2>/dev/null; do :; done",
                "ip route flush table 300 2>/dev/null || true",
                "ip -6 route flush table 300 2>/dev/null || true",
            ),
            commands,
        )
    }

    @Test
    fun `reserved and named tables are deleted but never flushed`() {
        val output = """
            9990: from all fwmark 0x10000000/0x10000000 lookup 253
            9990: from all fwmark 0x10000000/0x10000000 lookup 254
            9990: from all fwmark 0x10000000/0x10000000 lookup 255
            9990: from all fwmark 0x10000000/0x10000000 lookup main
        """.trimIndent()

        val commands = staleTproxyCleanupCommands(
            ipv4Rules = staleTproxyRules(output),
            ipv6Rules = emptyList(),
        )

        assertEquals(
            listOf("while ip rule del fwmark 0x10000000/0x10000000 pref 9990 2>/dev/null; do :; done"),
            commands,
        )
    }

    @Test
    fun `shared tables are flushed once and in order`() {
        val output = """
            9990: from all fwmark 0x10000000/0x10000000 lookup 301
            9990: from all fwmark 0x10000000/0x10000000 lookup 300
        """.trimIndent()
        val rules = staleTproxyRules(output)

        val commands = staleTproxyCleanupCommands(ipv4Rules = rules, ipv6Rules = rules)

        assertEquals(
            listOf(
                "while ip rule del fwmark 0x10000000/0x10000000 pref 9990 2>/dev/null; do :; done",
                "while ip -6 rule del fwmark 0x10000000/0x10000000 pref 9990 2>/dev/null; do :; done",
                "ip route flush table 300 2>/dev/null || true",
                "ip -6 route flush table 300 2>/dev/null || true",
                "ip route flush table 301 2>/dev/null || true",
                "ip -6 route flush table 301 2>/dev/null || true",
            ),
            commands,
        )
    }

    @Test
    fun `nothing stale produces no commands`() {
        assertEquals(emptyList<String>(), staleTproxyCleanupCommands(emptyList(), emptyList()))
    }

    @Test
    fun `the cleanup is guarded to run only while no runtime state file exists`() {
        val guarded = guardStaleTproxyCleanup(
            commands = listOf("ip route flush table 300 2>/dev/null || true"),
            runtimeStatePath = "/data/data/com.material.xray/files/state.json",
        )

        assertEquals(
            "if [ -e \"/data/data/com.material.xray/files/state.json\" ]; then echo $RUNTIME_STATE_MARKER; " +
                "else ip route flush table 300 2>/dev/null || true; fi",
            guarded,
        )
    }
}
