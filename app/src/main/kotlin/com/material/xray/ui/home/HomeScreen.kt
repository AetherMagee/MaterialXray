package com.material.xray.ui.home

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.material.xray.R
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.ProviderRoutingAvailability
import com.material.xray.model.AppUpdate
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import com.material.xray.model.PingMethod
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.service.AppUpdateInstallProgress
import com.material.xray.service.AppUpdateInstallStage
import com.material.xray.service.ConnectionEvent
import com.material.xray.ui.components.DropdownOption
import com.material.xray.ui.components.FlatStateCard
import com.material.xray.ui.components.ReadOnlyDropdownField
import com.material.xray.ui.components.ScrolledTopAppBar
import com.material.xray.ui.components.SettingsSwitchRow
import com.material.xray.ui.components.rememberSystemState
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod")
@Composable
fun HomeScreen(
    showTitleBarLogo: Boolean,
    floatingConnectButton: Boolean,
    pendingSubscriptionLink: String?,
    onSubscriptionLinkHandled: () -> Unit,
    showDiagnosticsNotice: Boolean,
    onDiagnosticsNoticeDismiss: () -> Unit,
    onOpenServerConfig: (Long, String) -> Unit,
    onViewRunningConfig: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState = collectHomeUiState(viewModel)
    val connectionUiState = buildConnectionUiState(
        connectionState = uiState.connectionState,
        selectedServer = uiState.selectedServer,
        alwaysOnVpn = uiState.alwaysOnVpn,
    )

    LaunchedEffect(pendingSubscriptionLink) {
        pendingSubscriptionLink?.let {
            onSubscriptionLinkHandled()
            viewModel.addLink(it)
        }
    }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showAddMenu by rememberSaveable { mutableStateOf(false) }
    // The connection card stays thin until the user opts into the live stats.
    var connectionStatsExpanded by rememberSaveable { mutableStateOf(false) }
    // The floating connection card reports its height, so the list reserves exactly enough room
    // to scroll the last item clear of it, expanded stats included.
    var connectionCardHeight by remember { mutableStateOf(88.dp) }
    var showQrScanner by remember { mutableStateOf(false) }
    var keepQrScannerDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var showPingMethodDialog by remember { mutableStateOf(false) }
    var pingMethodDialogSubscriptionId by remember { mutableStateOf<Long?>(null) }
    var pendingDescriptionLink by remember { mutableStateOf<PendingSubscriptionLink?>(null) }
    var editingSubscriptionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingSubscription = uiState.subscriptions?.find { it.id == editingSubscriptionId }
    // Drop a parked edit id once the loaded list no longer contains it, so a later subscription
    // that happens to reuse the row id does not spontaneously reopen the edit dialog. A null list
    // means the data has not loaded yet and cannot say anything about the id.
    LaunchedEffect(uiState.subscriptions, editingSubscriptionId) {
        val id = editingSubscriptionId ?: return@LaunchedEffect
        val subscriptions = uiState.subscriptions ?: return@LaunchedEffect
        if (subscriptions.none { it.id == id }) {
            editingSubscriptionId = null
        }
    }
    LaunchedEffect(uiState.subscriptions, pingMethodDialogSubscriptionId) {
        val id = pingMethodDialogSubscriptionId ?: return@LaunchedEffect
        val subscriptions = uiState.subscriptions
        if (subscriptions == null || subscriptions.none { it.id == id }) {
            showPingMethodDialog = false
            pingMethodDialogSubscriptionId = null
        }
    }
    LaunchedEffect(uiState.subscriptions, pendingDescriptionLink) {
        val pending = pendingDescriptionLink ?: return@LaunchedEffect
        val subscriptions = uiState.subscriptions
        if (subscriptions == null ||
            subscriptions.none {
                it.id == pending.subscriptionId && it.announce?.trim().orEmpty() == pending.description
            }
        ) {
            pendingDescriptionLink = null
        }
    }
    var removeSubscriptionRequest by remember { mutableStateOf<Pair<SubscriptionEntity, Int>?>(null) }
    var showRootFallbackDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val collapsedSubscriptionIds = remember(context) {
        context.collapsedSubscriptionIds().toMutableStateList()
    }
    LaunchedEffect(uiState.subscriptions) {
        val currentIds = uiState.subscriptions?.mapTo(mutableSetOf()) { it.id } ?: return@LaunchedEffect
        if (collapsedSubscriptionIds.removeAll { it !in currentIds }) {
            context.setCollapsedSubscriptionIds(collapsedSubscriptionIds)
        }
    }
    val unableToFetchLinkText = stringResource(R.string.home_unable_to_fetch_link)
    val lifecycleOwner = LocalLifecycleOwner.current
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.connect()
        }
    }
    val openQrScanner = QrScannerPermissionGate { showQrScanner = true }
    val startRootlessConnection = {
        val vpnPermissionIntent = VpnService.prepare(context)
        if (vpnPermissionIntent != null) {
            vpnPermissionLauncher.launch(vpnPermissionIntent)
        } else {
            viewModel.connect()
        }
    }
    val pasteFromClipboard = {
        val link = context.clipboardText()
        if (link == null) {
            Toast.makeText(context, unableToFetchLinkText, Toast.LENGTH_SHORT).show()
        } else {
            viewModel.addLink(link)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshTunnelInterfaceState()
        viewModel.checkForAppUpdateIfDue()
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.connectionEvents.collect { event ->
                when (event) {
                    ConnectionEvent.RootUnavailableFallback -> showRootFallbackDialog = true
                }
            }
        }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiEvents.collect { event ->
                when (event) {
                    is HomeUiEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(showQrScanner) {
        if (showQrScanner) {
            keepQrScannerDialog = true
        } else {
            delay(QR_SCANNER_TRANSITION_MS.toLong())
            keepQrScannerDialog = false
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshTunnelInterfaceState()
                    viewModel.resumePendingAppUpdateInstall()
                }
                Lifecycle.Event.ON_STOP -> {
                    showQrScanner = false
                    keepQrScannerDialog = false
                    viewModel.onHidden()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    val onConnectionClick = {
        connectionUiState.handleClick(
            context = context,
            useRootService = uiState.useRootService,
            disconnect = viewModel::disconnect,
            connectRoot = viewModel::connect,
            connectVpn = startRootlessConnection,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                ScrolledTopAppBar(
                    title = stringResource(R.string.app_name),
                    scrollBehavior = topAppBarScrollBehavior,
                    showLogo = showTitleBarLogo,
                    actions = {
                        Box {
                            IconButton(onClick = { showAddMenu = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add_24),
                                    contentDescription = stringResource(R.string.home_add_server_or_subscription),
                                )
                            }
                            AddSubscriptionMenu(
                                expanded = showAddMenu,
                                onDismissRequest = { showAddMenu = false },
                                onPasteFromClipboard = pasteFromClipboard,
                                onScanQrCode = openQrScanner,
                                onAddManually = { showAddDialog = true },
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                ConnectionFab(
                    visible = floatingConnectButton,
                    state = connectionUiState,
                    canStart = uiState.selectedServer != null,
                    onClick = onConnectionClick,
                    onViewConfig = onViewRunningConfig,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = homeListContentPadding(floatingConnectButton, connectionCardHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showDiagnosticsNotice) {
                    item(key = "diagnosticsNotice", contentType = "diagnosticsNotice") {
                        DiagnosticsNoticeBanner(onDismiss = onDiagnosticsNoticeDismiss)
                    }
                }

                uiState.availableUpdate?.let { update ->
                    item(key = "appUpdate", contentType = "appUpdate") {
                        AppUpdateBanner(
                            update = update,
                            installProgress = uiState.appUpdateInstallProgress,
                            onInstall = { viewModel.installAppUpdate(update) },
                        )
                    }
                }

                if (floatingConnectButton) {
                    item(key = "connectionPanel", contentType = "connectionPanel") {
                        ConnectionPanel(
                            connectionState = uiState.connectionState,
                            connectionProgress = uiState.connectionProgress,
                            geoDataDownloadFraction = uiState.geoDataDownloadFraction,
                            showProgressDetails = uiState.showAdvancedOptions,
                            selectedServerName = connectionUiState.displayServerName,
                            activeBalancer = uiState.activeBalancer,
                            pingMs = viewModel.activeServerPingMs,
                            sessionTraffic = viewModel.sessionTraffic,
                            buttonColor = connectionUiState.buttonColor,
                            isConnected = connectionUiState.isConnected,
                            isRestartRequired = connectionUiState.isRestartRequired,
                            isInterfaceBusy = connectionUiState.isInterfaceBusy,
                            isTransitioning = connectionUiState.isTransitioning,
                            isAlwaysOnVpn = connectionUiState.isAlwaysOnVpn,
                            canStart = uiState.selectedServer != null,
                            compact = true,
                            onClick = onConnectionClick,
                            onViewConfig = onViewRunningConfig,
                        )
                    }
                }

                val errorState = uiState.connectionState as? ConnectionState.Error
                if (errorState != null) {
                    item {
                        ErrorCard(message = errorState.message)
                    }
                }

                val subscriptions = uiState.subscriptions
                when {
                    subscriptions == null -> item {
                        val loadingText = stringResource(R.string.home_loading_subscriptions)
                        FlatStateCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = loadingText
                                    },
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clearAndSetSemantics {},
                                )
                                Text(
                                    text = loadingText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    subscriptions.isEmpty() -> item {
                        EmptySubscriptionsCard(
                            onPasteFromClipboard = pasteFromClipboard,
                            onScanQrCode = openQrScanner,
                            onAddManually = { showAddDialog = true },
                        )
                    }
                    else -> {
                        items(
                            items = subscriptions,
                            key = { it.id },
                            contentType = { "subscription" },
                        ) { subscription ->
                            val servers = uiState.serversBySubscription[subscription.id].orEmpty()
                            val manualRouting = subscription.manualRoutingData(
                                policy = uiState.routingPolicyControl,
                                selectedProvider = uiState.providerRoutingAvailability,
                            )
                            SubscriptionCard(
                                subscription = subscription,
                                isRefreshing = subscription.id in uiState.refreshingSubscriptionIds,
                                servers = servers,
                                selectedServerId = uiState.selectedServerId,
                                defaultPingMethod = uiState.defaultPingMethod,
                                canApplyRouting = manualRouting.appRouting != null || manualRouting.routing != null,
                                canCollapse = subscriptions.size > 1,
                                expanded = subscription.id !in collapsedSubscriptionIds,
                                canReorder = subscriptions.size > 1,
                                onExpandedChange = { expanded ->
                                    context.setSubscriptionExpanded(
                                        collapsedSubscriptionIds,
                                        subscription.id,
                                        expanded,
                                    )
                                },
                                onDelete = {
                                    if (servers.isEmpty()) {
                                        viewModel.deleteSubscription(subscription)
                                    } else {
                                        removeSubscriptionRequest = subscription to servers.size
                                    }
                                },
                                onEdit = { editingSubscriptionId = subscription.id },
                                onReorder = { showReorderDialog = true },
                                onRefresh = { viewModel.refreshSubscription(subscription) },
                                onTestAll = { viewModel.testSubscriptionLatencies(subscription) },
                                onPingMethodRequested = {
                                    showPingMethodDialog = true
                                    pingMethodDialogSubscriptionId = subscription.id
                                },
                                onDescriptionUrlClick = { url ->
                                    pendingDescriptionLink = PendingSubscriptionLink(
                                        subscriptionId = subscription.id,
                                        description = subscription.announce?.trim().orEmpty(),
                                        url = url,
                                    )
                                },
                                onApplyRouting = { viewModel.requestApplySubscriptionRouting(subscription) },
                                onDescriptionHiddenChange = { hidden ->
                                    viewModel.setSubscriptionDescriptionHidden(subscription.id, hidden)
                                },
                                onServerSelected = { viewModel.selectServer(it) },
                                onTestLatency = { viewModel.testLatency(it) },
                                onOpenServerConfig = onOpenServerConfig,
                            )
                        }
                    }
                }
            }
        }

        // The connection card floats over the list and stays clear of the bottom navigation
        // bar, so opening its stats moves only this card and never the bar or the screen.
        if (!floatingConnectButton) {
            ConnectionPanel(
                connectionState = uiState.connectionState,
                connectionProgress = uiState.connectionProgress,
                geoDataDownloadFraction = uiState.geoDataDownloadFraction,
                showProgressDetails = uiState.showAdvancedOptions,
                selectedServerName = connectionUiState.displayServerName,
                activeBalancer = uiState.activeBalancer,
                pingMs = viewModel.activeServerPingMs,
                sessionTraffic = viewModel.sessionTraffic,
                buttonColor = connectionUiState.buttonColor,
                isConnected = connectionUiState.isConnected,
                isRestartRequired = connectionUiState.isRestartRequired,
                isInterfaceBusy = connectionUiState.isInterfaceBusy,
                isTransitioning = connectionUiState.isTransitioning,
                isAlwaysOnVpn = connectionUiState.isAlwaysOnVpn,
                canStart = uiState.selectedServer != null,
                compact = false,
                onClick = onConnectionClick,
                onViewConfig = onViewRunningConfig,
                statsExpanded = connectionStatsExpanded,
                onStatsExpandedChange = { connectionStatsExpanded = it },
                onCardHeightChange = { connectionCardHeight = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }

    AddSubscriptionDialogHost(
        visible = showAddDialog,
        onDismiss = { showAddDialog = false },
        onConfirm = { name, url, preferJson, allowInsecureUpdates, userAgentMode, customUserAgent, customHeaders ->
            viewModel.addSubscription(
                name,
                url,
                preferJson,
                allowInsecureUpdates,
                userAgentMode,
                customUserAgent,
                customHeaders,
            )
            showAddDialog = false
        },
    )
    QrScannerDialogHost(
        keepDialog = keepQrScannerDialog,
        visible = showQrScanner,
        onVisibleChange = { showQrScanner = it },
        onLinkScanned = { link ->
            val trimmed = link.trim()
            if (trimmed.isEmpty()) {
                Toast.makeText(context, unableToFetchLinkText, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.addLink(trimmed)
            }
        },
    )
    PingMethodDialogHost(
        visible = showPingMethodDialog,
        selectedMethod = uiState.defaultPingMethod,
        onDismiss = {
            showPingMethodDialog = false
            pingMethodDialogSubscriptionId = null
        },
        onSelected = { method ->
            viewModel.setDefaultPingMethod(method)
            showPingMethodDialog = false
            pingMethodDialogSubscriptionId = null
        },
    )
    SubscriptionDescriptionDialogHost(
        url = pendingDescriptionLink?.url,
        onDismiss = { pendingDescriptionLink = null },
        onConfirm = { url ->
            pendingDescriptionLink = null
            uriHandler.openUri(url)
        },
    )
    ApplySubscriptionRoutingDialogHost(
        visible = uiState.pendingSubscriptionRouting != null,
        onDismiss = viewModel::dismissPendingSubscriptionRouting,
        onConfirm = viewModel::applyPendingSubscriptionRouting,
    )
    RootFallbackDialogHost(
        visible = showRootFallbackDialog,
        onDismiss = { showRootFallbackDialog = false },
        onConfirm = {
            showRootFallbackDialog = false
            startRootlessConnection()
        },
    )
    RemoveSubscriptionDialogHost(
        request = removeSubscriptionRequest,
        onDismiss = { removeSubscriptionRequest = null },
        onConfirm = { subscription ->
            viewModel.deleteSubscription(subscription)
            removeSubscriptionRequest = null
        },
    )
    ReorderSubscriptionsDialogHost(
        visible = showReorderDialog,
        subscriptions = uiState.subscriptions.orEmpty(),
        onDismiss = { showReorderDialog = false },
        onConfirm = { subscriptionIds ->
            viewModel.reorderSubscriptions(subscriptionIds)
            showReorderDialog = false
        },
    )
    EditSubscriptionDialogHost(
        subscription = editingSubscription,
        onDismiss = { editingSubscriptionId = null },
        onConfirm = { subscription, name, url, preferJson, allowInsecureUpdates, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders ->
            viewModel.updateSubscription(
                subscription,
                name,
                url,
                preferJson,
                allowInsecureUpdates,
                autoUpdateIntervalHours,
                userAgentMode,
                customUserAgent,
                customHeaders,
            )
            editingSubscriptionId = null
        },
    )
    InstallPermissionRationaleDialogHost(
        visible = uiState.showInstallPermissionRationale,
        onDismiss = viewModel::dismissInstallPermissionRationale,
        onConfirm = viewModel::confirmInstallPermissionRationale,
    )
    DiscardEditedActiveConfigDialogHost(
        visible = uiState.pendingServerSelection != null,
        onDismiss = viewModel::dismissDiscardEditedActiveConfig,
        onConfirm = viewModel::confirmDiscardEditedActiveConfig,
    )
    HwidRequiredDialogHost(
        visible = uiState.pendingHwidServerSelection != null,
        onDismiss = viewModel::dismissHwidRequiredSelection,
        onConfirm = viewModel::confirmHwidRequiredSelection,
    )
}

@Composable
private fun QrScannerPermissionGate(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var promptAccess by remember { mutableStateOf<CameraPermissionAccess?>(null) }
    val accessState = rememberSystemState { cameraPermissionAccess(it) }
    val access = accessState.value
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        context.recordCameraPermissionRequest()
        accessState.refresh()
        if (granted) {
            promptAccess = null
            onGranted()
        } else {
            promptAccess = cameraPermissionAccess(context)
        }
    }

    promptAccess?.let { requestedAccess ->
        CameraPermissionDialogHost(
            requestedAccess = requestedAccess,
            onDismiss = { promptAccess = null },
            onConfirm = {
                promptAccess = null
                if (requestedAccess == CameraPermissionAccess.SystemSettings) {
                    context.openAppSettings()
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
        )
    }

    return {
        when (access) {
            CameraPermissionAccess.Granted -> onGranted()
            else -> promptAccess = access
        }
    }
}

private fun cameraPermissionAccess(context: Context): CameraPermissionAccess {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val activity = context as? android.app.Activity
    return resolveCameraPermissionAccess(
        granted = granted,
        shouldShowRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA),
        permissionRequested = context.wasCameraPermissionRequested(),
    )
}

internal fun resolveCameraPermissionAccess(
    granted: Boolean,
    shouldShowRationale: Boolean,
    permissionRequested: Boolean,
): CameraPermissionAccess = when {
    granted -> CameraPermissionAccess.Granted
    shouldShowRationale -> CameraPermissionAccess.Rationale
    permissionRequested -> CameraPermissionAccess.SystemSettings
    else -> CameraPermissionAccess.Requestable
}

private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

private fun Context.recordCameraPermissionRequest() {
    getSharedPreferences(CAMERA_PERMISSION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(CAMERA_PERMISSION_REQUESTED, true)
        .apply()
}

private fun Context.wasCameraPermissionRequested(): Boolean = getSharedPreferences(
    CAMERA_PERMISSION_PREFS,
    Context.MODE_PRIVATE,
).getBoolean(CAMERA_PERMISSION_REQUESTED, false)

private fun Context.collapsedSubscriptionIds(): List<Long> = getSharedPreferences(
    HOME_UI_PREFS,
    Context.MODE_PRIVATE,
).getStringSet(COLLAPSED_SUBSCRIPTION_IDS, emptySet())
    .orEmpty()
    .mapNotNull(String::toLongOrNull)

private fun Context.setCollapsedSubscriptionIds(ids: Collection<Long>) {
    getSharedPreferences(HOME_UI_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(COLLAPSED_SUBSCRIPTION_IDS, ids.mapTo(mutableSetOf(), Long::toString))
        .apply()
}

private fun Context.setSubscriptionExpanded(
    collapsedIds: SnapshotStateList<Long>,
    subscriptionId: Long,
    expanded: Boolean,
) {
    if (expanded) {
        collapsedIds.remove(subscriptionId)
    } else if (subscriptionId !in collapsedIds) {
        collapsedIds.add(subscriptionId)
    }
    setCollapsedSubscriptionIds(collapsedIds)
}

internal enum class CameraPermissionAccess {
    Granted,
    Requestable,
    Rationale,
    SystemSettings,
}

@Composable
internal fun ReorderSubscriptionsDialog(
    subscriptions: List<SubscriptionEntity>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val order = remember(subscriptions.map { it.id }) { subscriptions.toMutableStateList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_reorder_subscriptions_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.home_reorder_subscriptions_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ReorderableSubscriptionList(order)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(order.map { it.id }) }) {
                Text(stringResource(R.string.home_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}

@Composable
private fun ReorderableSubscriptionList(order: SnapshotStateList<SubscriptionEntity>) {
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Long, Int>() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        order.forEach { subscription ->
            key(subscription.id) {
                val dragging = subscription.id == draggingId
                val currentIndex = order.indexOf(subscription)
                val moveUpLabel = stringResource(R.string.home_move_up)
                val moveDownLabel = stringResource(R.string.home_move_down)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { heights[subscription.id] = it.size.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffsetY else 0f }
                        .padding(vertical = 4.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .then(
                            if (dragging) {
                                Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                            } else {
                                Modifier
                            },
                        )
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = stringResource(R.string.home_drag_to_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .semantics {
                                customActions = buildList {
                                    if (currentIndex > 0) {
                                        add(
                                            CustomAccessibilityAction(moveUpLabel) {
                                                order.add(currentIndex - 1, order.removeAt(currentIndex))
                                                true
                                            },
                                        )
                                    }
                                    if (currentIndex < order.lastIndex) {
                                        add(
                                            CustomAccessibilityAction(moveDownLabel) {
                                                order.add(currentIndex + 1, order.removeAt(currentIndex))
                                                true
                                            },
                                        )
                                    }
                                }
                            }.pointerInput(subscription.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingId = subscription.id
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        val current = order.indexOf(subscription)
                                        if (dragAmount.y < 0 && current > 0) {
                                            val above = order[current - 1]
                                            val height = heights[above.id] ?: 0
                                            if (-dragOffsetY > height / 2f) {
                                                order.add(current - 1, order.removeAt(current))
                                                dragOffsetY += height
                                            }
                                        } else if (dragAmount.y > 0 && current < order.lastIndex) {
                                            val below = order[current + 1]
                                            val height = heights[below.id] ?: 0
                                            if (dragOffsetY > height / 2f) {
                                                order.add(current + 1, order.removeAt(current))
                                                dragOffsetY -= height
                                            }
                                        }
                                    },
                                )
                            },
                    )
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun collectHomeUiState(viewModel: HomeViewModel): HomeUiState {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectionProgress by viewModel.connectionProgress.collectAsStateWithLifecycle()
    val geoDataDownloadFraction by viewModel.geoDataDownloadFraction.collectAsStateWithLifecycle()
    val alwaysOnVpn by viewModel.alwaysOnVpn.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val activeBalancer by viewModel.activeBalancer.collectAsStateWithLifecycle()
    val selectedServerId by viewModel.selectedServerId.collectAsStateWithLifecycle()
    val useRootService by viewModel.useRootService.collectAsStateWithLifecycle()
    val showAdvancedOptions by viewModel.showAdvancedOptions.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val serversBySubscription by viewModel.serversBySubscription.collectAsStateWithLifecycle()
    val refreshingSubscriptionIds by viewModel.refreshingSubscriptionIds.collectAsStateWithLifecycle()
    val defaultPingMethod by viewModel.defaultPingMethod.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val providerRoutingAvailability by viewModel.providerRoutingAvailability.collectAsStateWithLifecycle()
    val pendingSubscriptionRouting by viewModel.pendingSubscriptionRouting.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val appUpdateInstallProgress by viewModel.appUpdateInstallProgress.collectAsStateWithLifecycle()
    val showInstallPermissionRationale by viewModel.showInstallPermissionRationale.collectAsStateWithLifecycle()
    val pendingServerSelection by viewModel.pendingServerSelection.collectAsStateWithLifecycle()
    val pendingHwidServerSelection by viewModel.pendingHwidServerSelection.collectAsStateWithLifecycle()

    return HomeUiState(
        connectionState = connectionState,
        connectionProgress = connectionProgress,
        geoDataDownloadFraction = geoDataDownloadFraction,
        alwaysOnVpn = alwaysOnVpn,
        selectedServer = selectedServer,
        activeBalancer = activeBalancer,
        selectedServerId = selectedServerId,
        useRootService = useRootService,
        showAdvancedOptions = showAdvancedOptions,
        subscriptions = subscriptions,
        serversBySubscription = serversBySubscription,
        refreshingSubscriptionIds = refreshingSubscriptionIds,
        defaultPingMethod = defaultPingMethod,
        routingPolicyControl = routingPolicyControl,
        providerRoutingAvailability = providerRoutingAvailability,
        pendingSubscriptionRouting = pendingSubscriptionRouting,
        availableUpdate = availableUpdate,
        appUpdateInstallProgress = appUpdateInstallProgress,
        showInstallPermissionRationale = showInstallPermissionRationale,
        pendingServerSelection = pendingServerSelection,
        pendingHwidServerSelection = pendingHwidServerSelection,
    )
}

private data class HomeUiState(
    val connectionState: ConnectionState,
    val connectionProgress: ConnectionProgress?,
    val geoDataDownloadFraction: Float?,
    val alwaysOnVpn: Boolean,
    val selectedServer: ServerConfig?,
    val activeBalancer: ActiveBalancerState?,
    val selectedServerId: Long,
    val useRootService: Boolean,
    val showAdvancedOptions: Boolean,
    /** `null` until the home data snapshot has loaded; distinct from a loaded empty list. */
    val subscriptions: List<SubscriptionEntity>?,
    val serversBySubscription: Map<Long, List<ServerListItem>>,
    val refreshingSubscriptionIds: Set<Long>,
    val defaultPingMethod: PingMethod,
    val routingPolicyControl: RoutingPolicyControl,
    val providerRoutingAvailability: ProviderRoutingAvailability?,
    val pendingSubscriptionRouting: SubscriptionRoutingData?,
    val availableUpdate: AppUpdate?,
    val appUpdateInstallProgress: AppUpdateInstallProgress?,
    val showInstallPermissionRationale: Boolean,
    /** Server awaiting confirmation because switching to it discards an edited active config. */
    val pendingServerSelection: Long?,
    /** Server awaiting confirmation because its subscription requires the hardware ID. */
    val pendingHwidServerSelection: Long?,
)

private data class PendingSubscriptionLink(
    val subscriptionId: Long,
    val description: String,
    val url: String,
)

/** Floating controls overlay the list, so the last item needs room to scroll clear of them. */
private fun homeListContentPadding(floatingConnectButton: Boolean, connectionCardHeight: Dp) = PaddingValues(
    start = 16.dp,
    top = 16.dp,
    end = 16.dp,
    // The compact FAB floats; the floating connection card reports its own height and the gap it
    // keeps above the navigation bar, expanded stats included, so the last row can always scroll
    // above it.
    bottom = 16.dp + if (floatingConnectButton) FloatingConnectButtonClearance else connectionCardHeight,
)

@Composable
private fun ErrorCard(message: String) {
    FlatStateCard {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DiagnosticsNoticeBanner(onDismiss: () -> Unit) {
    FlatStateCard {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.diagnostics_default_notice),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.diagnostics_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AppUpdateBanner(
    update: AppUpdate,
    installProgress: AppUpdateInstallProgress?,
    onInstall: () -> Unit,
) {
    FlatStateCard(
        modifier = Modifier.clickable(enabled = installProgress == null, onClick = onInstall),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_app_update_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.home_app_update_message, update.tagName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (installProgress != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = appUpdateInstallProgressText(installProgress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val fraction = installProgress.fraction
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun appUpdateInstallProgressText(progress: AppUpdateInstallProgress): String = when (progress.stage) {
    AppUpdateInstallStage.ResolvingRelease -> stringResource(R.string.home_app_update_progress_resolving)
    AppUpdateInstallStage.Connecting -> stringResource(R.string.home_app_update_progress_connecting)
    AppUpdateInstallStage.Downloading -> progress.fraction?.let { fraction ->
        stringResource(R.string.home_app_update_progress_downloading_percent, (fraction * 100).roundToInt())
    } ?: stringResource(R.string.home_app_update_progress_downloading)
    AppUpdateInstallStage.Verifying -> stringResource(R.string.home_app_update_progress_verifying)
    AppUpdateInstallStage.PreparingInstallation -> stringResource(R.string.home_app_update_progress_preparing)
    AppUpdateInstallStage.OpeningInstaller -> stringResource(R.string.home_app_update_progress_opening_installer)
    AppUpdateInstallStage.InstallingWithRoot -> stringResource(R.string.home_app_update_progress_installing_root)
}

@Composable
private fun EmptySubscriptionsCard(
    onPasteFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onAddManually: () -> Unit,
) {
    FlatStateCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.home_no_subscriptions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.home_no_subscriptions_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            AddSubscriptionActionButton(
                onPasteFromClipboard = onPasteFromClipboard,
                onScanQrCode = onScanQrCode,
                onAddManually = onAddManually,
            )
        }
    }
}

@Composable
private fun AddSubscriptionActionButton(
    modifier: Modifier = Modifier,
    onPasteFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onAddManually: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_add_server_or_subscription))
        }
        AddSubscriptionMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            onPasteFromClipboard = onPasteFromClipboard,
            onScanQrCode = onScanQrCode,
            onAddManually = onAddManually,
        )
    }
}

@Composable
private fun AddSubscriptionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onAddManually: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_paste_from_clipboard)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_content_paste_24),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onPasteFromClipboard()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_scan_qr_code)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_scanner_24),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onScanQrCode()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.home_add_manually)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_add_24),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onAddManually()
            },
        )
    }
}

/** 64dp button plus its 16dp scaffold inset, so list content can scroll clear of it. */
private val FloatingConnectButtonClearance = 80.dp
internal const val QR_SCANNER_TRANSITION_MS = 180
private const val CAMERA_PERMISSION_PREFS = "camera_permission"
private const val CAMERA_PERMISSION_REQUESTED = "requested"
private const val HOME_UI_PREFS = "home_ui"
private const val COLLAPSED_SUBSCRIPTION_IDS = "collapsed_subscription_ids"

private fun Context.clipboardText(): String? {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private data class AutoUpdateIntervalOption(
    val intervalHours: Int,
)

private val autoUpdateIntervalOptions = listOf(
    AutoUpdateIntervalOption(1),
    AutoUpdateIntervalOption(3),
    AutoUpdateIntervalOption(6),
    AutoUpdateIntervalOption(24),
    AutoUpdateIntervalOption(72),
    AutoUpdateIntervalOption(0),
)

@Composable
private fun autoUpdateIntervalLabel(intervalHours: Int): String = when (intervalHours) {
    0 -> stringResource(R.string.home_duration_manual)
    24, 72 -> {
        val days = intervalHours / 24
        pluralStringResource(R.plurals.home_duration_days, days, days)
    }
    else -> pluralStringResource(R.plurals.home_duration_hours, intervalHours, intervalHours)
}

@Composable
internal fun EditSubscriptionDialog(
    subscription: SubscriptionEntity,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, Boolean, Int, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    var advancedExpanded by rememberSaveable(subscription.id) { mutableStateOf(false) }
    var name by rememberSaveable(subscription.id) { mutableStateOf(subscription.name) }
    var url by rememberSaveable(subscription.id) { mutableStateOf(subscription.url) }
    var preferJson by rememberSaveable(subscription.id) { mutableStateOf(subscription.preferJson ?: true) }
    var allowInsecureUpdates by rememberSaveable(subscription.id) {
        mutableStateOf(subscription.allowInsecureUpdates)
    }
    var autoUpdateIntervalHours by rememberSaveable(subscription.id) {
        mutableStateOf(subscription.autoUpdateIntervalHours)
    }
    var userAgentMode by rememberSaveable(subscription.id) {
        mutableStateOf(SubscriptionUserAgentMode.fromValue(subscription.userAgentMode))
    }
    var customUserAgent by rememberSaveable(subscription.id) {
        mutableStateOf(subscription.customUserAgent.orEmpty())
    }
    var customHeaders by rememberSaveable(subscription.id) {
        mutableStateOf(subscription.customHeaders.orEmpty())
    }
    val hasChanges = name.trim() != subscription.name ||
        url.trim() != subscription.url ||
        preferJson != (subscription.preferJson ?: true) ||
        allowInsecureUpdates != subscription.allowInsecureUpdates ||
        autoUpdateIntervalHours != subscription.autoUpdateIntervalHours ||
        userAgentMode != SubscriptionUserAgentMode.fromValue(subscription.userAgentMode) ||
        customUserAgent.trim().ifBlank { null } != subscription.customUserAgent ||
        customHeaders.trim().ifBlank { null } != subscription.customHeaders
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_edit_subscription_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.home_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.home_name_from_provider_hint)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.home_field_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                key(subscription.id) {
                    ReadOnlyDropdownField(
                        label = stringResource(R.string.home_auto_update_label),
                        selectedText = autoUpdateIntervalLabel(autoUpdateIntervalHours),
                        options = autoUpdateIntervalOptions.map { option ->
                            DropdownOption(
                                value = option.intervalHours,
                                label = autoUpdateIntervalLabel(option.intervalHours),
                            )
                        },
                        onSelected = { autoUpdateIntervalHours = it },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SubscriptionAdvancedOptions(
                    expanded = advancedExpanded,
                    onExpandedChange = { advancedExpanded = it },
                    preferJson = preferJson,
                    onPreferJsonChange = { preferJson = it },
                    allowInsecureUpdates = allowInsecureUpdates,
                    onAllowInsecureUpdatesChange = { allowInsecureUpdates = it },
                    userAgentMode = userAgentMode,
                    customUserAgent = customUserAgent,
                    customHeaders = customHeaders,
                    onUserAgentModeChange = { userAgentMode = it },
                    onCustomUserAgentChange = { customUserAgent = it },
                    onCustomHeadersChange = { customHeaders = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        name.trim(),
                        url.trim(),
                        preferJson,
                        allowInsecureUpdates,
                        autoUpdateIntervalHours,
                        userAgentMode,
                        customUserAgent,
                        customHeaders,
                    )
                },
                enabled = url.isNotBlank() && hasChanges,
            ) {
                Text(stringResource(R.string.home_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}

@Composable
internal fun ApplySubscriptionRoutingDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_apply_subscription_routing_title)) },
        text = { Text(stringResource(R.string.home_apply_subscription_routing_message)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.home_apply_subscription_routing_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_apply_subscription_routing_dismiss))
            }
        },
    )
}

@Composable
internal fun RemoveSubscriptionDialog(
    serverCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_remove_subscription_title)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.home_remove_subscription_message,
                    serverCount,
                    serverCount,
                ),
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.home_action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}

@Composable
internal fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, Boolean, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var preferJson by rememberSaveable { mutableStateOf(true) }
    var allowInsecureUpdates by rememberSaveable { mutableStateOf(false) }
    var userAgentMode by rememberSaveable { mutableStateOf(SubscriptionUserAgentMode.default) }
    var customUserAgent by rememberSaveable { mutableStateOf("") }
    var customHeaders by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_add_manually)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.home_field_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.home_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.home_name_from_provider_hint)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SubscriptionAdvancedOptions(
                    expanded = advancedExpanded,
                    onExpandedChange = { advancedExpanded = it },
                    preferJson = preferJson,
                    onPreferJsonChange = { preferJson = it },
                    allowInsecureUpdates = allowInsecureUpdates,
                    onAllowInsecureUpdatesChange = { allowInsecureUpdates = it },
                    userAgentMode = userAgentMode,
                    customUserAgent = customUserAgent,
                    customHeaders = customHeaders,
                    onUserAgentModeChange = { userAgentMode = it },
                    onCustomUserAgentChange = { customUserAgent = it },
                    onCustomHeadersChange = { customHeaders = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim(),
                        url.trim(),
                        preferJson,
                        allowInsecureUpdates,
                        userAgentMode,
                        customUserAgent,
                        customHeaders,
                    )
                },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.home_action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}

