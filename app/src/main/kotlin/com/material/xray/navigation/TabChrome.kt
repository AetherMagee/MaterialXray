package com.material.xray.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.material.xray.core.navigation.HomeKey
import com.material.xray.core.navigation.LogsKey
import com.material.xray.core.navigation.RoutingKey
import com.material.xray.core.navigation.SettingsKey
import com.material.xray.core.navigation.TopLevelKey
import com.material.xray.core.ui.R
import com.material.xray.core.ui.adaptive.useNavigationRail
import com.material.xray.core.ui.components.AppTopBarHeight
import com.material.xray.core.ui.components.LocalTopBarTint

/** One persistent navigation bar or rail; only the clipped content viewport changes tabs. */
@Composable
internal fun TabChrome(state: TabChromeState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val useRail = useNavigationRail()
    val bottomInset = with(LocalDensity.current) { NavigationBarDefaults.windowInsets.getBottom(this).toDp() }
    Row(modifier = modifier.fillMaxSize()) {
        if (useRail) {
            AppNavigationRail(state = state, modifier = Modifier)
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            // Without a bottom bar to absorb them, the rail layout has to keep content clear of the
            // gesture area itself, and of a side navigation bar or cutout at the end.
            contentWindowInsets = if (useRail) {
                WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)
            } else {
                WindowInsets(0.dp)
            },
            bottomBar = {
                if (!useRail) {
                    AppNavigationBar(
                        state = state,
                        height = CompactNavigationBarHeight + bottomInset,
                        modifier = Modifier,
                    )
                }
            },
        ) { innerPadding ->
            // Consumed so the screens' own top bars do not add the rail layout's end inset again.
            Box(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).clipToBounds()) {
                CompositionLocalProvider(LocalTopBarTint provides state.topBarTint) {
                    content()
                }
            }
        }
    }
}

private val CompactNavigationBarHeight = 68.dp

@Composable
private fun AppNavigationBar(state: TabChromeState, height: Dp, modifier: Modifier) {
    AnimatedContent(
        targetState = state.visibleTabs,
        modifier = modifier,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
        label = "advancedNavigationItems",
    ) { tabs ->
        NavigationBar(modifier = Modifier.height(height)) {
            tabs.forEach { tab ->
                key(tab) {
                    NavigationBarItem(
                        icon = { TabIcon(tab) },
                        label = { AnimatedTabLabel(tab, selected = state.selectedTab() == tab) },
                        selected = state.selectedTab() == tab,
                        onClick = { state.onSelectTab(tab) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavigationRail(state: TabChromeState, modifier: Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val scrolledSurface = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier = modifier.background(surface)) {
        // The top bars start beside the rail. When one tints as content scrolls under it, this
        // band behind the rail's top edge takes the same colour, so the bar reads as running the
        // full width instead of leaving a notch in the status bar strip.
        // Sized from the rail rather than filling, so the band cannot widen the rail's slot.
        Column(modifier = Modifier.matchParentSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(lerp(surface, scrolledSurface, state.topBarTint.fraction)) },
            ) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(Modifier.height(AppTopBarHeight))
            }
        }
        NavigationRail(containerColor = Color.Transparent) {
            // Centred rather than top-aligned: on a tablet held in landscape the middle of the edge
            // is where a thumb rests, and the top corner is the hardest place to reach.
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = state.visibleTabs,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "advancedRailItems",
            ) { tabs ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tabs.forEach { tab ->
                        key(tab) {
                            NavigationRailItem(
                                icon = { TabIcon(tab) },
                                label = { AnimatedTabLabel(tab, selected = state.selectedTab() == tab) },
                                selected = state.selectedTab() == tab,
                                onClick = { state.onSelectTab(tab) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Blend glyphs rather than jump between the system font's discrete available weights. */
@Composable
private fun AnimatedTabLabel(tab: TopLevelKey, selected: Boolean) {
    val label = stringResource(tab.labelRes)
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200),
        label = "tabLabelEmphasis",
    )
    Box(
        modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(label) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Normal, modifier = Modifier.graphicsLayer { alpha = 1f - emphasis })
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.graphicsLayer { alpha = emphasis })
    }
}

private val TopLevelKey.labelRes: Int
    get() = when (this) {
        HomeKey -> R.string.navigation_home
        RoutingKey -> R.string.navigation_routing
        LogsKey -> R.string.navigation_logs
        SettingsKey -> R.string.navigation_settings
    }

@Composable
private fun TabIcon(tab: TopLevelKey) {
    val label = stringResource(tab.labelRes)
    when (tab) {
        HomeKey -> Icon(Icons.Default.Home, contentDescription = label)
        RoutingKey -> Icon(painter = painterResource(R.drawable.ic_arrow_split_24), contentDescription = label)
        LogsKey -> Icon(Icons.AutoMirrored.Filled.Article, contentDescription = label)
        SettingsKey -> Icon(Icons.Default.Settings, contentDescription = label)
    }
}
