package com.material.xray.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene

/** Details share the tab transition's timing, easing and quarter-width motion. */
fun appDetailTransitionSpec(
    layoutDirectionSign: Int,
    isPop: Boolean = false,
): AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
    if (initialState is DetailSheetScene || targetState is DetailSheetScene) {
        // The sheet and its scrim animate independently, including during a predictive gesture.
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        tabContentTransform((if (isPop) -1 else 1) * layoutDirectionSign)
    }
}

/** Navigation 3 seeks the same pop transition during a predictive Back gesture. */
fun appDetailPredictivePopTransitionSpec(layoutDirectionSign: Int): AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform {
    val popTransition = appDetailTransitionSpec(layoutDirectionSign, isPop = true)
    return { popTransition.invoke(this) }
}
