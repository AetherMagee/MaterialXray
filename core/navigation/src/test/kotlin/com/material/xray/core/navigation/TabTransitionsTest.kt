package com.material.xray.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TabTransitionsTest {
    @Test
    fun `direction follows the current order including reordered tabs`() {
        val original = listOf(HomeKey, RoutingKey, SettingsKey)
        val reordered = listOf(SettingsKey, RoutingKey, HomeKey)
        assertEquals(1, tabTransitionDirection(original, HomeKey, SettingsKey))
        assertEquals(-1, tabTransitionDirection(reordered, HomeKey, SettingsKey))
    }

    @Test
    fun `back reverses direction with hidden tabs omitted`() {
        val visible = listOf(HomeKey, SettingsKey)
        assertEquals(1, tabTransitionDirection(visible, HomeKey, SettingsKey))
        assertEquals(-1, tabTransitionDirection(visible, SettingsKey, HomeKey))
    }

    @Test
    fun `initial unchanged and removed destinations do not slide`() {
        val visible = listOf(HomeKey, SettingsKey)
        assertEquals(0, tabTransitionDirection(visible, null, HomeKey))
        assertEquals(0, tabTransitionDirection(visible, HomeKey, HomeKey))
        assertEquals(0, tabTransitionDirection(visible, LogsKey, HomeKey))
    }

    @Test
    fun `restoring the outgoing tab keeps the latest tap direction during interruption`() {
        val tabs = listOf(HomeKey, RoutingKey, SettingsKey)
        assertEquals(-1, tabTransitionDirection(tabs, RoutingKey, RoutingKey, interruptedDirection = -1))
        assertEquals(1, tabTransitionDirection(tabs, SettingsKey, SettingsKey, interruptedDirection = 1))
        assertEquals(-1, tabTransitionDirection(tabs, SettingsKey, HomeKey, interruptedDirection = 1))
    }
}
