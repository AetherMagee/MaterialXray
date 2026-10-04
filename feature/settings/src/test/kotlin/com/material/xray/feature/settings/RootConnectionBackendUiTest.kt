package com.material.xray.feature.settings

import com.material.xray.core.model.RootConnectionBackend
import com.material.xray.core.xray.TproxyCompatibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootConnectionBackendUiTest {

    @Test
    fun `IPv6 stays optimistic until an IPv6 failure is confirmed`() {
        assertTrue(
            isIpv6SelectionEnabled(
                rootServiceActive = true,
                RootConnectionBackend.Tproxy,
                TproxyCompatibility.Unknown,
            ),
        )
        assertTrue(
            isIpv6SelectionEnabled(
                rootServiceActive = false,
                RootConnectionBackend.Tproxy,
                TproxyCompatibility.Supported(ipv6 = false),
            ),
        )
        assertFalse(
            isIpv6SelectionEnabled(
                rootServiceActive = true,
                RootConnectionBackend.Tproxy,
                TproxyCompatibility.Supported(ipv6 = false),
            ),
        )
    }
}
