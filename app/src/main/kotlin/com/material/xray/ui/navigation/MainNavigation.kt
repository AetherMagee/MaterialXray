package com.material.xray.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.ui.configviewer.ConfigViewerRequest
import com.material.xray.ui.configviewer.ConfigViewerScreen
import com.material.xray.ui.home.HomeScreen
import com.material.xray.ui.logs.LogsScreen
import com.material.xray.ui.routing.EditableRoutingRule
import com.material.xray.ui.routing.RoutingRuleEditorScreen
import com.material.xray.ui.routing.RoutingRuleViewerRequest
import com.material.xray.ui.routing.RoutingRuleViewerScreen
import com.material.xray.ui.routing.RoutingScreen
import com.material.xray.ui.routing.RoutingViewModel
import com.material.xray.ui.settings.SettingsScreen
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun MainNavigation(
    pendingSubscriptionLink: String?,
    onSubscriptionLinkHandled: () -> Unit,
    showDiagnosticsNotice: Boolean,
    onDiagnosticsNoticeDismiss: () -> Unit,
) {
    val viewModel: MainNavigationViewModel = hiltViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadedSettings = settings ?: return
    val showTitleBarLogo = loadedSettings.showTitleBarLogo
    val floatingConnectButton = loadedSettings.floatingConnectButton
    val showAdvancedOptions = loadedSettings.showAdvancedOptions
    val navigationScreens = remember(showAdvancedOptions) {
        if (showAdvancedOptions) {
            Screen.entries.toList()
        } else {
            Screen.entries.filterNot { it == Screen.Logs }
        }
    }
    // The tab pager lives here, above the screens, so the main tabs can be swiped or tapped as one
    // element while the persistent bottom navigation bar drives the same selection. Screens are
    // keyed by tab, so their state follows the tab across jumps and across the tab list changing.
    val tabPagerState = rememberTabPagerState(pageCount = navigationScreens.size)
    var selectedScreen by rememberSaveable { mutableStateOf(Screen.Home) }
    var previousScreen by remember { mutableStateOf(selectedScreen) }
    // The pill and the bottom navigation bar follow the pager's own position, so they move together
    // with a swipe or a slide; the effects above keep waiting for the settled selection instead.
    val visibleScreen by remember(navigationScreens) {
        derivedStateOf {
            navigationScreens[tabPagerState.currentPage().coerceIn(navigationScreens.indices)]
        }
    }
    val bottomInset = with(LocalDensity.current) {
        NavigationBarDefaults.windowInsets.getBottom(this).toDp()
    }

    fun selectScreen(screen: Screen) {
        selectedScreen = screen
        tabPagerState.select(navigationScreens.indexOf(screen))
    }

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

    LaunchedEffect(selectedScreen) {
        if (previousScreen == Screen.Routing && selectedScreen != Screen.Routing) {
            viewModel.onLeavingRoutingTab()
        }
        previousScreen = selectedScreen
    }

    LaunchedEffect(showAdvancedOptions, selectedScreen) {
        if (!showAdvancedOptions && selectedScreen == Screen.Logs) {
            selectScreen(Screen.Home)
        }
    }

    LaunchedEffect(pendingSubscriptionLink) {
        if (pendingSubscriptionLink != null && selectedScreen != Screen.Home) {
            selectScreen(Screen.Home)
        }
    }

    // A process death mid-jump leaves the pager parked on one of the two jump pages; line it up with
    // the restored selection once, before anything is shown moving.
    LaunchedEffect(Unit) {
        val restoredPage = navigationScreens.indexOf(selectedScreen)
        if (restoredPage >= 0 && tabPagerState.currentPage() != restoredPage) {
            tabPagerState.snapTo(restoredPage)
        }
    }

    BackHandler(enabled = selectedScreen != Screen.Home) {
        selectScreen(Screen.Home)
    }

    var configViewerRequest by rememberSaveable(stateSaver = ConfigViewerRequestSaver) {
        mutableStateOf<ConfigViewerRequest?>(null)
    }
    var routingRuleViewerRequest by rememberSaveable(stateSaver = RoutingRuleViewerRequestSaver) {
        mutableStateOf<RoutingRuleViewerRequest?>(null)
    }
    var routingRuleEditorRequest by rememberSaveable(stateSaver = RoutingRuleEditorRequestSaver) {
        mutableStateOf<EditableRoutingRule?>(null)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // The bar's band is consumed here, so nothing inside pads for the navigation bar twice.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(PaddingValues(bottom = CompactNavigationBarHeight + bottomInset)),
        ) {
            TabPager(
                pages = navigationScreens,
                state = tabPagerState,
                modifier = Modifier.fillMaxSize(),
                onPageSettled = { page -> selectedScreen = navigationScreens[page] },
            ) { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(
                        showTitleBarLogo = showTitleBarLogo,
                        floatingConnectButton = floatingConnectButton,
                        pendingSubscriptionLink = pendingSubscriptionLink,
                        onSubscriptionLinkHandled = onSubscriptionLinkHandled,
                        showDiagnosticsNotice = showDiagnosticsNotice,
                        onDiagnosticsNoticeDismiss = onDiagnosticsNoticeDismiss,
                        onOpenServerConfig = { serverId, name ->
                            configViewerRequest = ConfigViewerRequest.Server(serverId, name)
                        },
                        onViewRunningConfig = { configViewerRequest = ConfigViewerRequest.Running },
                    )
                    Screen.Logs -> LogsScreen(showTitleBarLogo = showTitleBarLogo)
                    Screen.Routing -> RoutingScreen(
                        showTitleBarLogo = showTitleBarLogo,
                        onViewRule = { request ->
                            routingRuleEditorRequest = null
                            routingRuleViewerRequest = request
                        },
                        onEditRule = { request ->
                            routingRuleViewerRequest = null
                            routingRuleEditorRequest = request
                        },
                    )
                    Screen.Settings -> SettingsScreen(showTitleBarLogo)
                }
            }

            BackHandler(enabled = configViewerRequest != null) { configViewerRequest = null }
            AnimatedContent(
                targetState = configViewerRequest,
                transitionSpec = {
                    fadeIn(tween(VIEWER_FADE_MS)) togetherWith fadeOut(tween(VIEWER_FADE_MS)) using null
                },
                label = "configViewer",
            ) { request ->
                if (request != null) {
                    ConfigViewerScreen(request = request, onBack = { configViewerRequest = null })
                }
            }

            AnimatedContent(
                targetState = routingRuleEditorRequest,
                transitionSpec = {
                    (
                        fadeIn(tween(ROUTING_EDITOR_ENTER_MS)) +
                            slideInVertically(tween(ROUTING_EDITOR_ENTER_MS)) { height -> height / 16 }
                        ) togetherWith
                        fadeOut(tween(ROUTING_EDITOR_EXIT_MS)) using null
                },
                label = "routingRuleEditor",
            ) { request ->
                if (request != null) {
                    RoutingRuleEditorScreen(
                        editableRule = request,
                        viewModel = hiltViewModel<RoutingViewModel>(),
                        onBack = { routingRuleEditorRequest = null },
                    )
                }
            }

            BackHandler(enabled = routingRuleViewerRequest != null) { routingRuleViewerRequest = null }
            AnimatedContent(
                targetState = routingRuleViewerRequest,
                transitionSpec = {
                    fadeIn(tween(VIEWER_FADE_MS)) togetherWith fadeOut(tween(VIEWER_FADE_MS)) using null
                },
                label = "routingRuleViewer",
            ) { request ->
                if (request != null) {
                    RoutingRuleViewerScreen(request = request, onBack = { routingRuleViewerRequest = null })
                }
            }
        }

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(CompactNavigationBarHeight + bottomInset),
        ) {
            navigationScreens.forEach { screen ->
                key(screen) {
                    val label = stringResource(screen.labelRes)
                    NavigationBarItem(
                        icon = {
                            val icon = screen.icon
                            if (icon != null) {
                                Icon(icon, contentDescription = label)
                            } else {
                                Icon(
                                    painter = painterResource(requireNotNull(screen.iconRes)),
                                    contentDescription = label,
                                )
                            }
                        },
                        label = { Text(label) },
                        selected = screen == visibleScreen,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        onClick = {
                            configViewerRequest = null
                            routingRuleViewerRequest = null
                            routingRuleEditorRequest = null
                            selectScreen(screen)
                        },
                    )
                }
            }
        }
    }
}

