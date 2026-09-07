package com.material.xray.core.xray

import com.material.xray.core.root.RootShell
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAddressesTest {
    @Test
    fun `reads host addresses from all interfaces without bypassing their subnets`() {
        val addresses = LocalAddresses.parse(
            """
            1: lo inet 127.0.0.1/8 scope host lo
            2: rmnet1 inet 198.51.100.2/30 scope global rmnet1
            3: ap0 inet 192.168.43.1/24 scope global ap0
            4: rndis0 inet6 2001:db8::1/64 scope global dynamic
            4: rndis0 inet6 fe80::1/64 scope link
            5: bt-pan inet 192.168.44.1/24 scope global bt-pan
            """.trimIndent(),
        )

        assertTrue(addresses.contains("192.168.43.1/32"))
        assertTrue(addresses.contains("192.168.44.1/32"))
        assertTrue(addresses.contains("2001:db8:0:0:0:0:0:1/128"))
        assertFalse(addresses.any { it.endsWith("/24") || it.endsWith("/64") })
        assertTrue(LocalAddresses.forTool(addresses, "iptables").contains("127.0.0.0/8"))
        assertFalse(LocalAddresses.forTool(addresses, "iptables").any { ':' in it })
        assertFalse(LocalAddresses.forTool(addresses, "ip6tables -w").any { '.' in it })
    }

    @Test
    fun `reordering duplicate and equivalent IPv6 addresses does not trigger a refresh`() {
        val first = "1: lo inet 127.0.0.1/8\n2: ap0 inet6 2001:db8::1/64"
        val second = "2: ap0 inet6 2001:db8:0:0::1/64\n1: lo inet 127.0.0.1/8\n1: lo inet 127.0.0.1/8"
        assertEquals(LocalAddresses.parse(first), LocalAddresses.parse(second))
    }

    @Test
    fun `invalid and empty snapshots cannot silently remove local protection`() {
        for (output in listOf("", "2: ap0 inet hostname/24", "2: ap0 inet 999.1.1.1/24", "2: ap0 inet6 ::1;evil/64")) {
            assertThrows(IOException::class.java) { LocalAddresses.parse(output) }
        }
    }

    @Test
    fun `failed root inspection is not treated as an empty address set`() = runTest {
        var failed = false
        try {
            LocalAddresses.read { RootShell.Result(1, "", "permission denied") }
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
    }
}
