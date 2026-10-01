package com.material.xray.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.material.xray.R

/** Height of the app's top bars, which a navigation rail matches to continue their band. */
val AppTopBarHeight = 52.dp

/**
 * The current screen's top bar tint, for a navigation rail that sits beside the top bars and tints
 * a matching band at its own top so the bar appears to run the full width.
 *
 * It hands over the bar's live scroll state rather than a copied value, so a reader in the draw
 * phase sees the same fraction as the bar in the same frame and the two never drift apart.
 */
@Stable
class TopBarTint {
    internal var source by mutableStateOf<(() -> Float)?>(null)

    /** How far the current top bar has shifted to its scrolled colour, from 0 to 1. */
    val fraction: Float get() = source?.invoke() ?: 0f
}

/** `null` where nothing sits beside the top bars to follow their tint. */
val LocalTopBarTint = compositionLocalOf<TopBarTint?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrolledTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    showLogo: Boolean,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val surface = MaterialTheme.colorScheme.surface
    val scrolledSurface = MaterialTheme.colorScheme.surfaceContainer
    val overlappedFraction by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.overlappedFraction.coerceIn(0f, 1f) }
    }
    // A pinned bar's overlap jumps straight between 0 and 1, so the tint is eased here once, the
    // way TopAppBar would ease it, and both the bar and a rail band read this one animated value.
    val animatedFraction = animateFloatAsState(
        targetValue = overlappedFraction,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "topBarTint",
    )
    val fraction = remember(animatedFraction) { { animatedFraction.value } }
    val containerColor = lerp(surface, scrolledSurface, overlappedFraction)
    val tint = LocalTopBarTint.current
    if (tint != null) {
        DisposableEffect(tint, fraction) {
            tint.source = fraction
            onDispose {
                if (tint.source === fraction) tint.source = null
            }
        }
    }
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }

    if (window != null && !view.isInEditMode) {
        val useDarkIcons = containerColor.luminance() > 0.5f
        SideEffect {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = useDarkIcons
        }
        DisposableEffect(window, view) {
            val previousLightStatusBars = WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars
            onDispose {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = previousLightStatusBars
            }
        }
    }

    TopAppBar(
        title = { AppBarTitle(title, showLogo) },
        // The bar paints its own background in the draw phase instead of letting TopAppBar animate
        // the colour privately, so a rail band reading the same fraction matches it frame for frame.
        modifier = Modifier.drawBehind { drawRect(lerp(surface, scrolledSurface, fraction())) },
        navigationIcon = navigationIcon,
        actions = actions,
        expandedHeight = AppTopBarHeight,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        scrollBehavior = scrollBehavior,
        windowInsets = TopAppBarDefaults.windowInsets,
    )
}

@Composable
fun AppBarTitle(title: String, showLogo: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = showLogo,
            enter = fadeIn(tween(180)) + expandHorizontally(tween(180), expandFrom = Alignment.Start) +
                slideInHorizontally(tween(180)) { -it },
            exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start) +
                slideOutHorizontally(tween(180)) { -it },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_default_monochrome),
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 8.dp).size(24.dp),
            )
        }
        Text(title)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
