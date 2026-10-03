package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDataUpdateIntervalTest {
    @Test
    fun defaultsInvalidValuesToTwentyFourHours() {
        assertEquals(GeoDataUpdateInterval.DEFAULT_HOURS, GeoDataUpdateInterval.normalize(null))
        assertEquals(GeoDataUpdateInterval.DEFAULT_HOURS, GeoDataUpdateInterval.normalize(-1))
        assertEquals(GeoDataUpdateInterval.DEFAULT_HOURS, GeoDataUpdateInterval.normalize(721))
    }

    @Test
    fun acceptsSupportedRange() {
        assertTrue(GeoDataUpdateInterval.isValid(GeoDataUpdateInterval.MIN_HOURS))
        assertTrue(GeoDataUpdateInterval.isValid(GeoDataUpdateInterval.MAX_HOURS))
        assertTrue(GeoDataUpdateInterval.isValid(0))
        assertFalse(GeoDataUpdateInterval.isValid(-1))
        assertEquals(0, GeoDataUpdateInterval.normalize(0))
        assertEquals(48, GeoDataUpdateInterval.normalize(48))
    }
}
