package com.material.xray.core.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigatorTest {
    private val state = NavigationState(
        startKey = HomeKey,
        topLevelKey = mutableStateOf(HomeKey),
        backStacks = TopLevelKeys.associateWith { NavBackStack<NavKey>(it) },
    )
    private val navigator = Navigator(state)

    private val runningConfig = ConfigViewerKey(ConfigViewerTarget.Running)
    private val viewer = RoutingRuleViewerKey(payload = "viewer")
    private val editor = RoutingRuleEditorKey(payload = "editor")

    @Test
    fun `switching tabs keeps each tab's stack`() {
        navigator.selectTab(RoutingKey)
        navigator.openDetail(viewer)
        navigator.selectTab(SettingsKey)
        assertEquals(listOf(HomeKey, SettingsKey), state.keysInUse)

        navigator.selectTab(RoutingKey)
        assertEquals(RoutingKey, navigator.currentTopLevelKey)
        assertEquals(viewer, navigator.currentDetailKey)
        assertEquals(listOf(HomeKey, RoutingKey, viewer), state.keysInUse)
    }

    @Test
    fun `back from another tab's root goes to Home, and Home is where the app exits`() {
        navigator.selectTab(LogsKey)
        navigator.goBack()
        assertEquals(HomeKey, navigator.currentTopLevelKey)
        assertEquals(listOf(HomeKey), state.keysInUse)

        navigator.goBack()
        assertEquals(HomeKey, navigator.currentTopLevelKey)
        assertEquals(listOf(HomeKey), state.keysInUse)
    }

    @Test
    fun `back from a detail returns to the tab it was opened on`() {
        navigator.selectTab(RoutingKey)
        navigator.openDetail(editor)
        navigator.goBack()
        assertEquals(RoutingKey, navigator.currentTopLevelKey)
        assertNull(navigator.currentDetailKey)
        assertEquals(listOf(HomeKey, RoutingKey), state.keysInUse)
    }

    @Test
    fun `opening a detail replaces the one on top`() {
        navigator.selectTab(RoutingKey)
        navigator.openDetail(viewer)
        navigator.openDetail(editor)
        assertEquals(listOf(RoutingKey, editor), state.backStacks.getValue(RoutingKey).toList())

        navigator.navigate(viewer)
        assertEquals(listOf(RoutingKey, viewer), state.backStacks.getValue(RoutingKey).toList())

        navigator.closeDetail()
        assertEquals(listOf(RoutingKey), state.backStacks.getValue(RoutingKey).toList())
        navigator.closeDetail()
        assertEquals(listOf(RoutingKey), state.backStacks.getValue(RoutingKey).toList())
    }

    @Test
    fun `a detail opened on Home stays on Home's stack`() {
        navigator.openDetail(runningConfig)
        assertEquals(HomeKey, navigator.currentTopLevelKey)
        assertEquals(listOf(HomeKey, runningConfig), state.keysInUse)
    }

    @Test
    fun `selecting the current tab changes nothing`() {
        navigator.selectTab(RoutingKey)
        navigator.openDetail(viewer)
        navigator.selectTab(RoutingKey)
        assertEquals(listOf(HomeKey, RoutingKey, viewer), state.keysInUse)
        assertEquals(1, navigator.latestTabDirection(TopLevelKeys))
    }

    @Test
    fun `entries list the start stack first, then the current tab's`() {
        navigator.openDetail(runningConfig)
        navigator.navigate(SettingsKey)
        assertEquals(listOf(HomeKey, SettingsKey), state.stacksInUse)
        assertEquals(listOf(HomeKey, runningConfig, SettingsKey), state.keysInUse)
    }

    @Test
    fun `the latest tab direction follows the last tab change, back included`() {
        val visible = listOf(HomeKey, RoutingKey, SettingsKey)
        assertEquals(0, navigator.latestTabDirection(visible))
        navigator.selectTab(SettingsKey)
        assertEquals(1, navigator.latestTabDirection(visible))
        navigator.selectTab(RoutingKey)
        assertEquals(-1, navigator.latestTabDirection(visible))
        navigator.goBack()
        assertEquals(-1, navigator.latestTabDirection(visible))
    }
}
