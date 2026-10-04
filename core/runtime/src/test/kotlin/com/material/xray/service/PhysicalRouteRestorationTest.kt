package com.material.xray.service

import com.material.xray.core.connection.routing.TunManager
import com.material.xray.core.xray.XrayState
import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalRouteRestorationTest {
    @Test
    fun `restoration preserves persisted route for reconciliation`() {
        val fallback = TunManager.PhysicalRoute(dev = "rmnet0", gateway = "10.0.0.1", table = "main")
        val state = XrayState(
            physicalInterface = "wlan0",
            physicalGateway = "192.168.1.1",
            physicalTable = "main",
        )

        val restored = selectRestoredPhysicalRoute(state, fallback)

        assertEquals(TunManager.PhysicalRoute("wlan0", "192.168.1.1", "main"), restored)
    }

    @Test
    fun `restoration uses current route when persisted interface is missing`() {
        val fallback = TunManager.PhysicalRoute(dev = "rmnet0", gateway = "10.0.0.1", table = "main")

        val restored = selectRestoredPhysicalRoute(XrayState(physicalInterface = null), fallback)

        assertEquals(fallback, restored)
    }
}
