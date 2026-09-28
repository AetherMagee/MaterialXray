package com.material.xray.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.material.xray.R
import com.material.xray.ui.adaptive.TwoPaneMinWidth
import com.material.xray.ui.adaptive.useNavigationRail
import com.material.xray.ui.components.AppTopBarHeight
import com.material.xray.ui.components.LocalTopBarTint
import com.material.xray.ui.components.TopBarTint
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
) {
    val viewModel: MainNavigationViewModel = hiltViewModel()
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadedSettings = settings ?: return
    val showTitleBarLogo = loadedSettings.showTitleBarLogo
    val floatingConnectButton = loadedSettings.floatingConnectButton
    val showAdvancedOptions = loadedSettings.showAdvancedOptions
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    var previousRoute by remember { mutableStateOf<String?>(currentRoute) }
    val bottomInset = with(LocalDensity.current) {
        NavigationBarDefaults.windowInsets.getBottom(this).toDp()
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

    LaunchedEffect(currentRoute) {
        if (previousRoute == Screen.Routing.route && currentRoute != Screen.Routing.route) {
            viewModel.onLeavingRoutingTab()
        }
        previousRoute = currentRoute
    }

    LaunchedEffect(showAdvancedOptions, currentRoute) {
        if (!showAdvancedOptions && currentRoute == Screen.Logs.route) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(pendingSubscriptionLink) {
        if (pendingSubscriptionLink != null && currentRoute != Screen.Home.route) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // The viewer covers the whole app, navigation bar included, so opening it does not make the
    // bar pop out of existence while the screen is still fading in.
    var configViewerRequest by rememberSaveable(stateSaver = ConfigViewerRequestSaver) {
        mutableStateOf<ConfigViewerRequest?>(null)
    }
    var routingRuleViewerRequest by rememberSaveable(stateSaver = RoutingRuleViewerRequestSaver) {
        mutableStateOf<RoutingRuleViewerRequest?>(null)
    }
    var routingRuleEditorRequest by rememberSaveable(stateSaver = RoutingRuleEditorRequestSaver) {
        mutableStateOf<EditableRoutingRule?>(null)
    }

    val useRail = useNavigationRail()
    val topBarTint = remember { TopBarTint() }
    val navigateTo: (Screen) -> Unit = { screen ->
        navController.navigate(screen.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val isSelected: (Screen) -> Boolean = { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    BoxWithConstraints {
        // On a wide window the viewers and the editor open as a sheet from the end edge, leaving
        // the list they were opened from in view instead of replacing the whole app.
        val detailAsSheet = maxWidth >= TwoPaneMinWidth
        val sheetWidth = (maxWidth * DETAIL_SHEET_WIDTH_FRACTION).coerceIn(DetailSheetMinWidth, DetailSheetMaxWidth)
        Row {
            if (useRail) {
                AppNavigationRail(
                    showLogs = showAdvancedOptions,
                    topBarScrollFraction = { topBarTint.fraction },
                    isSelected = isSelected,
                    onNavigate = navigateTo,
                )
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                // Without a bottom bar to absorb them, the rail layout has to keep content clear
                // of the gesture area itself, and of a side navigation bar or cutout at the end.
                contentWindowInsets = if (useRail) {
                    WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)
                } else {
                    WindowInsets(0.dp)
                },
                bottomBar = {
                    if (!useRail) {
                        AppNavigationBar(
                            showLogs = showAdvancedOptions,
                            height = CompactNavigationBarHeight + bottomInset,
                            isSelected = isSelected,
                            onNavigate = navigateTo,
                        )
                    }
                },
            ) { innerPadding ->
                CompositionLocalProvider(LocalTopBarTint provides topBarTint) {
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route,
                        // Consumed so the screens' own top bars do not add the rail layout's end inset again.
                        modifier = Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                showTitleBarLogo = showTitleBarLogo,
                                floatingConnectButton = floatingConnectButton,
                                pendingSubscriptionLink = pendingSubscriptionLink,
                                onSubscriptionLinkHandled = onSubscriptionLinkHandled,
                                onOpenServerConfig = { serverId, name ->
                                    configViewerRequest = ConfigViewerRequest.Server(serverId, name)
                                },
                                onViewRunningConfig = { configViewerRequest = ConfigViewerRequest.Running },
                            )
                        }
                        composable(Screen.Logs.route) { LogsScreen(showTitleBarLogo) }
                        composable(Screen.Routing.route) {
                            RoutingScreen(
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
                        }
                        composable(Screen.Settings.route) { SettingsScreen(showTitleBarLogo) }
                    }
                }
            }
        }

        AnimatedContent(
            targetState = configViewerRequest,
            transitionSpec = {
                fadeIn(tween(CONFIG_VIEWER_FADE_MS)) togetherWith fadeOut(tween(CONFIG_VIEWER_FADE_MS)) using null
            },
            label = "configViewer",
        ) { request ->
            if (request != null) {
                // Registered as the viewer opens, so it outranks the NavHost's own back handling.
                BackHandler { configViewerRequest = null }
                DetailSheet(asSheet = detailAsSheet, width = sheetWidth, onDismiss = { configViewerRequest = null }) {
                    ConfigViewerScreen(request = request, onBack = { configViewerRequest = null })
                }
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
                DetailSheet(asSheet = detailAsSheet, width = sheetWidth, onDismiss = { routingRuleEditorRequest = null }) {
                    RoutingRuleEditorScreen(
                        editableRule = request,
                        viewModel = hiltViewModel<RoutingViewModel>(requireNotNull(navBackStackEntry)),
                        onBack = { routingRuleEditorRequest = null },
                    )
                }
            }
        }

        AnimatedContent(
            targetState = routingRuleViewerRequest,
            transitionSpec = {
                fadeIn(tween(CONFIG_VIEWER_FADE_MS)) togetherWith fadeOut(tween(CONFIG_VIEWER_FADE_MS)) using null
            },
            label = "routingRuleViewer",
        ) { request ->
            if (request != null) {
                // Opened from the Routing tab, where the NavHost would otherwise take back and pop to
                // Home underneath the viewer; registering here, on open, puts this handler first.
                BackHandler { routingRuleViewerRequest = null }
                DetailSheet(asSheet = detailAsSheet, width = sheetWidth, onDismiss = { routingRuleViewerRequest = null }) {
                    RoutingRuleViewerScreen(request = request, onBack = { routingRuleViewerRequest = null })
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
private const val CONFIG_VIEWER_FADE_MS = 180
private const val ROUTING_EDITOR_ENTER_MS = 200
private const val ROUTING_EDITOR_EXIT_MS = 140

private val CompactNavigationBarHeight = 68.dp
private const val DETAIL_SHEET_WIDTH_FRACTION = 0.5f
private val DetailSheetMinWidth = 480.dp
private val DetailSheetMaxWidth = 640.dp

/**
 * Hosts a full-screen subpage. On a phone it simply fills the window; on a wide one it becomes a
 * modal sheet along the end edge over a scrim, and tapping the scrim closes it like back does.
 */
@Composable
private fun DetailSheet(
    asSheet: Boolean,
    width: Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!asSheet) {
        content()
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = DETAIL_SHEET_SCRIM_ALPHA))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = stringResource(R.string.navigation_close_sheet),
                    onClick = onDismiss,
                ),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(width)
                .fillMaxHeight(),
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
            shadowElevation = 6.dp,
        ) {
            content()
        }
    }
}

private const val DETAIL_SHEET_SCRIM_ALPHA = 0.32f

@Composable
private fun AppNavigationBar(
    showLogs: Boolean,
    height: Dp,
    isSelected: (Screen) -> Boolean,
    onNavigate: (Screen) -> Unit,
) {
    AnimatedContent(
        targetState = showLogs,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
        label = "advancedNavigationItems",
    ) { showLogsItem ->
        NavigationBar(modifier = Modifier.height(height)) {
            navigationScreens(showLogsItem).forEach { screen ->
                NavigationBarItem(
                    icon = { ScreenIcon(screen) },
                    label = { Text(stringResource(screen.labelRes)) },
                    selected = isSelected(screen),
                    onClick = { onNavigate(screen) },
                )
            }
        }
    }
}

@Composable
private fun AppNavigationRail(
    showLogs: Boolean,
    topBarScrollFraction: () -> Float,
    isSelected: (Screen) -> Boolean,
    onNavigate: (Screen) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val scrolledSurface = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier = Modifier.background(surface)) {
        // The top bars start beside the rail. When one tints as content scrolls under it, this
        // band behind the rail's top edge takes the same colour, so the bar reads as running the
        // full width instead of leaving a notch in the status bar strip.
        // Sized from the rail rather than filling, so the band cannot widen the rail's slot.
        Column(modifier = Modifier.matchParentSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(lerp(surface, scrolledSurface, topBarScrollFraction())) },
            ) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Spacer(Modifier.height(AppTopBarHeight))
            }
        }
        AppNavigationRailItems(showLogs = showLogs, isSelected = isSelected, onNavigate = onNavigate)
    }
}

