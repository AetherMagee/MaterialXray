package com.material.xray.ui.apps

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.ui.components.AnimatedDropdownMenu
import com.material.xray.ui.components.ScrollFadeEdges
import com.material.xray.ui.components.SelectableOptionRow
import com.material.xray.ui.components.TooltipIconButton
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBypassContent(active: Boolean, viewModel: AppsViewModel = koinViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val appLoadProgress by viewModel.appLoadProgress.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val automaticRoutingProviderName by viewModel.automaticRoutingProviderName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val iconSize = 40.dp
    val iconPixelSize = remember(density) { with(density) { iconSize.roundToPx() } }
    var editingApp by remember { mutableStateOf<AppItem?>(null) }
    val pullToRefreshState = rememberPullToRefreshState()
    val showInitialLoading = isLoadingApps && apps.isEmpty()

    DisposableEffect(lifecycleOwner, viewModel, active) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (active) viewModel.onVisible() else viewModel.onHidden()
                Lifecycle.Event.ON_STOP -> viewModel.onHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    PullToRefreshBox(
        isRefreshing = isLoadingApps && !showInitialLoading,
        onRefresh = { if (!isLoadingApps) viewModel.refreshApps() },
        modifier = Modifier.fillMaxSize(),
        state = pullToRefreshState,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showInitialLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = appLoadProgress?.let { progress ->
                                stringResource(R.string.apps_loading_progress, progress.processed, progress.total)
                            } ?: stringResource(R.string.apps_finding_apps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                return@Column
            }

            if (routingPolicyControl == RoutingPolicyControl.SubscriptionProvider) {
                SubscriptionRoutingBanner(automaticRoutingProviderName)
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                label = { Text(stringResource(R.string.apps_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
            )

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = apps,
                        key = { it.appKey },
                        contentType = { "app" },
                    ) { app ->
                        ListItem(
                            headlineContent = { Text(app.name) },
                            supportingContent = {
                                Text(
                                    text = if (app.workProfile) {
                                        stringResource(
                                            R.string.apps_package_with_profile,
                                            app.packageName,
                                            stringResource(R.string.apps_work_profile_label),
                                        )
                                    } else {
                                        app.packageName
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                val iconBitmap = remember(app.appKey, app.icon, iconPixelSize) {
                                    app.icon?.toBitmap(iconPixelSize, iconPixelSize)?.asImageBitmap()
                                }
                                iconBitmap?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = null,
                                        modifier = Modifier.size(iconSize),
                                    )
                                }
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.width(176.dp),
                                ) {
                                    Text(
                                        text = app.routeTitle.resolve(context),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            },
                            modifier = Modifier.clickable { editingApp = app },
                        )
                    }
                }
                ScrollFadeEdges()
            }
        }
    }

    editingApp?.let { app ->
        AppRouteEditor(
            app = app,
            onAppChange = { editingApp = it },
            viewModel = viewModel,
        )
    }
}

