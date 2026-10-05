package com.material.xray.core.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey

/** Changes [NavigationState] in response to navigation events. */
class Navigator(private val state: NavigationState) {
    /** The tab that was selected before the current one; feeds the interrupted-slide rule. */
    private var previousTopLevelKey by mutableStateOf(state.topLevelKey)

    private var homeTapOrigin by mutableStateOf<TopLevelKey?>(null)

    /** Home taps are forward tab changes; only Back removes the previous tab visually. */
    val displayStacks: List<TopLevelKey>
        get() = homeTapOrigin?.takeIf { state.topLevelKey == state.startKey }
            ?.let { listOf(it, state.startKey) } ?: state.stacksInUse

    val currentTopLevelKey: TopLevelKey
        get() = state.topLevelKey

    /** The detail open on the current tab, if any. */
    val currentDetailKey: DetailKey?
        get() = state.currentStack.last() as? DetailKey

    fun navigate(key: AppNavKey) {
        when (key) {
            is TopLevelKey -> selectTab(key)
            is DetailKey -> openDetail(key)
        }
    }

    /** Switches to [key]'s stack as it was left. Selecting the current tab does nothing. */
    fun selectTab(key: TopLevelKey) {
        require(key in state.backStacks) { "No back stack for $key" }
        if (key == state.topLevelKey) return
        switchTab(key, fromTap = true)
    }

    private fun switchTab(key: TopLevelKey, fromTap: Boolean) {
        previousTopLevelKey = state.topLevelKey
        homeTapOrigin = previousTopLevelKey.takeIf { fromTap && key == state.startKey }
        state.topLevelKey = key
    }

    /**
     * Opens [key] on the current tab. Only one detail is open at a time, so one already on top is
     * replaced rather than stacked under the new one.
     */
    fun openDetail(key: DetailKey) {
        val stack = state.currentStack
        if (stack.last() is DetailKey) {
            stack[stack.lastIndex] = key
        } else {
            stack.add(key)
        }
    }

    fun closeDetail() {
        val stack = state.currentStack
        if (stack.last() is DetailKey) stack.removeAt(stack.lastIndex)
    }

    /** Pops the current tab's stack, or returns to the start tab from another tab's root. */
    fun goBack() {
        val stack = state.currentStack
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            state.topLevelKey != state.startKey -> switchTab(state.startKey, fromTap = false)
        }
    }

    /**
     * The slide direction of the latest tab change in [tabOrder], before layout direction. Pass it as
     * `interruptedDirection` to [appTransitionSpec]: when a tab change is reversed mid-slide, the
     * transition runs between two entries of the same tab and this is the only direction left.
     */
    fun latestTabDirection(tabOrder: List<NavKey>): Int = tabTransitionDirection(tabOrder, previousTopLevelKey, state.topLevelKey)
}
