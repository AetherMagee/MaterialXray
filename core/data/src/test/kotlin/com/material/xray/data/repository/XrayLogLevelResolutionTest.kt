package com.material.xray.data.repository

import com.material.xray.model.XrayLogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayLogLevelResolutionTest {
    @Test
    fun `uses the stored level`() {
        assertEquals(XrayLogLevel.Debug, resolveXrayLogLevel(current = "debug", legacySaved = null))
    }

    @Test
    fun `falls back to the default when nothing is stored`() {
        assertEquals(XrayLogLevel.default, resolveXrayLogLevel(current = null, legacySaved = null))
    }

    @Test
    fun `restores the level parked while advanced options forced logging off`() {
        assertEquals(XrayLogLevel.Info, resolveXrayLogLevel(current = "none", legacySaved = "info"))
    }

    @Test
    fun `keeps an explicit none`() {
        assertEquals(XrayLogLevel.None, resolveXrayLogLevel(current = "none", legacySaved = null))
    }
}
