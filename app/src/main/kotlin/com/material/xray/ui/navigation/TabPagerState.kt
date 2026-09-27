package com.material.xray.ui.navigation

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.material.xray.ui.components.TabTransition
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * State for [TabPager]: the page requests waiting to be animated to, and the short-lived page
 * mapping a direct jump animates across.
 */
@Stable
internal class TabPagerState internal constructor(
    internal val pagerState: PagerState,
    internal val jumpPages: MutableState<List<Int>?>,
    internal val selections: Channel<Int>,
) {
    private val selectionPending = mutableStateOf(false)

    /** Whether a direct jump is animating. Drags stay locked while one runs. */
    val isJumping: Boolean get() = jumpPages.value != null

    /** Whether a queued selection has not started animating yet. */
    val isSelectionPending: Boolean get() = selectionPending.value

    /**
     * Queues a move to [page]. Selections run one after another and the queue keeps only the latest,
     * so a tap during a running jump is honoured without cutting that jump short mid-slide.
     */
    fun select(page: Int) {
        selectionPending.value = true
        selections.trySend(page)
    }

    /** Marks the queued selection as taken and animating. */
    internal fun onSelectionStarted() {
        selectionPending.value = false
    }

    /** The page shown at pager [slot]: the slot itself, or one of the two pages of a running jump. */
    fun pageForSlot(slot: Int): Int = jumpPages.value?.getOrNull(slot) ?: slot

    /** The real page the pager sits on, following the mapping of a running jump. */
    fun currentPage(): Int = jumpPages.value
        ?.let { pages -> pages[pagerState.currentPage.coerceIn(pages.indices)] }
        ?: pagerState.currentPage

    /**
     * Animates to [page]. A jump across the tabs in between briefly re-maps the pager onto just the
     * two pages taking part, so nothing slides past in between.
     */
    suspend fun animateSelection(page: Int) {
        val from = currentPage()
        if (page == from) return
        if (abs(page - from) == 1) {
            pagerState.animateScrollToPage(page, animationSpec = TabTransition.pageScrollSpec)
            return
        }
        val pair = listOf(min(from, page), maxOf(from, page))
        val fromSlot = if (from == pair.first()) 0 else 1
        jumpPages.value = pair
        try {
            // The mapping and the scroll position both change before the next frame.
            pagerState.scrollToPage(fromSlot)
            pagerState.animateScrollToPage(1 - fromSlot, animationSpec = TabTransition.pageScrollSpec)
        } finally {
            // However the slide ends, the mapping goes back and the pager lands on a real page, or
            // it would stay locked on the two jump pages. A cut slide lands on the closer of the two.
            withContext(NonCancellable) {
                jumpPages.value = null
                pagerState.scrollToPage(pair[pagerState.currentPage.coerceIn(pair.indices)])
            }
        }
    }

    /** Puts [page] under the viewport without animating, to line up with a restored selection. */
    suspend fun snapTo(page: Int) {
        jumpPages.value = null
        pagerState.scrollToPage(page)
    }
}

@Composable
internal fun rememberTabPagerState(pageCount: Int): TabPagerState {
    val pageCountState = rememberUpdatedState(pageCount)
    val jumpPages = remember { mutableStateOf<List<Int>?>(null) }
    val selections = remember { Channel<Int>(Channel.CONFLATED) }
    val pagerState = rememberPagerState(initialPage = 0) {
        jumpPages.value?.size ?: pageCountState.value
    }
    return remember(pagerState) { TabPagerState(pagerState, jumpPages, selections) }
}
