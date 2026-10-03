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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Path
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene

/**
 * `NavDisplay`'s `transitionSpec` and `popTransitionSpec`: tabs slide in the direction of [tabOrder]
 * (the visible tabs, in bar order), details fade in over the tab and out again. Push and pop share
 * it, so back follows the visible tab order too.
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
    val from = initialState.entries.lastOrNull()?.navKey
    val to = targetState.entries.lastOrNull()?.navKey
    val fromTab = initialState.tab
    val toTab = targetState.tab
    // Changing tabs slides, whether or not either tab has a detail open.
    if (fromTab != toTab || (from !is DetailKey && to !is DetailKey)) {
        val direction = tabTransitionDirection(tabOrder, fromTab, toTab, interruptedDirection) * layoutDirectionSign
        return tabEnterTransition(direction) togetherWith tabExitTransition(direction)
    }
    if (from == to) return EnterTransition.None togetherWith ExitTransition.None
    // A sheet animates its own scrim and panel (DetailSheetScene) over a background that must
    // stay put, so its scene neither fades in nor out; it stays while those animations run. A
    // full-window detail fades over the tab, which is kept underneath until it has finished.
    val enter = if (to is DetailKey && targetState !is DetailSheetScene) detailEnterTransition(to) else EnterTransition.None
    val exit = when {
        initialState is DetailSheetScene -> ExitTransition.None
        from is DetailKey -> detailExitTransition(from)
        else -> ExitTransition.KeepUntilTransitionsFinished
    }
    return enter togetherWith exit
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
private val TabTransitionEasing by lazy {
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
        slideInHorizontally(tween(TAB_TRANSITION_MS, easing = TabTransitionEasing)) { direction * it / 4 }
}

private fun tabExitTransition(direction: Int): ExitTransition = if (direction == 0) {
    ExitTransition.None
} else {
    fadeOut(tween(durationMillis = 113, easing = LinearEasing)) +
        slideOutHorizontally(tween(TAB_TRANSITION_MS, easing = TabTransitionEasing)) { -direction * it / 4 }
}

private const val CONFIG_VIEWER_FADE_MS = 180
private const val ROUTING_EDITOR_ENTER_MS = 200
private const val ROUTING_EDITOR_EXIT_MS = 140

/** How [key] appears, whether full-window or as a sheet. */
internal fun detailEnterTransition(key: DetailKey): EnterTransition = when (key) {
    is ConfigViewerKey, is RoutingRuleViewerKey -> fadeIn(tween(CONFIG_VIEWER_FADE_MS))
    is RoutingRuleEditorKey ->
        fadeIn(tween(ROUTING_EDITOR_ENTER_MS)) +
            slideInVertically(tween(ROUTING_EDITOR_ENTER_MS)) { height -> height / 16 }
}

/** How [key] disappears, whether full-window or as a sheet. */
internal fun detailExitTransition(key: DetailKey): ExitTransition = when (key) {
    is ConfigViewerKey, is RoutingRuleViewerKey -> fadeOut(tween(CONFIG_VIEWER_FADE_MS))
    is RoutingRuleEditorKey -> fadeOut(tween(ROUTING_EDITOR_EXIT_MS))
}
