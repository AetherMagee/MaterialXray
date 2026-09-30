package com.material.xray.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * Whether the window is wide enough to move top-level navigation from the bottom bar into a side
 * rail: tablets, unfolded foldables and phones held sideways, where width is plentiful and height
 * is the scarcer axis.
 */
@Composable
fun useNavigationRail(): Boolean = currentWindowAdaptiveInfoV2().windowSizeClass
    .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * Content width from which a screen splits into side-by-side panes. Measured against the space a
 * screen actually gets, after the rail, rather than the window, so split-screen and freeform
 * windows fall back to one pane on their own. It sits above a tablet in portrait (about 720dp
 * beside the rail), where two panes would leave each too narrow for its rows.
 */
val TwoPaneMinWidth = 760.dp

/** Widest a single column of cards and settings rows grows before it is centred instead. */
val SinglePaneMaxWidth = 680.dp
