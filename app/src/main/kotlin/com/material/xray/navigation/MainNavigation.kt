package com.material.xray.navigation

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.NavDisplay
import com.material.xray.core.navigation.HomeKey
import com.material.xray.core.navigation.LogsKey
import com.material.xray.core.navigation.NavigationState
import com.material.xray.core.navigation.Navigator
import com.material.xray.core.navigation.RoutingKey
import com.material.xray.core.navigation.TopLevelKey
import com.material.xray.core.navigation.TopLevelKeys
import com.material.xray.core.navigation.appPredictivePopTransitionSpec
import com.material.xray.core.navigation.appTransitionSpec
import com.material.xray.core.navigation.rememberDetailSheetSceneStrategy
import com.material.xray.core.navigation.toEntries
import com.material.xray.core.ui.R
import com.material.xray.core.ui.adaptive.TwoPaneMinWidth
import com.material.xray.core.ui.components.TopBarTint
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app's navigation: one `NavDisplay` filling the window. Tabs carry the bar or rail with them
 * ([TabChrome]); details are pushed on the current tab's stack and either fill the window or, on a
 * wide one, open as an end-edge sheet over the tab.
 */
@Composable
fun MainNavigation(
    navigationState: NavigationState,
    navigator: Navigator,
    pendingSubscriptionLink: String?,
    onSubscriptionLinkHandled: () -> Unit,
    addSubscriptionFocusRequester: FocusRequester,
) {
    val viewModel: MainNavigationViewModel = koinViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadedSettings = settings ?: return
    val showAdvancedOptions = loadedSettings.showAdvancedOptions
    val visibleTabs = remember(showAdvancedOptions) {
        if (showAdvancedOptions) TopLevelKeys else TopLevelKeys - LogsKey
    }
    val currentTab = navigator.currentTopLevelKey
    var previousTab by remember { mutableStateOf(currentTab) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(currentTab) {
        if (previousTab == RoutingKey && currentTab != RoutingKey) {
            viewModel.onLeavingRoutingTab()
        }
        previousTab = currentTab
    }

    LaunchedEffect(showAdvancedOptions, currentTab) {
        if (!showAdvancedOptions && currentTab == LogsKey) {
            navigator.selectTab(HomeKey)
        }
    }

    val settingsState = rememberUpdatedState(loadedSettings)
    val subscriptionLinkState = rememberUpdatedState(pendingSubscriptionLink)
    val onSubscriptionLinkHandledState = rememberUpdatedState(onSubscriptionLinkHandled)
    val topBarTint = remember { TopBarTint() }
    val layoutDirectionSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val interruptedDirection = navigator.latestTabDirection(visibleTabs)
    val detailSheetStrategy = rememberDetailSheetSceneStrategy(
        minWindowWidth = TwoPaneMinWidth,
        scrimClickLabel = stringResource(R.string.navigation_close_sheet),
    )

    // Painted behind the scenes so that, while two of them cross-fade, the gap shows the same colour
    // as the tabs' own Scaffolds rather than the window background.
    SharedTransitionLayout(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val chrome = TabChromeState(
            visibleTabs = visibleTabs,
            selectedTab = { navigator.currentTopLevelKey },
            onSelectTab = { tab: TopLevelKey -> navigator.selectTab(tab) },
            sharedTransitionScope = this,
            topBarTint = topBarTint,
        )
        val chromeState = rememberUpdatedState(chrome)
        val entryProvider = remember(navigator) {
            appEntryProvider(
                navigator = navigator,
                chrome = chromeState,
                settings = settingsState,
                pendingSubscriptionLink = subscriptionLinkState,
                onSubscriptionLinkHandled = onSubscriptionLinkHandledState,
                addSubscriptionFocusRequester = addSubscriptionFocusRequester,
            )
        }
        NavDisplay(
            entries = navigationState.toEntries(entryProvider),
            onBack = navigator::goBack,
            sceneStrategies = listOf(detailSheetStrategy),
            transitionSpec = appTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
            popTransitionSpec = appTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
            predictivePopTransitionSpec = appPredictivePopTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
        )
    }
}
