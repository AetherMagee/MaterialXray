package com.material.xray.ui.navigation

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The swipable pager behind the main tabs. Dragging moves one tab at a time like any pager; tapping
 * a tab goes through [TabPagerState.animateSelection] and animates only between the current tab and
 * the selected one.
 */
@Composable
internal fun <T : Any> TabPager(
    pages: List<T>,
    state: TabPagerState,
    modifier: Modifier = Modifier,
    onPageSettled: (Int) -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(state) {
        for (page in state.selections) {
            state.onSelectionStarted()
            // Each slide runs in its own job, so one cut short by the user grabbing the page cannot
            // take the queue down with it and leave later taps with nothing to animate them.
            val slide = launch {
                try {
                    state.animateSelection(page)
                } catch (_: CancellationException) {
                    // Cut short; the queue simply moves on.
                }
            }
            slide.join()
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.pagerState.settledPage }.collect { settled ->
            // A jump snaps the mapping into place while it runs, and a queued selection is already on
            // its way; only a settle outside both is a real selection the rest of the UI follows.
            if (!state.isJumping && !state.isSelectionPending) {
                onPageSettled(state.pageForSlot(settled))
            }
        }
    }
    HorizontalPager(
        state = state.pagerState,
        modifier = modifier,
        userScrollEnabled = !state.isJumping,
        key = { slot -> pages[state.pageForSlot(slot)] },
    ) { slot ->
        val page = state.pageForSlot(slot)
        saveableStateHolder.SaveableStateProvider(key = pages[page]) {
            content(pages[page])
        }
    }
}
