package com.material.xray.core.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.SceneStrategyScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailSheetSceneStrategyTest {
    private val home = entry(HomeKey)
    private val routing = entry(RoutingKey)
    private val editor = entry(RoutingRuleEditorKey(payload = "{}"))

    @Test
    fun `a detail on a wide window opens as a sheet over the entry below it`() {
        val scene = calculate(windowWidth = 760.dp, listOf(home, routing, editor))
        assertTrue(scene is DetailSheetScene)
        scene as DetailSheetScene
        assertEquals(routing, scene.backgroundEntry)
        assertEquals(editor, scene.detailEntry)
        assertEquals(listOf(routing, editor), scene.entries)
        assertEquals(listOf(home, routing), scene.previousEntries)
    }

    @Test
    fun `a narrow window leaves the detail to the single-pane fallback`() {
        assertNull(calculate(windowWidth = 759.dp, listOf(home, routing, editor)))
    }

    @Test
    fun `a tab on top is not a sheet`() {
        assertNull(calculate(windowWidth = 1200.dp, listOf(home, routing)))
        assertNull(calculate(windowWidth = 1200.dp, listOf(editor)))
    }

    private fun calculate(windowWidth: Dp, entries: List<NavEntry<NavKey>>) = with(DetailSheetSceneStrategy(windowWidth, minWindowWidth = 760.dp, scrimClickLabel = null)) {
        SceneStrategyScope<NavKey>().calculateScene(entries)
    }

    private fun entry(key: NavKey): NavEntry<NavKey> = NavEntry<NavKey>(key) {}.withNavKey(key)
}