@Composable
private fun AppRouteEditor(
    app: AppItem,
    onAppChange: (AppItem?) -> Unit,
    viewModel: AppsViewModel,
) {
    val routeOptions by viewModel.routeOptions.collectAsStateWithLifecycle()
    val alwaysProxiedAvailable by viewModel.alwaysProxiedAvailable.collectAsStateWithLifecycle()
    val appSpecificServerNoteShown by viewModel.appSpecificServerNoteShown.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val automaticRoutingProviderName by viewModel.automaticRoutingProviderName.collectAsStateWithLifecycle()
    val providerAppRouting by viewModel.providerAppRouting.collectAsStateWithLifecycle()
    val visibleRouteOptions by remember(routeOptions) {
        derivedStateOf {
            if (routeOptions.count { it.kind == AppRouteKind.SERVER } == 1) {
                routeOptions.filterNot { it.kind == AppRouteKind.SERVER }
            } else {
                routeOptions
            }
        }
    }
    var pendingProviderEdit by remember { mutableStateOf<ProviderManagedAppEdit?>(null) }
    var pendingSpecificServerRoute by remember { mutableStateOf<AppRouteSelection?>(null) }

    fun applyRouteSelection(target: AppItem, option: AppRouteOption) {
        viewModel.setAppRoute(target, option)
        onAppChange(target.withSelectedRoute(option))
    }

    fun selectRoute(target: AppItem, option: AppRouteOption) {
        if (option.kind == AppRouteKind.SERVER && !appSpecificServerNoteShown) {
            pendingSpecificServerRoute = AppRouteSelection(target, option)
        } else {
            applyRouteSelection(target, option)
        }
    }

    fun applyAlwaysProxied(target: AppItem, enabled: Boolean) {
        viewModel.setAlwaysProxied(target, enabled)
        onAppChange(target.copy(alwaysProxied = enabled))
    }

    fun applyEdit(edit: ProviderManagedAppEdit) {
        when (edit) {
            is ProviderManagedAppEdit.Route -> selectRoute(edit.app, edit.option)
            is ProviderManagedAppEdit.AlwaysProxied -> applyAlwaysProxied(edit.app, edit.enabled)
        }
    }

    // Only apps the provider assigns a route to need automatic updates turned off before editing.
    fun requestEdit(edit: ProviderManagedAppEdit) {
        if (providerAppRouting?.assignmentModeFor(edit.app.packageName) != null) {
            pendingProviderEdit = edit
        } else {
            applyEdit(edit)
        }
    }

    LaunchedEffect(routingPolicyControl) {
        if (routingPolicyControl == RoutingPolicyControl.User) {
            pendingProviderEdit?.let { edit ->
                pendingProviderEdit = null
                applyEdit(edit)
            }
        }
    }

    AppRoutePickerDialog(
        app = app,
        routeOptions = visibleRouteOptions,
        singleServerRouteHidden = visibleRouteOptions.size != routeOptions.size,
        showAlwaysProxied = alwaysProxiedAvailable,
        onDismiss = { onAppChange(null) },
        onAlwaysProxiedChanged = { enabled -> requestEdit(ProviderManagedAppEdit.AlwaysProxied(app, enabled)) },
        onSelected = { option -> requestEdit(ProviderManagedAppEdit.Route(app, option)) },
    )

    if (pendingProviderEdit != null) {
        AutomaticRoutingDialog(
            providerName = automaticRoutingProviderName,
            onDismiss = { pendingProviderEdit = null },
            onSwitchToManual = {
                viewModel.switchToManualRouting()
            },
        )
    }

    pendingSpecificServerRoute?.let { selection ->
        SpecificServerRouteNoteDialog(
            onDismiss = { pendingSpecificServerRoute = null },
            onConfirm = {
                viewModel.setAppSpecificServerNoteShown()
                applyRouteSelection(selection.app, selection.option)
                pendingSpecificServerRoute = null
            },
        )
    }
}

