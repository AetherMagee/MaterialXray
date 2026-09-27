package com.material.xray.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * The one motion timing of the app, so the parts of one gesture finish together.
 */
internal object AppMotion {
    /** Duration of every shared animation. */
    const val DURATION_MS: Int = 280

    /** Easing of every shared animation. */
    val Easing: Easing = FastOutSlowInEasing

    /** A typed tween for any value that should move with the rest. */
    fun <T> spec(): TweenSpec<T> = tween(durationMillis = DURATION_MS, easing = Easing)
}

/**
 * Which way a tab slide travels. The tabs of a strip have a fixed order, so every switch is either a
 * step towards the later tabs ([Forwards]) or back towards the earlier ones ([Backwards]).
 */
internal enum class TabSlideDirection {
    Forwards,
    Backwards,
}

/**
 * The typed animation of one tab switch: only the tab being left and the tab being selected take
 * part, however far apart they sit, so a jump reads as one direct move. Call sites name a
 * [TabSlideDirection] or a pair of tab positions instead of offsets, durations and easings.
 */
internal class TabTransition private constructor(
    val enter: EnterTransition,
    val exit: ExitTransition,
) {
    /** The two halves of the slide combined, ready to hand to `AnimatedContent`. */
    val contentTransform: ContentTransform = enter togetherWith exit

    companion object {
        private fun offsetSpec(): TweenSpec<IntOffset> = AppMotion.spec()

        /**
         * Scroll animation for a pager that slides a jump itself, crossing one page in the same
         * time [contentTransform] needs for the same slide.
         */
        val pageScrollSpec: FiniteAnimationSpec<Float> = AppMotion.spec()

        /** The slide between the current tab and the one on the given side of it. */
        fun slide(direction: TabSlideDirection): TabTransition = when (direction) {
            TabSlideDirection.Forwards -> TabTransition(
                enter = slideInHorizontally(offsetSpec()) { fullWidth -> fullWidth },
                exit = slideOutHorizontally(offsetSpec()) { fullWidth -> -fullWidth },
            )
            TabSlideDirection.Backwards -> TabTransition(
                enter = slideInHorizontally(offsetSpec()) { fullWidth -> -fullWidth },
                exit = slideOutHorizontally(offsetSpec()) { fullWidth -> fullWidth },
            )
        }

        /** The slide between the tabs at [fromIndex] and [toIndex] of the same strip. */
        fun slideBetween(fromIndex: Int, toIndex: Int): TabTransition = slide(
            direction = if (toIndex >= fromIndex) TabSlideDirection.Forwards else TabSlideDirection.Backwards,
        )
    }
}
