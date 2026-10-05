package com.material.xray.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Path
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene

/**
 * `NavDisplay`'s `transitionSpec` and `popTransitionSpec`: tabs slide in the direction of [tabOrder]
 * (the visible tabs, in bar order). Push and pop share it, so back follows the visible tab order.
 * Details use [appDetailTransitionSpec] in their separate overlay.
 *
 * @param layoutDirectionSign 1 for left-to-right, -1 for right-to-left.
 * @param interruptedDirection [Navigator.latestTabDirection].
 */
fun appTransitionSpec(
    tabOrder: List<TopLevelKey>,
    layoutDirectionSign: Int,
    interruptedDirection: Int,
): AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
    appContentTransform(tabOrder, layoutDirectionSign, interruptedDirection)
}

/** `NavDisplay`'s `predictivePopTransitionSpec`: the same as [appTransitionSpec], whichever edge is swiped. */
fun appPredictivePopTransitionSpec(
    tabOrder: List<TopLevelKey>,
    layoutDirectionSign: Int,
    interruptedDirection: Int,
): AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform = {
    appContentTransform(tabOrder, layoutDirectionSign, interruptedDirection)
}

private fun AnimatedContentTransitionScope<Scene<NavKey>>.appContentTransform(
    tabOrder: List<TopLevelKey>,
    layoutDirectionSign: Int,
    interruptedDirection: Int,
): ContentTransform {
    val direction = tabTransitionDirection(tabOrder, initialState.tab, targetState.tab, interruptedDirection) * layoutDirectionSign
    return tabEnterTransition(direction) togetherWith tabExitTransition(direction)
}

/** The tab a scene belongs to: the nearest tab root at or below its top entry. */
private val Scene<NavKey>.tab: NavKey?
    get() = (previousEntries + entries).lastOrNull { it.navKey is TopLevelKey }?.navKey

/**
 * The slide direction from [from] to [to]: 1 towards the end of [tabs], -1 towards the start, 0 for
 * no slide. A tab that is not in [tabs] (hidden, or not a tab) does not slide.
 */
internal fun tabTransitionDirection(tabs: List<NavKey>, from: NavKey?, to: NavKey?, interruptedDirection: Int = 0): Int {
    val fromIndex = tabs.indexOf(from)
    val toIndex = tabs.indexOf(to)
    return if (fromIndex < 0 || toIndex < 0) {
        0
    } else {
        // Restoring the tab we were animating away from creates a new entry with the same route.
        // Retain the direction of the latest tap instead of replacing that interrupted slide with None.
        toIndex.compareTo(fromIndex).takeIf { it != 0 } ?: interruptedDirection
    }
}

// Obtainium's FadeForwards transition: 450 ms, a quarter-width slide and emphasized easing.
private const val TAB_TRANSITION_MS = 450
internal val PlatformPageEasing by lazy {
    PathEasing(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        },
    )
}

private fun tabEnterTransition(direction: Int): EnterTransition = if (direction == 0) {
    EnterTransition.None
} else {
    fadeIn(tween(durationMillis = 338, easing = LinearEasing)) +
        slideInHorizontally(tween(TAB_TRANSITION_MS, easing = PlatformPageEasing)) { direction * it / 4 }
}

private fun tabExitTransition(direction: Int): ExitTransition = if (direction == 0) {
    ExitTransition.None
} else {
    fadeOut(tween(durationMillis = 113, easing = LinearEasing)) +
        slideOutHorizontally(tween(TAB_TRANSITION_MS, easing = PlatformPageEasing)) { -direction * it / 4 }
}
