package com.material.xray.data.parser

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionDeviceIdentityTest {
    @Test
    fun `valid Android ID is sent unchanged`() {
        val id = resolveSubscriptionHardwareId(
            androidId = " 0123456789abcdef ",
            readStored = { error("fallback should not be read") },
            store = { error("fallback should not be stored") },
        )

        assertEquals("0123456789abcdef", id)
    }

    @Test
    fun `missing Android ID creates and reuses a stored random token`() {
        var stored: String? = null
        var generations = 0
        val read = { stored }
        val write: (String) -> Boolean = {
            stored = it
            true
        }
        val generate = { "random-${++generations}" }

        val first = resolveSubscriptionHardwareId(null, read, write, generate)
        val second = resolveSubscriptionHardwareId("9774d56d682e549c", read, write, generate)

        assertEquals("random-1", first)
        assertEquals(first, second)
        assertEquals(1, generations)
    }

    @Test
    fun `failed persistence does not send a transient token`() {
        assertThrows(IOException::class.java) {
            resolveSubscriptionHardwareId(
                androidId = null,
                readStored = { null },
                store = { false },
                generate = { "random-token" },
            )
        }
    }
}
