package com.material.xray.service

import com.material.xray.core.xray.XrayState
import com.material.xray.core.xray.XrayStateReadResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryResetManagerTest {
    @Test
    fun `recorded root runtime requires cleanup before data reset`() {
        assertEquals(
            RootCleanupDecision.Required,
            rootCleanupDecision(XrayStateReadResult.Present(XrayState(physicalInterface = "wlan0"))),
        )
    }

    @Test
    fun `rootless or absent runtime needs no root cleanup`() {
        assertEquals(
            RootCleanupDecision.None,
            rootCleanupDecision(XrayStateReadResult.Present(XrayState(physicalInterface = VPN_SERVICE_INTERFACE_LABEL))),
        )
        assertEquals(RootCleanupDecision.None, rootCleanupDecision(XrayStateReadResult.Absent))
    }

    @Test
    fun `unreadable runtime record blocks data reset`() {
        assertEquals(RootCleanupDecision.Unsafe, rootCleanupDecision(XrayStateReadResult.Unreadable))
    }
}
