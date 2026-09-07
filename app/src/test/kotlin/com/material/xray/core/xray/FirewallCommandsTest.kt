package com.material.xray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallCommandsTest {
    @Test
    fun `best effort cleanup cannot mask an earlier command failure`() {
        val commands = listOf("false", "false || true; true").shellAnd()
        assertEquals(1, ProcessBuilder("sh", "-c", commands).start().waitFor())
    }

    @Test
    fun `quoted input remains literal shell data`() {
        val value = "a' b; \$(echo unsafe)\nsecond line"
        val process = ProcessBuilder("sh", "-c", "printf '%s' ${shellQuote(value)}").start()
        assertEquals(value, process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
    }

    @Test
    fun `restore batch keeps wait options outside the rule payload`() {
        val batch = FirewallRestoreBatch(
            FirewallCommands.IPV6,
            "filter",
            listOf("${FirewallCommands.IPV6} -t filter -N EXAMPLE", "${FirewallCommands.IPV6} -t filter -A EXAMPLE -j DROP"),
        )
        assertEquals("*filter\n-N EXAMPLE\n-A EXAMPLE -j DROP\nCOMMIT", batch.payload())
        assertTrue(batch.command().contains("ip6tables-restore --noflush -w 2"))
        assertFalse(batch.payload().contains("-w"))
    }

    @Test
    fun `restore batch rejects shell control flow or a different table`() {
        for (command in listOf("${FirewallCommands.IPV4} -t filter -N TEST; false", "${FirewallCommands.IPV4} -t mangle -N TEST")) {
            assertThrows(IllegalArgumentException::class.java) {
                FirewallRestoreBatch(FirewallCommands.IPV4, "filter", listOf(command)).payload()
            }
        }
    }

    @Test
    fun `owned chain inspection detects orphan INPUT chains without matching neighboring names`() {
        val command = FirewallCommands.absentChains(listOf("MXTI"), listOf("filter"), listOf(FirewallCommands.IPV4))
        for ((snapshot, expected) in listOf("-N MXTI" to 1, "-N MXTI_OTHER" to 0)) {
            val stub = "iptables() { printf '%s\\n' '$snapshot'; }; "
            assertEquals(expected, ProcessBuilder("sh", "-c", stub + command).start().waitFor())
        }
        assertEquals(1, ProcessBuilder("sh", "-c", "iptables() { return 1; }; $command").start().waitFor())
    }
}