@Composable
private fun AppNavigationRailItems(
    showLogs: Boolean,
    isSelected: (Screen) -> Boolean,
    onNavigate: (Screen) -> Unit,
) {
    NavigationRail(containerColor = Color.Transparent) {
        // Centred rather than top-aligned: on a tablet held in landscape the middle of the edge is
        // where a thumb rests, and the top corner is the hardest place to reach.
        Spacer(Modifier.weight(1f))
        AnimatedContent(
            targetState = showLogs,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "advancedRailItems",
        ) { showLogsItem ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                navigationScreens(showLogsItem).forEach { screen ->
                    NavigationRailItem(
                        icon = { ScreenIcon(screen) },
                        label = { Text(stringResource(screen.labelRes)) },
                        selected = isSelected(screen),
                        onClick = { onNavigate(screen) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun navigationScreens(showLogs: Boolean): List<Screen> = if (showLogs) {
    Screen.entries
} else {
    Screen.entries.filterNot { it == Screen.Logs }
}

@Composable
private fun ScreenIcon(screen: Screen) {
    val label = stringResource(screen.labelRes)
    val icon = screen.icon
    if (icon != null) {
        Icon(icon, contentDescription = label)
    } else {
        Icon(painter = painterResource(requireNotNull(screen.iconRes)), contentDescription = label)
    }
}
