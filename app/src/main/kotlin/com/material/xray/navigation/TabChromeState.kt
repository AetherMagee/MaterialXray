package com.material.xray.navigation

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Stable
import com.material.xray.core.navigation.TopLevelKey
import com.material.xray.core.ui.components.TopBarTint

/** What every tab's [TabChrome] shows and does. One instance is shared by all the tab entries. */
@Stable
internal class TabChromeState(
    /** The tabs in the bar or rail, in order; Logs is left out while it is hidden. */
    val visibleTabs: List<TopLevelKey>,
    val selectedTab: () -> TopLevelKey,
    val onSelectTab: (TopLevelKey) -> Unit,
    val sharedTransitionScope: SharedTransitionScope,
    val topBarTint: TopBarTint,
)
