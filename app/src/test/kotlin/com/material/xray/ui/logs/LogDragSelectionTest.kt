package com.material.xray.ui.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class LogDragSelectionTest {
    private val ids = listOf(10L, 20L, 30L, 40L, 50L)

    @Test
    fun `reversing a drag removes rows below the shortened range`() {
        val gesture = LogDragSelection(20L, emptySet())
        assertEquals(setOf(20L, 30L, 40L, 50L), gesture.selectionAt(ids, 50L))
        assertEquals(setOf(20L, 30L), gesture.selectionAt(ids, 30L))
        assertEquals(setOf(10L, 20L), gesture.selectionAt(ids, 10L))
    }

    @Test
    fun `shortening a drag restores the selection that preceded it`() {
        val gesture = LogDragSelection(20L, setOf(40L, 50L))
        assertEquals(setOf(20L, 30L, 40L, 50L), gesture.selectionAt(ids, 50L))
        assertEquals(setOf(20L, 40L, 50L), gesture.selectionAt(ids, 20L))
    }

    @Test
    fun `dragging from a selected row deselects and reversing restores rows`() {
        val gesture = LogDragSelection(20L, ids.toSet())
        assertEquals(setOf(10L, 50L), gesture.selectionAt(ids, 40L))
        assertEquals(setOf(10L, 30L, 40L, 50L), gesture.selectionAt(ids, 20L))
    }

    @Test
    fun `range follows visible rows rather than consecutive log ids`() {
        val gesture = LogDragSelection(10L, setOf(20L))
        assertEquals(setOf(10L, 20L, 30L, 50L), gesture.selectionAt(listOf(10L, 30L, 50L), 50L))
    }

    @Test
    fun `evicted anchor does not select an unrelated range`() {
        val gesture = LogDragSelection(10L, setOf(10L, 50L))
        assertEquals(setOf(50L), gesture.selectionAt(listOf(30L, 40L, 50L), 40L))
    }
}
