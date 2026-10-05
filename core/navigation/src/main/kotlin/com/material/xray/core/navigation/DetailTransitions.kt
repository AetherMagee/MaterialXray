package com.material.xray.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec

/** Compose equivalent of Android's activity slide and short opacity ramps, with a 96dp offset. */
fun appDetailTransitionSpec(
    offsetPx: Int,
    layoutDirectionSign: Int,
    isPop: Boolean = false,
): AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
    if (initialState is DetailSheetScene || targetState is DetailSheetScene) {
        // The sheet and its scrim animate independently, including during a predictive gesture.
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        val enters = targetState.entries.lastOrNull()?.navKey is DetailKey
        val exits = initialState.entries.lastOrNull()?.navKey is DetailKey
        val enter = if (enters && !isPop) {
            fadeIn(tween(83, delayMillis = 50, easing = LinearEasing)) +
                slideInHorizontally(tween(450, easing = PlatformPageEasing)) { offsetPx * layoutDirectionSign }
        } else {
            EnterTransition.None
        }
        val exit = if (exits && isPop) {
            fadeOut(tween(83, delayMillis = 35, easing = LinearEasing)) +
                slideOutHorizontally(tween(450, easing = PlatformPageEasing)) { offsetPx * layoutDirectionSign }
        } else {
            ExitTransition.KeepUntilTransitionsFinished
        }
        enter togetherWith exit
    }
}

/** Navigation 3 drives progress, cancellation and completion of Android-style predictive Back. */
fun appDetailPredictivePopTransitionSpec(): AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform {
    val platformPredictive = defaultPredictivePopTransitionSpec<NavKey>()
    return { edge ->
        if (initialState is DetailSheetScene || targetState is DetailSheetScene) {
            EnterTransition.None togetherWith ExitTransition.None
        } else {
            platformPredictive.invoke(this, edge)
        }
    }
}
