package com.material.xray.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.ui.NavDisplay
import com.material.xray.core.navigation.HomeKey
import com.material.xray.core.navigation.LogsKey
import com.material.xray.core.navigation.NavigationState
import com.material.xray.core.navigation.Navigator
import com.material.xray.core.navigation.RoutingKey
import com.material.xray.core.navigation.TopLevelKey
import com.material.xray.core.navigation.TopLevelKeys
import com.material.xray.core.navigation.appDetailPredictivePopTransitionSpec
import com.material.xray.core.navigation.appDetailTransitionSpec
import com.material.xray.core.navigation.appPredictivePopTransitionSpec
import com.material.xray.core.navigation.appTransitionSpec
import com.material.xray.core.navigation.rememberDetailSheetSceneStrategy
import com.material.xray.core.navigation.toEntryLayers
import com.material.xray.core.ui.R
import com.material.xray.core.ui.adaptive.TwoPaneMinWidth
import com.material.xray.core.ui.components.TopBarTint
import org.koin.compose.viewmodel.koinViewModel

/** Persistent tab chrome with one display for tabs and a full-window overlay for details. */
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
    val pageShiftPx = with(LocalDensity.current) { 96.dp.roundToPx() }
    val detailSheetStrategy = rememberDetailSheetSceneStrategy(
        minWindowWidth = TwoPaneMinWidth,
        scrimClickLabel = stringResource(R.string.navigation_close_sheet),
    )

    val entryProvider = remember(navigator) {
        appEntryProvider(
            navigator = navigator,
            settings = settingsState,
            pendingSubscriptionLink = subscriptionLinkState,
            onSubscriptionLinkHandled = onSubscriptionLinkHandledState,
            addSubscriptionFocusRequester = addSubscriptionFocusRequester,
        )
    }
    val layers = navigationState.toEntryLayers(entryProvider)
    val hasDetail = layers.details.size > 1
    val backgroundLifecycle = rememberLifecycleOwner(
        maxLifecycle = if (hasDetail) Lifecycle.State.STARTED else Lifecycle.State.RESUMED,
    )
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val backgroundFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    var backgroundHasFocus by remember { mutableStateOf(false) }
    var detailWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(hasDetail) {
        if (hasDetail && backgroundHasFocus) focusManager.clearFocus()
        if (inputModeManager.inputMode == InputMode.Keyboard) {
            when {
                hasDetail -> {
                    withFrameNanos {}
                    detailFocus.requestFocus()
                }
                detailWasOpen -> backgroundFocus.requestFocus()
            }
        }
        detailWasOpen = hasDetail
    }
    val backgroundModifier = Modifier
        .then(if (hasDetail) Modifier.clearAndSetSemantics {} else Modifier)
        .focusRequester(backgroundFocus)
        .onFocusChanged { backgroundHasFocus = it.hasFocus }
        .focusProperties { onEnter = { if (hasDetail) cancelFocusChange() } }
        .focusRestorer()
        .focusGroup()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalLifecycleOwner provides backgroundLifecycle) {
            TabChrome(
                state = TabChromeState(
                    visibleTabs = visibleTabs,
                    selectedTab = { navigator.currentTopLevelKey },
                    onSelectTab = { tab: TopLevelKey -> navigator.selectTab(tab) },
                    topBarTint = topBarTint,
                ),
                modifier = backgroundModifier,
            ) {
                NavDisplay(
                    entries = layers.tabs,
                    onBack = navigator::goBack,
                    transitionSpec = appTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
                    popTransitionSpec = appTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
                    predictivePopTransitionSpec = appPredictivePopTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().focusRequester(detailFocus).focusGroup()) {
            NavDisplay(
                entries = layers.details,
                onBack = navigator::closeDetail,
                sceneStrategies = listOf(detailSheetStrategy),
                transitionSpec = appDetailTransitionSpec(pageShiftPx, layoutDirectionSign),
                popTransitionSpec = appDetailTransitionSpec(pageShiftPx, layoutDirectionSign, isPop = true),
                predictivePopTransitionSpec = appDetailPredictivePopTransitionSpec(),
            )
        }
    }
}
