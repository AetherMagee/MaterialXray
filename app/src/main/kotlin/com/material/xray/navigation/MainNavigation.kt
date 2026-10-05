package com.material.xray.navigation

import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
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
import com.material.xray.feature.home.HomeViewModel
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
    val homeViewModel: HomeViewModel = koinViewModel(viewModelStoreOwner = requireNotNull(LocalActivity.current as? ViewModelStoreOwner))
    val showAdvancedOptions = loadedSettings.showAdvancedOptions
    val visibleTabs = remember(showAdvancedOptions) {
        if (showAdvancedOptions) TopLevelKeys else TopLevelKeys - LogsKey
    }
    val currentTab = navigator.currentTopLevelKey
    var previousTab by remember { mutableStateOf(currentTab) }

    // This owner belongs to the Activity, outside NavDisplay's per-scene lifecycle. Switching
    // tabs must not repeat runtime reconciliation or resume an APK installation.
    DisposableEffect(lifecycleOwner, homeViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    homeViewModel.refreshTunnelInterfaceState()
                    homeViewModel.resumePendingAppUpdateInstall()
                }
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                else -> Unit
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

    val entryProvider = remember(navigationState, navigator, homeViewModel) {
        val provider = appEntryProvider(
            navigator = navigator,
            homeViewModel = homeViewModel,
            settings = settingsState,
            pendingSubscriptionLink = subscriptionLinkState,
            onSubscriptionLinkHandled = onSubscriptionLinkHandledState,
            addSubscriptionFocusRequester = addSubscriptionFocusRequester,
        )
        return@remember { key: NavKey -> provider(key).withTabLifecycle(key, navigator, navigationState.startKey) }
    }
    val layers = navigationState.toEntryLayers(entryProvider, navigator.displayStacks)
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
                detailWasOpen -> {
                    withFrameNanos {}
                    backgroundFocus.requestFocus()
                }
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

    // Keep the tab host's identity stable when selecting tabs. Full-screen details animate this
    // actual page out and back, rather than moving over an unrelated stationary background.
    val tabContent = rememberUpdatedState<@Composable () -> Unit> {
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
                val tabScene = rememberSceneState(entries = layers.tabs, sceneStrategies = listOf(SinglePaneSceneStrategy()), onBack = navigator::goBack)
                val tabBack = rememberNavigationEventState(tabScene)
                // Only the visible page may handle Back. The outer page owns detail gestures,
                // while a screen's own editor handler still takes precedence over navigation.
                // Home exits the app even when another tab is retained under its tap animation.
                if (!hasDetail && currentTab != navigationState.startKey) NavigationBackHandler(tabScene, tabBack, onBackCompleted = navigator::goBack)
                NavDisplay(
                    sceneState = tabScene,
                    navigationEventState = tabBack,
                    transitionSpec = appTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
                    popTransitionSpec = appTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
                    predictivePopTransitionSpec = appPredictivePopTransitionSpec(visibleTabs, layoutDirectionSign, interruptedDirection),
                )
            }
        }
    }
    val tabHost = NavEntry<NavKey>(currentTab, contentKey = "materialxray.tabHost") { tabContent.value() }
    val detailEntries = layers.details.drop(1).map { entry ->
        NavEntry<NavKey>(requireNotNull(navigator.currentDetailKey), contentKey = entry.contentKey, metadata = entry.metadata) {
            Box(modifier = Modifier.fillMaxSize().focusRequester(detailFocus).focusGroup()) { entry.Content() }
        }
    }
    val pageEntries = listOf(tabHost) + detailEntries
    val pageScene = rememberSceneState(
        entries = pageEntries,
        sceneStrategies = listOf(detailSheetStrategy),
        onBack = navigator::closeDetail,
    )
    val pageBack = rememberNavigationEventState(pageScene)
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationBackHandler(pageScene, pageBack, onBackCompleted = navigator::closeDetail)
        NavDisplay(
            sceneState = pageScene,
            navigationEventState = pageBack,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = appDetailTransitionSpec(layoutDirectionSign),
            popTransitionSpec = appDetailTransitionSpec(layoutDirectionSign, isPop = true),
            predictivePopTransitionSpec = appDetailPredictivePopTransitionSpec(layoutDirectionSign),
        )
    }
}

/** Keep a tab retained beneath a Home tap from handling Back or collecting screen data. */
private fun NavEntry<NavKey>.withTabLifecycle(key: NavKey, navigator: Navigator, startKey: TopLevelKey): NavEntry<NavKey> {
    if (key !is TopLevelKey) return this
    val entry = this
    return NavEntry<NavKey>(key, contentKey = contentKey, metadata = metadata) {
        val retainedOrigin = navigator.currentTopLevelKey == startKey && key != startKey
        val tabLifecycle = rememberLifecycleOwner(
            maxLifecycle = if (retainedOrigin) Lifecycle.State.CREATED else Lifecycle.State.RESUMED,
        )
        CompositionLocalProvider(LocalLifecycleOwner provides tabLifecycle) { entry.Content() }
    }
}
