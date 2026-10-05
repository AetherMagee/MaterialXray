package com.material.xray.navigation

import androidx.compose.runtime.Stable
import com.material.xray.core.navigation.TopLevelKey
import com.material.xray.core.ui.components.TopBarTint

/** The persistent navigation bar or rail and the tab viewport it surrounds. */
@Stable
internal class TabChromeState(
    /** The tabs in the bar or rail, in order; Logs is left out while it is hidden. */
    val visibleTabs: List<TopLevelKey>,
    val selectedTab: () -> TopLevelKey,
    val onSelectTab: (TopLevelKey) -> Unit,
    val topBarTint: TopBarTint,
)
