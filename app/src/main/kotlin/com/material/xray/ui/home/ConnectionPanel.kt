package com.material.xray.ui.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.material.xray.R
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.model.SessionTrafficMetrics
import com.material.xray.ui.components.AppMotion
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun buildConnectionUiState(
    connectionState: ConnectionState,
    selectedServer: ServerConfig?,
    alwaysOnVpn: Boolean,
): ConnectionUiState {
    val isConnected = connectionState is ConnectionState.Connected
    val isRestartRequired = connectionState is ConnectionState.RestartRequired
    val isInterfaceBusy = connectionState is ConnectionState.InterfaceBusy
    val isTransitioning = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.ApplyingRoutingChanges ||
        connectionState is ConnectionState.UpdatingRoutingData ||
        connectionState is ConnectionState.Disconnecting
    val selectedServerName = selectedServer?.name ?: stringResource(R.string.home_no_server_selected)
    val stopLike = isConnected && !alwaysOnVpn || isRestartRequired || isInterfaceBusy

    return ConnectionUiState(
        isConnected = isConnected,
        isRestartRequired = isRestartRequired,
        isInterfaceBusy = isInterfaceBusy,
        isTransitioning = isTransitioning,
        isAlwaysOnVpn = alwaysOnVpn,
        buttonColor = when {
            stopLike -> MaterialTheme.colorScheme.error
            isTransitioning -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        // The compact button fills with the role colour itself rather than the softer *Container
        // pair: it is small and floats over scrolling content, so it needs the contrast.
        fabContentColor = when {
            stopLike -> MaterialTheme.colorScheme.onError
            isTransitioning -> MaterialTheme.colorScheme.onTertiary
            else -> MaterialTheme.colorScheme.onPrimary
        },
        displayServerName = (connectionState as? ConnectionState.Connected)?.serverName ?: selectedServerName,
    )
}

internal data class ConnectionUiState(
    val isConnected: Boolean,
    val isRestartRequired: Boolean,
    val isInterfaceBusy: Boolean,
    val isTransitioning: Boolean,
    val isAlwaysOnVpn: Boolean,
    val buttonColor: Color,
    val fabContentColor: Color,
    val displayServerName: String,
)

/** The progress or uptime line under the status, in a fixed-height slot so the card does not jump. */
@Composable
private fun ConnectionDetailLine(
    showProgressDetails: Boolean,
    connectionProgress: ConnectionProgress?,
    connectedStartTime: Long?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            showProgressDetails && connectionProgress != null -> Text(
                text = connectionProgressText(connectionProgress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
            connectedStartTime != null -> CoreUptime(startTime = connectedStartTime)
        }
    }
}

/**
 * The chevron that expands the live stats block, present only while stats can be shown. The card
 * floats above the bottom navigation bar and opens upward over the list, so the collapsed state
 * points up and the expanded state points back down.
 */
@Composable
private fun ConnectionStatsDisclosure(
    showsStats: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    if (!showsStats) return
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = AppMotion.spec(),
        label = "connectionStatsChevron",
    )
    IconButton(
        onClick = { onExpandedChange(!expanded) },
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(
                if (expanded) R.string.home_connection_hide_stats else R.string.home_connection_show_stats,
            ),
            modifier = Modifier
                .size(20.dp)
                .rotate(chevronRotation),
        )
    }
}

