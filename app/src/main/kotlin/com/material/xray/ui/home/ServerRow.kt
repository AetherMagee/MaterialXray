package com.material.xray.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.material.xray.R
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.model.PingMethod
import com.material.xray.ui.text.labelResource

@Composable
internal fun AnimatedServerRows(
    servers: List<ServerListItem>,
    selectedServerId: Long,
    onServerSelected: (Long) -> Unit,
    onTestLatency: (ServerEntity) -> Unit,
    onOpenServerConfig: (Long, String) -> Unit,
) {
    LookaheadScope {
        Column {
            servers.forEachIndexed { index, server ->
                key(server.entity.id) {
                    Column(modifier = Modifier.animateBounds(this@LookaheadScope)) {
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            )
                        }
                        ServerRow(
                            server = server,
                            isSelected = server.entity.id == selectedServerId,
                            onClick = { onServerSelected(server.entity.id) },
                            onTestLatency = { onTestLatency(server.entity) },
                            onOpenConfig = {
                                onOpenServerConfig(server.entity.id, server.entity.name)
                            },
                            contentPadding = ServerRowDefaults.contentPadding,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServerRow(
    server: ServerListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTestLatency: () -> Unit,
    onOpenConfig: () -> Unit,
    contentPadding: PaddingValues = ServerRowDefaults.contentPadding,
) {
    val latency = server.latency
    val latencyColor = if (latency?.let(::latencyShowsError) == true) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onTestLatency),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        // IntrinsicSize.Min gives the row a height the chevron can fill, so its tap target and
        // ripple cover the whole strip at the row's end instead of a small circle inside it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactSelectionDot(isSelected = isSelected)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = server.entity.name,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (server.entity.edited) {
                            ServerStateBadge(Icons.Outlined.Edit, R.string.home_server_edited)
                        }
                        if (server.entity.guarded) {
                            ServerStateBadge(Icons.Outlined.Shield, R.string.home_server_guarded)
                        }
                    }
                    Text(
                        text = server.endpointSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (latency != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        LatencyBadgeContent(
                            latency = latency,
                            color = latencyColor,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable(
                        onClick = onOpenConfig,
                        onClickLabel = stringResource(R.string.config_viewer_open),
                    )
                    .padding(horizontal = ServerRowDefaults.chevronHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.config_viewer_open),
                    modifier = Modifier.size(ServerRowDefaults.chevronIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ServerStateBadge(icon: ImageVector, @StringRes descriptionRes: Int) {
    Icon(
        imageVector = icon,
        contentDescription = stringResource(descriptionRes),
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LatencyBadgeContent(
    latency: ServerLatencyState,
    color: Color,
) {
    val tcpingLatencyMs = latency.tcpingLatencyMs
    val httpingLatencyMs = latency.httpingLatencyMs
    if (latency.latencyMs == LATENCY_TESTING) {
        ShimmeringText(
            text = stringResource(R.string.home_latency_testing),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    } else if (tcpingLatencyMs != null && httpingLatencyMs != null) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LatencyValue(tcpingLatencyMs, PingMethod.Tcping, Icons.Outlined.NetworkPing, color)
            Text(
                text = ",",
                color = color,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = (-0.25).sp),
            )
            LatencyValue(httpingLatencyMs, PingMethod.Httping, Icons.Outlined.Dns, color)
        }
    } else {
        Text(
            text = if (latency.latencyMs < 0) {
                stringResource(R.string.home_latency_not_available)
            } else {
                stringResource(R.string.home_latency_milliseconds, latency.latencyMs)
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ShimmeringText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "latency-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = LATENCY_SHIMMER_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "latency-shimmer-progress",
    )
    val shimmerWidth = with(LocalDensity.current) { 32.dp.toPx() }
    val travelDistance = with(LocalDensity.current) { 120.dp.toPx() }
    val startX = -shimmerWidth + progress * (travelDistance + shimmerWidth)
    val brush = Brush.linearGradient(
        colors = listOf(color.copy(alpha = 0.45f), color, color.copy(alpha = 0.45f)),
        start = Offset(startX, 0f),
        end = Offset(startX + shimmerWidth, 0f),
    )

    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = brush),
    )
}

@Composable
private fun LatencyValue(
    latencyMs: Int,
    method: PingMethod?,
    icon: ImageVector,
    color: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (latencyMs < 0) {
                stringResource(R.string.home_latency_not_available)
            } else {
                stringResource(R.string.home_latency_milliseconds_compact, latencyMs)
            },
            color = color,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = (-0.25).sp),
        )
        Icon(
            imageVector = icon,
            contentDescription = method?.let { stringResource(it.labelResource) },
            modifier = Modifier.size(13.dp),
            tint = color,
        )
    }
}

internal fun latencyShowsError(latency: ServerLatencyState): Boolean {
    val httpingLatencyMs = latency.httpingLatencyMs
    if (latency.latencyMs == LATENCY_TESTING) return false
    return if (latency.tcpingLatencyMs != null && httpingLatencyMs != null) {
        httpingLatencyMs < 0
    } else {
        latency.latencyMs < 0
    }
}

private object ServerRowDefaults {
    // No end padding: the chevron's own strip supplies the row's end inset.
    val contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 0.dp, bottom = 10.dp)

    val chevronHorizontalPadding = 7.dp
    val chevronIconSize = 20.dp
}

private const val LATENCY_SHIMMER_DURATION_MS = 850

@Composable
private fun CompactSelectionDot(isSelected: Boolean) {
    Surface(
        modifier = Modifier.size(18.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 2.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