@Composable
private fun SubscriptionRoutingBanner(providerName: String?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = providerName?.let {
                    stringResource(R.string.apps_routing_managed_by_provider, it)
                } ?: stringResource(R.string.apps_routing_managed_by_selected_subscription),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun AppRoutingMenuActions(viewModel: AppsViewModel = koinViewModel()) {
    val showSystemApps by viewModel.showSystemApps.collectAsStateWithLifecycle()
    val showWorkProfileApps by viewModel.showWorkProfileApps.collectAsStateWithLifecycle()
    val hasWorkProfileApps by viewModel.hasWorkProfileApps.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val automaticRoutingProviderName by viewModel.automaticRoutingProviderName.collectAsStateWithLifecycle()
    var pendingBulkAction by remember { mutableStateOf<BulkAppRouteAction?>(null) }
    var pendingAutomaticBulkAction by remember { mutableStateOf<BulkAppRouteAction?>(null) }
    var appRoutingMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(routingPolicyControl) {
        if (routingPolicyControl == RoutingPolicyControl.User) {
            pendingAutomaticBulkAction?.let { action ->
                pendingAutomaticBulkAction = null
                pendingBulkAction = action
            }
        }
    }

    fun requestBulkAction(action: BulkAppRouteAction) {
        if (routingPolicyControl == RoutingPolicyControl.SubscriptionProvider) {
            pendingAutomaticBulkAction = action
        } else {
            pendingBulkAction = action
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(
            tooltip = stringResource(R.string.apps_refresh),
            onClick = { viewModel.refreshApps() },
            enabled = !isLoadingApps,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.apps_refresh))
        }
        Box {
            TooltipIconButton(tooltip = stringResource(R.string.apps_routing_menu), onClick = { appRoutingMenuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.apps_routing_menu))
            }
            AnimatedDropdownMenu(
                expanded = appRoutingMenuExpanded,
                onDismissRequest = { appRoutingMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.apps_reset_to_defaults)) },
                    onClick = {
                        appRoutingMenuExpanded = false
                        requestBulkAction(BulkAppRouteAction.ResetToDefaults)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.apps_bypass_all)) },
                    onClick = {
                        appRoutingMenuExpanded = false
                        requestBulkAction(BulkAppRouteAction.BypassAllApps)
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.apps_show_system)) },
                    trailingIcon = {
                        Checkbox(
                            checked = showSystemApps,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        viewModel.setShowSystemApps(!showSystemApps)
                    },
                )
                if (hasWorkProfileApps) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.apps_show_work_profile)) },
                        trailingIcon = {
                            Checkbox(
                                checked = showWorkProfileApps,
                                onCheckedChange = null,
                            )
                        },
                        onClick = {
                            viewModel.setShowWorkProfileApps(!showWorkProfileApps)
                        },
                    )
                }
            }
        }
    }

    if (pendingAutomaticBulkAction != null) {
        AutomaticRoutingDialog(
            providerName = automaticRoutingProviderName,
            onDismiss = { pendingAutomaticBulkAction = null },
            onSwitchToManual = {
                viewModel.switchToManualRouting()
            },
        )
    }

    pendingBulkAction?.let { action ->
        BulkAppRouteConfirmationDialog(
            action = action,
            onDismiss = { pendingBulkAction = null },
            onConfirm = {
                when (action) {
                    BulkAppRouteAction.ResetToDefaults -> viewModel.resetAllToDefault()
                    BulkAppRouteAction.BypassAllApps -> viewModel.bypassAllApps()
                }
                pendingBulkAction = null
            },
        )
    }
}

private enum class BulkAppRouteAction {
    ResetToDefaults,
    BypassAllApps,
}

private sealed interface ProviderManagedAppEdit {
    val app: AppItem

    data class Route(override val app: AppItem, val option: AppRouteOption) : ProviderManagedAppEdit
    data class AlwaysProxied(override val app: AppItem, val enabled: Boolean) : ProviderManagedAppEdit
}

private data class AppRouteSelection(
    val app: AppItem,
    val option: AppRouteOption,
)

private fun AppItem.withSelectedRoute(option: AppRouteOption): AppItem {
    val forceProxy = alwaysProxied && (option.kind == AppRouteKind.DEFAULT || option.kind == AppRouteKind.SERVER)
    return copy(
        routeKey = option.key,
        routeKind = option.kind,
        alwaysProxied = forceProxy,
        customRouted = option.kind != AppRouteKind.DEFAULT || forceProxy,
        routeTitle = option.title,
        routeDescription = option.description,
    )
}

@Composable
private fun AutomaticRoutingDialog(
    providerName: String?,
    onDismiss: () -> Unit,
    onSwitchToManual: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.apps_routing_automatic_title),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                providerName?.let {
                    stringResource(R.string.apps_routing_automatic_provider_description, it)
                } ?: stringResource(R.string.apps_routing_automatic_selected_subscription_description),
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSwitchToManual,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.apps_switch_to_manual_mode))
                }
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.apps_leave_as_is))
                }
            }
        },
    )
}

@Composable
private fun SpecificServerRouteNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_specific_server_routing_title)) },
        text = {
            Text(stringResource(R.string.apps_specific_server_routing_description))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.apps_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.apps_cancel))
            }
        },
    )
}

