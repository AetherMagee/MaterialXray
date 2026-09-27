package com.material.xray.core.network

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralXrayCoreTest {
    @Test
    fun `unsupported local socket connect is treated as not ready`() {
        assertFalse(socketConnects { throw UnsupportedOperationException() })
        assertFalse(socketConnects { throw IOException() })
        assertTrue(socketConnects {})
    }
}
