package com.material.xray.core.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEntryLayersTest {
    private val home = entry(HomeKey)
    private val settings = entry(SettingsKey)
    private val homeDetailKey = ConfigViewerKey(ConfigViewerTarget.Running)
    private val homeDetail = entry(homeDetailKey)
    private val settingsDetailKey = RoutingRuleEditorKey(payload = "{}")
    private val settingsDetail = entry(settingsDetailKey)

    @Test
    fun `a saved Home detail does not cover another tab`() {
        val layers = splitEntryLayers(listOf(home, homeDetail, settings), SettingsKey, currentDetailKey = null)
        assertEquals(listOf(home, settings), layers.tabs)
        assertEquals(listOf(SettingsKey), layers.details.map { it.navKey })
    }

    @Test
    fun `only the current detail appears over its transparent tab backdrop`() {
        val layers = splitEntryLayers(listOf(home, homeDetail, settings, settingsDetail), SettingsKey, settingsDetailKey)
        assertEquals(listOf(home, settings), layers.tabs)
        assertEquals(listOf(SettingsKey, settingsDetailKey), layers.details.map { it.navKey })
        assertTrue(layers.details.last() === settingsDetail)
    }

    @Test
    fun `returning to Home restores its saved detail in the overlay`() {
        val layers = splitEntryLayers(listOf(home, homeDetail), HomeKey, homeDetailKey)
        assertEquals(listOf(home), layers.tabs)
        assertEquals(listOf(HomeKey, homeDetailKey), layers.details.map { it.navKey })
        assertTrue(layers.details.last() === homeDetail)
    }

    @Test
    fun `identical details on two tabs use only the current tab entry`() {
        val currentDetail = entry(homeDetailKey)
        val layers = splitEntryLayers(listOf(home, homeDetail, settings, currentDetail), SettingsKey, homeDetailKey)
        assertEquals(2, layers.details.size)
        assertTrue(layers.details.last() === currentDetail)
    }

    @Test
    fun `closing a detail leaves a single backdrop so the tab handles back`() {
        val layers = splitEntryLayers(listOf(home, settings), SettingsKey, currentDetailKey = null)
        assertEquals(1, layers.details.size)
        assertEquals(listOf(HomeKey, SettingsKey), layers.tabs.map { it.navKey })
    }

    private fun entry(key: NavKey): NavEntry<NavKey> = NavEntry<NavKey>(key) {}.withNavKey(key)
}
