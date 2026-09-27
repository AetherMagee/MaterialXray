package com.material.xray.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds

/**
 * The content behind a tab strip like [SegmentedTabRow]: selecting a tab slides directly between
 * the current tab and the selected one. These strips are tapped rather than swiped, so the swipe
 * gesture belongs to the main tab pager.
 */
@Composable
internal fun TabbedContent(
    selectedTab: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val saveableStateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = selectedTab,
        transitionSpec = {
            TabTransition
                .slideBetween(fromIndex = initialState, toIndex = targetState)
                .contentTransform using null
        },
        modifier = modifier.clipToBounds(),
        label = "tabbedContent",
    ) { tab ->
        saveableStateHolder.SaveableStateProvider(key = tab) {
            content(tab)
        }
    }
}
