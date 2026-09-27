package com.material.xray.ui.home

import androidx.compose.animation.animateBounds
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                                color = MaterialTheme.colorScheme.outlineVariant,
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
    val latencyText = when {
        latency == null -> null
        latency.latencyMs == LATENCY_TESTING -> stringResource(R.string.home_latency_testing)
        latency.tcpingLatencyMs != null && latency.httpingLatencyMs != null -> {
            val tcping = latencyCompactText(latency.tcpingLatencyMs)
            val httping = latencyCompactText(latency.httpingLatencyMs)
            "$tcping · $httping"
        }
        else -> if (latency.latencyMs < 0) {
            stringResource(R.string.home_latency_not_available)
        } else {
            stringResource(R.string.home_latency_milliseconds, latency.latencyMs)
        }
    }
    val latencyDescription = if (
        latency?.tcpingLatencyMs != null && latency.httpingLatencyMs != null
    ) {
        val tcping = latencyAccessibleText(latency.tcpingLatencyMs)
        val httping = latencyAccessibleText(latency.httpingLatencyMs)
        "${stringResource(PingMethod.Tcping.labelResource)}: $tcping; " +
            "${stringResource(PingMethod.Httping.labelResource)}: $httping"
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onTestLatency),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        // IntrinsicSize.Min gives the row a height the chevron can fill, so its tap target and
        // ripple cover the whole strip at the row's end instead of a small indicator inside it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
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
                            HomeStateBadge(
                                text = stringResource(R.string.home_server_edited),
                                leadingIcon = Icons.Outlined.Edit,
                                showText = false,
                            )
                        }
                        if (server.entity.guarded) {
                            HomeStateBadge(
                                text = stringResource(R.string.home_server_guarded),
                                leadingIcon = Icons.Outlined.Shield,
                                showText = false,
                            )
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
                if (latencyText != null) {
                    HomeStateBadge(
                        text = latencyText,
                        tone = if (latency != null && latencyShowsError(latency)) {
                            HomeStateBadgeTone.Error
                        } else {
                            HomeStateBadgeTone.Neutral
                        },
                        isLoading = latency?.latencyMs == LATENCY_TESTING,
                        contentDescription = latencyDescription,
                    )
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

internal fun latencyShowsError(latency: ServerLatencyState): Boolean {
    val httpingLatencyMs = latency.httpingLatencyMs
    if (latency.latencyMs == LATENCY_TESTING) return false
    return if (latency.tcpingLatencyMs != null && httpingLatencyMs != null) {
        httpingLatencyMs < 0
    } else {
        latency.latencyMs < 0
    }
}

@Composable
private fun latencyCompactText(latencyMs: Int): String = if (latencyMs < 0) {
    stringResource(R.string.home_latency_not_available)
} else {
    stringResource(R.string.home_latency_milliseconds_compact, latencyMs)
}

@Composable
private fun latencyAccessibleText(latencyMs: Int): String = if (latencyMs < 0) {
    stringResource(R.string.home_latency_not_available)
} else {
    stringResource(R.string.home_latency_milliseconds, latencyMs)
}

internal enum class HomeStateBadgeTone {
    Neutral,
    Primary,
    Tertiary,
    Error,
}

@Composable
internal fun HomeStateBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: HomeStateBadgeTone = HomeStateBadgeTone.Neutral,
    leadingIcon: ImageVector? = null,
    isLoading: Boolean = false,
    showText: Boolean = true,
    contentDescription: String? = null,
) {
    val containerColor = when (tone) {
        HomeStateBadgeTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
        HomeStateBadgeTone.Primary -> MaterialTheme.colorScheme.primaryContainer
        HomeStateBadgeTone.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer
        HomeStateBadgeTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (tone) {
        HomeStateBadgeTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        HomeStateBadgeTone.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
        HomeStateBadgeTone.Tertiary -> MaterialTheme.colorScheme.onTertiaryContainer
        HomeStateBadgeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier
            .heightIn(min = 24.dp)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (showText) 8.dp else 4.dp,
                vertical = 4.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = if (showText) null else text,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
            }
            if (showText) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private object ServerRowDefaults {
    // No end padding: the chevron's own strip supplies the row's end inset.
    val contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 0.dp, bottom = 8.dp)

    val chevronHorizontalPadding = 8.dp
    val chevronIconSize = 20.dp
}

@Composable
private fun CompactSelectionDot(isSelected: Boolean) {
    Icon(
        imageVector = if (isSelected) {
            Icons.Filled.RadioButtonChecked
        } else {
            Icons.Outlined.RadioButtonUnchecked
        },
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        },
    )
}
