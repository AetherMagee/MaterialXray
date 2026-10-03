package com.material.xray.core.common.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicClockTest {
    @Test
    fun `milliseconds truncate nanoseconds`() {
        assertEquals(1_234L, MonotonicClock { 1_234_999_999L }.elapsedMillis())
    }

    @Test
    fun `system clock never goes backwards`() {
        val first = SystemMonotonicClock.elapsedNanos()
        val second = SystemMonotonicClock.elapsedNanos()

        assertTrue(second >= first)
    }
}