@Composable
internal fun ConnectionPanel(
    connectionState: ConnectionState,
    connectionProgress: ConnectionProgress?,
    geoDataDownloadFraction: Float?,
    showProgressDetails: Boolean,
    selectedServerName: String,
    activeBalancer: ActiveBalancerState?,
    pingMs: StateFlow<Int?>,
    sessionTraffic: StateFlow<SessionTrafficMetrics?>,
    buttonColor: Color,
    isConnected: Boolean,
    isRestartRequired: Boolean,
    isInterfaceBusy: Boolean,
    isTransitioning: Boolean,
    isAlwaysOnVpn: Boolean,
    canStart: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onViewConfig: () -> Unit,
    statsExpanded: Boolean = false,
    onStatsExpandedChange: (Boolean) -> Unit = {},
    onCardHeightChange: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val buttonEnabled = (canStart || isConnected || isRestartRequired || isInterfaceBusy) && !isTransitioning
    val density = LocalDensity.current
    val buttonContentColor = when {
        isRestartRequired || isInterfaceBusy || isConnected && !isAlwaysOnVpn -> MaterialTheme.colorScheme.onError
        isTransitioning -> MaterialTheme.colorScheme.onTertiary
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val actionLabel = stringResource(
        connectionActionLabel(
            isConnected = isConnected,
            isAlwaysOnVpn = isAlwaysOnVpn,
            isRestartRequired = isRestartRequired,
            isInterfaceBusy = isInterfaceBusy,
        ),
    )

    if (compact) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionStatusRow(
                heading = connectionHeading(connectionState, geoDataDownloadFraction),
                tone = connectionState.badgeTone(),
                detailText = connectionDetailText(isInterfaceBusy, isRestartRequired, selectedServerName),
                serverTextStyle = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
        }

        AnimatedVisibility(
            visible = connectionState.showsConnectionStats(),
            enter = fadeIn(AppMotion.spec()) + expandVertically(AppMotion.spec()),
            exit = fadeOut(AppMotion.spec()) + shrinkVertically(AppMotion.spec()),
        ) {
            ConnectionStatsBanner(
                activeBalancer = activeBalancer,
                pingMs = pingMs,
                sessionTraffic = sessionTraffic,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    } else {
        val showProgress = connectionState is ConnectionState.Connected ||
            (isTransitioning && showProgressDetails && connectionProgress != null)

        Card(
            modifier = modifier
                .fillMaxWidth()
                .onSizeChanged { size -> onCardHeightChange(with(density) { size.height.toDp() }) },
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConnectionStatusRow(
                        heading = connectionHeading(connectionState, geoDataDownloadFraction),
                        tone = connectionState.badgeTone(),
                        detailText = connectionDetailText(isInterfaceBusy, isRestartRequired, selectedServerName),
                        serverTextStyle = MaterialTheme.typography.titleMedium,
                        maxLines = if (isRestartRequired || isInterfaceBusy) 4 else 1,
                        serverFontWeight = FontWeight.SemiBold,
                    )

                    if (showProgress) {
                        ConnectionDetailLine(
                            showProgressDetails = showProgressDetails,
                            connectionProgress = connectionProgress,
                            connectedStartTime = (connectionState as? ConnectionState.Connected)?.startTime,
                        )
                    }

                    AnimatedVisibility(
                        visible = connectionState.showsConnectionStats() && statsExpanded,
                        enter = fadeIn(AppMotion.spec()) + expandVertically(AppMotion.spec()),
                        exit = fadeOut(AppMotion.spec()) + shrinkVertically(AppMotion.spec()),
                    ) {
                        ConnectionStatsContent(
                            activeBalancer = activeBalancer,
                            pingMs = pingMs,
                            sessionTraffic = sessionTraffic,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                ConnectionStatsDisclosure(
                    showsStats = connectionState.showsConnectionStats(),
                    expanded = statsExpanded,
                    onExpandedChange = onStatsExpandedChange,
                )
                Button(
                    onClick = onClick,
                    enabled = buttonEnabled,
                    modifier = Modifier
                        .width(120.dp)
                        .height(56.dp)
                        .combinedClickable(
                            enabled = buttonEnabled,
                            role = Role.Button,
                            onClickLabel = actionLabel,
                            onClick = onClick,
                            onLongClick = {
                                if (isConnected) {
                                    onViewConfig()
                                }
                            },
                        ),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = buttonContentColor,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (isTransitioning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = buttonContentColor,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = connectionActionIcon(
                                    isConnected = isConnected,
                                    isAlwaysOnVpn = isAlwaysOnVpn,
                                    isRestartRequired = isRestartRequired,
                                    isInterfaceBusy = isInterfaceBusy,
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = actionLabel,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = 10.sp,
                                    maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
                                    stepSize = 1.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ConnectionState.badgeTone(): HomeStateBadgeTone = when {
    this is ConnectionState.Connected -> HomeStateBadgeTone.Primary
    this is ConnectionState.Connecting ||
        this == ConnectionState.ApplyingRoutingChanges ||
        this == ConnectionState.UpdatingRoutingData ||
        this is ConnectionState.Disconnecting -> HomeStateBadgeTone.Tertiary
    this is ConnectionState.Error ||
        this is ConnectionState.RestartRequired ||
        this is ConnectionState.InterfaceBusy -> HomeStateBadgeTone.Error
    else -> HomeStateBadgeTone.Neutral
}

/** Badge with the connection heading plus the server or state detail line, shared by both layouts. */
@Composable
private fun ConnectionStatusRow(
    heading: String,
    tone: HomeStateBadgeTone,
    detailText: String,
    serverTextStyle: TextStyle,
    maxLines: Int,
    serverFontWeight: FontWeight? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeStateBadge(text = heading, tone = tone)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = detailText,
            modifier = Modifier.weight(1f),
            style = serverTextStyle,
            fontWeight = serverFontWeight,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun connectionDetailText(
    isInterfaceBusy: Boolean,
    isRestartRequired: Boolean,
    selectedServerName: String,
): String = when {
    isInterfaceBusy -> stringResource(R.string.home_connection_interface_busy_detail)
    isRestartRequired -> stringResource(R.string.home_connection_restart_required_detail)
    else -> selectedServerName
}

@Composable
private fun connectionHeading(connectionState: ConnectionState, geoDataDownloadFraction: Float?): String = when (connectionState) {
    is ConnectionState.Connected -> stringResource(R.string.home_connection_connected)
    is ConnectionState.Connecting -> stringResource(R.string.home_connection_connecting)
    ConnectionState.ApplyingRoutingChanges -> stringResource(R.string.home_connection_applying_routing)
    ConnectionState.UpdatingRoutingData -> geoDataDownloadFraction?.let { fraction ->
        stringResource(
            R.string.home_connection_updating_routing_percent,
            (fraction * 100).roundToInt(),
        )
    } ?: stringResource(R.string.home_connection_updating_routing)
    is ConnectionState.RestartRequired -> stringResource(R.string.home_connection_restart_required)
    is ConnectionState.InterfaceBusy -> stringResource(R.string.home_connection_interface_busy)
    is ConnectionState.Disconnecting -> stringResource(R.string.home_connection_disconnecting)
    is ConnectionState.Error -> stringResource(R.string.home_connection_error)
    ConnectionState.Disconnected -> stringResource(R.string.home_connection_disconnected)
}

internal fun ConnectionState.showsConnectionStats(): Boolean = this is ConnectionState.Connected ||
    this == ConnectionState.ApplyingRoutingChanges

/**
 * Compact alternative to the large connection button, anchored in the corner of the home screen.
 * Built on a plain [Surface] instead of [FloatingActionButton]: the FAB carries its own internal
 * click handling, which sits inner in the pointer-dispatch order and swallows the tap before
 * [combinedClickable] can fire it, leaving the button unresponsive. Shape and shadow elevation
 * below keep the standard FAB look.
 */
@Composable
internal fun ConnectionFab(
    visible: Boolean,
    state: ConnectionUiState,
    canStart: Boolean,
    onClick: () -> Unit,
    onViewConfig: () -> Unit,
) {
    if (!visible) return
    val enabled = (canStart || state.isConnected || state.isRestartRequired || state.isInterfaceBusy) &&
        !state.isTransitioning
    val actionLabel = stringResource(
        connectionActionLabel(
            isConnected = state.isConnected,
            isAlwaysOnVpn = state.isAlwaysOnVpn,
            isRestartRequired = state.isRestartRequired,
            isInterfaceBusy = state.isInterfaceBusy,
        ),
    )

    Surface(
        modifier = Modifier
            .size(56.dp)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = actionLabel,
                onClick = onClick,
                onLongClick = {
                    if (state.isConnected) {
                        onViewConfig()
                    }
                },
            ),
        shape = MaterialTheme.shapes.large,
        color = if (enabled) {
            state.buttonColor
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (enabled) {
            state.fabContentColor
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (state.isTransitioning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = state.fabContentColor,
                )
            } else {
                Icon(
                    imageVector = connectionActionIcon(
                        isConnected = state.isConnected,
                        isAlwaysOnVpn = state.isAlwaysOnVpn,
                        isRestartRequired = state.isRestartRequired,
                        isInterfaceBusy = state.isInterfaceBusy,
                    ),
                    contentDescription = actionLabel,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun connectionProgressText(progress: ConnectionProgress): String = when (progress) {
    ConnectionProgress.PreparingRuntime -> stringResource(R.string.home_connection_progress_preparing_runtime)
    ConnectionProgress.PreparingCore -> stringResource(R.string.home_connection_progress_preparing_core)
    ConnectionProgress.UpdatingRoutingData -> stringResource(R.string.home_connection_progress_updating_routing_data)
    ConnectionProgress.ResolvingEntryServer -> stringResource(R.string.home_connection_progress_resolving_entry_server)
    ConnectionProgress.GeneratingConfiguration -> stringResource(R.string.home_connection_progress_generating_configuration)
    ConnectionProgress.StartingCore -> stringResource(R.string.home_connection_progress_starting_core)
    ConnectionProgress.ConfiguringTunnel -> stringResource(R.string.home_connection_progress_configuring_tunnel)
    ConnectionProgress.ConfiguringRouting -> stringResource(R.string.home_connection_progress_configuring_routing)
    ConnectionProgress.WaitingForCore -> stringResource(R.string.home_connection_progress_waiting_for_core)
    ConnectionProgress.StoppingCore -> stringResource(R.string.home_connection_progress_stopping_core)
    ConnectionProgress.CleaningRuntime -> stringResource(R.string.home_connection_progress_cleaning_runtime)
    ConnectionProgress.InspectingSavedRuntime -> stringResource(R.string.home_connection_progress_inspecting_saved_runtime)
    ConnectionProgress.VerifyingRuntime -> stringResource(R.string.home_connection_progress_verifying_runtime)
    ConnectionProgress.RestoringControlApi -> stringResource(R.string.home_connection_progress_restoring_control_api)
    ConnectionProgress.UpdatingNetworkRoute -> stringResource(R.string.home_connection_progress_updating_network_route)
    ConnectionProgress.UpdatingAppRouting -> stringResource(R.string.home_connection_progress_updating_app_routing)
}

@Composable
private fun CoreUptime(startTime: Long) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTime by remember(startTime) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startTime, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                currentTime = System.currentTimeMillis()
                delay(CORE_UPTIME_REFRESH_INTERVAL_MS)
            }
        }
    }

    Text(
        text = stringResource(R.string.home_connection_uptime, formatCoreUptime(currentTime - startTime)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

internal fun formatCoreUptime(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis.coerceAtLeast(0L) / 1_000L
    val days = totalSeconds / 86_400L
    val hours = totalSeconds % 86_400L / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        days > 0L -> String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", days, hours, minutes, seconds)
        hours > 0L -> String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        else -> String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

internal fun ConnectionUiState.handleClick(
    context: Context,
    useRootService: Boolean,
    disconnect: () -> Unit,
    connectRoot: () -> Unit,
    connectVpn: () -> Unit,
) {
    when {
        isConnected && isAlwaysOnVpn -> context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        isConnected -> disconnect()
        !isTransitioning && useRootService -> connectRoot()
        !isTransitioning -> connectVpn()
    }
}

@StringRes
private fun connectionActionLabel(
    isConnected: Boolean,
    isAlwaysOnVpn: Boolean,
    isRestartRequired: Boolean,
    isInterfaceBusy: Boolean,
): Int = when {
    isConnected && isAlwaysOnVpn -> R.string.home_action_always_on
    isConnected -> R.string.home_action_stop
    isRestartRequired || isInterfaceBusy -> R.string.home_action_restart
    else -> R.string.home_action_start
}

/** Icon counterpart to [connectionActionLabel], for the compact button that has no room for text. */
private fun connectionActionIcon(
    isConnected: Boolean,
    isAlwaysOnVpn: Boolean,
    isRestartRequired: Boolean,
    isInterfaceBusy: Boolean,
): ImageVector = when {
    isConnected && isAlwaysOnVpn -> Icons.Default.Settings
    isConnected -> Icons.Default.Stop
    isRestartRequired || isInterfaceBusy -> Icons.Default.RestartAlt
    else -> Icons.Default.PlayArrow
}

private const val CORE_UPTIME_REFRESH_INTERVAL_MS = 1_000L
