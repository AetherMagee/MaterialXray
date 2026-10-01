package com.material.xray.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TabTransitionsTest {
    @Test
    fun `direction follows the current order including reordered tabs`() {
        val original = listOf("first", "second", "third")
        val reordered = listOf("third", "second", "first")
        assertEquals(1, tabTransitionDirection(original, "first", "third"))
        assertEquals(-1, tabTransitionDirection(reordered, "first", "third"))
    }

    @Test
    fun `back reverses direction with hidden tabs omitted`() {
        val visible = listOf("first", "last")
        assertEquals(1, tabTransitionDirection(visible, "first", "last"))
        assertEquals(-1, tabTransitionDirection(visible, "last", "first"))
    }

    @Test
    fun `initial unchanged and removed destinations do not slide`() {
        val visible = listOf("first", "last")
        assertEquals(0, tabTransitionDirection(visible, null, "first"))
        assertEquals(0, tabTransitionDirection(visible, "first", "first"))
        assertEquals(0, tabTransitionDirection(visible, "removed", "first"))
    }

    @Test
    fun `restoring the outgoing tab keeps the latest tap direction during interruption`() {
        val routes = listOf("first", "middle", "last")
        assertEquals(-1, tabTransitionDirection(routes, "middle", "middle", interruptedDirection = -1))
        assertEquals(1, tabTransitionDirection(routes, "last", "last", interruptedDirection = 1))
        assertEquals(-1, tabTransitionDirection(routes, "last", "first", interruptedDirection = 1))
    }
}