private val ConfigViewerRequestSaver: Saver<ConfigViewerRequest?, Any> = listSaver(
    save = { request ->
        when (request) {
            null -> emptyList()
            ConfigViewerRequest.Running -> listOf(RUNNING_CONFIG_TAG)
            is ConfigViewerRequest.Server -> listOf(SERVER_CONFIG_TAG, request.serverId, request.name)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            RUNNING_CONFIG_TAG -> ConfigViewerRequest.Running
            SERVER_CONFIG_TAG -> ConfigViewerRequest.Server(saved[1] as Long, saved[2] as String)
            else -> null
        }
    },
)

private val RoutingRuleViewerRequestSaver: Saver<RoutingRuleViewerRequest?, String> = Saver(
    save = { request -> request?.let(Json::encodeToString) },
    restore = { saved -> runCatching { Json.decodeFromString<RoutingRuleViewerRequest>(saved) }.getOrNull() },
)

private val RoutingRuleEditorRequestSaver: Saver<EditableRoutingRule?, String> = Saver(
    save = { request -> request?.let { Json.encodeToString(it) } },
    restore = { saved -> runCatching { Json.decodeFromString<EditableRoutingRule>(saved) }.getOrNull() },
)

private const val RUNNING_CONFIG_TAG = "running"
private const val SERVER_CONFIG_TAG = "server"
private const val VIEWER_FADE_MS = 180
private const val ROUTING_EDITOR_ENTER_MS = 200
private const val ROUTING_EDITOR_EXIT_MS = 140

private val CompactNavigationBarHeight = 68.dp