@Composable
private fun BulkAppRouteConfirmationDialog(
    action: BulkAppRouteAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        BulkAppRouteAction.ResetToDefaults -> stringResource(R.string.apps_reset_to_defaults_title)
        BulkAppRouteAction.BypassAllApps -> stringResource(R.string.apps_bypass_all_title)
    }
    val description = when (action) {
        BulkAppRouteAction.ResetToDefaults -> stringResource(R.string.apps_reset_to_defaults_description)
        BulkAppRouteAction.BypassAllApps -> stringResource(R.string.apps_bypass_all_description)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.apps_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.apps_cancel))
            }
        },
    )
}

@Composable
private fun AppRoutePickerDialog(
    app: AppItem,
    routeOptions: List<AppRouteOption>,
    singleServerRouteHidden: Boolean,
    showAlwaysProxied: Boolean,
    onDismiss: () -> Unit,
    onAlwaysProxiedChanged: (Boolean) -> Unit,
    onSelected: (AppRouteOption) -> Unit,
) {
    val context = LocalContext.current
    var query by remember(app.appKey) { mutableStateOf("") }
    val filteredOptions by remember(routeOptions, query) {
        derivedStateOf {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                routeOptions
            } else {
                routeOptions.filter { option ->
                    option.title.resolve(context).contains(trimmed, ignoreCase = true) ||
                        option.description.resolve(context).contains(trimmed, ignoreCase = true)
                }
            }
        }
    }
    val presetOptions by remember(filteredOptions) {
        derivedStateOf { filteredOptions.filter { it.kind != AppRouteKind.SERVER } }
    }
    val serverOptions by remember(filteredOptions) {
        derivedStateOf { filteredOptions.filter { it.kind == AppRouteKind.SERVER } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.apps_close))
            }
        },
        title = { Text(stringResource(R.string.apps_route_title, app.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (routeOptions.size > 8) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.apps_search_configurations)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(
                        items = presetOptions,
                        key = { it.key },
                        contentType = { "routeOption" },
                    ) { option ->
                        RouteOptionRow(
                            option = option,
                            selected = option.key == app.routeKey ||
                                (singleServerRouteHidden && app.routeKind == AppRouteKind.SERVER && option.kind == AppRouteKind.DEFAULT),
                            onSelected = { onSelected(option) },
                        )
                    }
                    if (showAlwaysProxied) {
                        item(key = "alwaysProxied", contentType = "alwaysProxied") {
                            val enabled = app.routeKind == AppRouteKind.DEFAULT || app.routeKind == AppRouteKind.SERVER
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = null,
                                        indication = null,
                                        enabled = enabled,
                                    ) { onAlwaysProxiedChanged(!app.alwaysProxied) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = app.alwaysProxied, onCheckedChange = null, enabled = enabled)
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(stringResource(R.string.apps_route_always_proxied_title))
                                    Text(
                                        stringResource(R.string.apps_route_always_proxied_description),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    if (serverOptions.isNotEmpty()) {
                        item(contentType = "routeOptionDivider") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                        item(key = "serverHeading", contentType = "serverHeading") {
                            Text(
                                stringResource(R.string.apps_route_specific_server_heading),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(
                        items = serverOptions,
                        key = { it.key },
                        contentType = { "routeOption" },
                    ) { option ->
                        RouteOptionRow(
                            option = option,
                            selected = option.key == app.routeKey ||
                                (singleServerRouteHidden && app.routeKind == AppRouteKind.SERVER && option.kind == AppRouteKind.DEFAULT),
                            onSelected = { onSelected(option) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun RouteOptionRow(
    option: AppRouteOption,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    val context = LocalContext.current
    SelectableOptionRow(
        title = option.title.resolve(context),
        description = option.description.resolve(context),
        selected = selected,
        onSelected = onSelected,
    )
}

private fun AppRouteText.resolve(context: Context): String = when (this) {
    is AppRouteText.Resource -> context.getString(resourceId, *arguments.toTypedArray())
    is AppRouteText.PluralResource -> context.resources.getQuantityString(
        resourceId,
        quantity,
        *arguments.toTypedArray(),
    )
    is AppRouteText.Raw -> value
}
