package com.material.xray.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.Path

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

internal fun tabTransitionDirection(routes: List<String>, from: String?, to: String?, interruptedDirection: Int = 0): Int {
    val fromIndex = routes.indexOf(from)
    val toIndex = routes.indexOf(to)
    return if (fromIndex < 0 || toIndex < 0) {
        0
    } else {
        // Restoring the tab we were animating away from creates a new entry with the same route.
        // Retain the direction of the latest tap instead of replacing that interrupted slide with None.
        toIndex.compareTo(fromIndex).takeIf { it != 0 } ?: interruptedDirection
    }
}

internal fun tabEnterTransition(direction: Int): EnterTransition = if (direction == 0) {
    EnterTransition.None
} else {
    fadeIn(tween(durationMillis = 338, easing = LinearEasing)) +
        slideInHorizontally(tween(TAB_TRANSITION_MS, easing = TabTransitionEasing)) { direction * it / 4 }
}

internal fun tabExitTransition(direction: Int): ExitTransition = if (direction == 0) {
    ExitTransition.None
} else {
    fadeOut(tween(durationMillis = 113, easing = LinearEasing)) +
        slideOutHorizontally(tween(TAB_TRANSITION_MS, easing = TabTransitionEasing)) { -direction * it / 4 }
}