@Composable
private fun SubscriptionAdvancedOptions(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    preferJson: Boolean,
    onPreferJsonChange: (Boolean) -> Unit,
    allowInsecureUpdates: Boolean,
    onAllowInsecureUpdatesChange: (Boolean) -> Unit,
    userAgentMode: SubscriptionUserAgentMode,
    customUserAgent: String,
    customHeaders: String,
    onUserAgentModeChange: (SubscriptionUserAgentMode) -> Unit,
    onCustomUserAgentChange: (String) -> Unit,
    onCustomHeadersChange: (String) -> Unit,
) {
    TextButton(
        onClick = { onExpandedChange(!expanded) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.home_advanced),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.home_collapse_advanced else R.string.home_expand_advanced,
            ),
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        Column {
            Spacer(modifier = Modifier.height(8.dp))
            SubscriptionFetchTypeDropdown(
                preferJson = preferJson,
                onPreferJsonChange = onPreferJsonChange,
            )
            Spacer(modifier = Modifier.height(16.dp))
            SubscriptionUserAgentSection(
                selectedMode = userAgentMode,
                customUserAgent = customUserAgent,
                customHeaders = customHeaders,
                onModeChange = onUserAgentModeChange,
                onCustomUserAgentChange = onCustomUserAgentChange,
                onCustomHeadersChange = onCustomHeadersChange,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsSwitchRow(
                title = stringResource(R.string.home_allow_insecure_updates),
                description = stringResource(R.string.home_allow_insecure_updates_description),
                checked = allowInsecureUpdates,
                onCheckedChange = onAllowInsecureUpdatesChange,
            )
        }
    }
}

