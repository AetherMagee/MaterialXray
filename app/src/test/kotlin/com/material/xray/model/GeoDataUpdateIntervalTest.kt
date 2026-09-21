package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDataUpdateIntervalTest {
    @Test
    fun defaultsInvalidValuesToTwentyFourHours() {
        assertEquals(GeoDataUpdateInterval.DEFAULT_HOURS, GeoDataUpdateInterval.normalize(null))
        assertEquals(GeoDataUpdateInterval.DEFAULT_HOURS, GeoDataUpdateInterval.normalize(0))
        assertEquals(GeoDataUpdateInterval.DEFAULT_HOURS, GeoDataUpdateInterval.normalize(721))
    }

    @Test
    fun acceptsSupportedRange() {
        assertTrue(GeoDataUpdateInterval.isValid(GeoDataUpdateInterval.MIN_HOURS))
        assertTrue(GeoDataUpdateInterval.isValid(GeoDataUpdateInterval.MAX_HOURS))
        assertFalse(GeoDataUpdateInterval.isValid(GeoDataUpdateInterval.MIN_HOURS - 1))
        assertEquals(48, GeoDataUpdateInterval.normalize(48))
    }
}