@Composable
private fun SubscriptionFetchTypeDropdown(
    preferJson: Boolean,
    onPreferJsonChange: (Boolean) -> Unit,
) {
    val jsonFirst = stringResource(R.string.home_fetch_type_json_first)
    val compatibility = stringResource(R.string.home_fetch_type_compatibility)

    ReadOnlyDropdownField(
        label = stringResource(R.string.home_fetch_type_label),
        selectedText = if (preferJson) jsonFirst else compatibility,
        supportingText = if (preferJson) {
            stringResource(R.string.home_fetch_type_json_first_description)
        } else {
            stringResource(R.string.home_fetch_type_compatibility_description)
        },
        options = listOf(
            DropdownOption(value = true, label = jsonFirst),
            DropdownOption(value = false, label = compatibility),
        ),
        onSelected = onPreferJsonChange,
    )
}

@Composable
private fun SubscriptionUserAgentSection(
    selectedMode: SubscriptionUserAgentMode,
    customUserAgent: String,
    customHeaders: String,
    onModeChange: (SubscriptionUserAgentMode) -> Unit,
    onCustomUserAgentChange: (String) -> Unit,
    onCustomHeadersChange: (String) -> Unit,
) {
    ReadOnlyDropdownField(
        label = stringResource(R.string.home_field_user_agent),
        selectedText = stringResource(selectedMode.labelResource),
        options = SubscriptionUserAgentMode.entries.map { mode ->
            DropdownOption(
                value = mode,
                label = stringResource(mode.labelResource),
                description = stringResource(mode.descriptionResource),
            )
        },
        onSelected = onModeChange,
    )
    if (selectedMode == SubscriptionUserAgentMode.CUSTOM) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customUserAgent,
            onValueChange = onCustomUserAgentChange,
            label = { Text(stringResource(R.string.home_field_user_agent)) },
            placeholder = {
                Text(
                    stringResource(
                        R.string.home_user_agent_example,
                        stringResource(R.string.home_user_agent_example_value),
                    ),
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customHeaders,
            onValueChange = onCustomHeadersChange,
            label = { Text(stringResource(R.string.home_field_headers)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(R.string.home_headers_example)) },
        )
    }
}
