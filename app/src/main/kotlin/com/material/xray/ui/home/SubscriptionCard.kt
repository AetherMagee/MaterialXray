package com.material.xray.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.PingMethod
import com.material.xray.ui.components.AppMotion

@Composable
internal fun SubscriptionCard(
    subscription: SubscriptionEntity,
    isRefreshing: Boolean,
    servers: List<ServerListItem>,
    selectedServerId: Long,
    defaultPingMethod: PingMethod,
    canApplyRouting: Boolean,
    canCollapse: Boolean,
    expanded: Boolean,
    canReorder: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onReorder: () -> Unit,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onPingMethodRequested: () -> Unit,
    onDescriptionUrlClick: (String) -> Unit,
    onApplyRouting: () -> Unit,
    onDescriptionHiddenChange: (Boolean) -> Unit,
    onServerSelected: (Long) -> Unit,
    onTestLatency: (ServerEntity) -> Unit,
    onOpenServerConfig: (Long, String) -> Unit,
) {
    val resources = LocalResources.current
    val locale = resources.configuration.locales[0]
    val metadata = remember(
        subscription.announce,
        subscription.subscriptionUploadBytes,
        subscription.subscriptionDownloadBytes,
        subscription.subscriptionTotalBytes,
        subscription.subscriptionExpireAt,
        subscription.autoUpdateIntervalHours,
        locale,
    ) {
        buildSubscriptionMetadataUiState(subscription, resources)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SubscriptionHeader(
                subscription = subscription,
                isRefreshing = isRefreshing,
                metadata = metadata,
                defaultPingMethod = defaultPingMethod,
                canCollapse = canCollapse,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                onRefresh = onRefresh,
                onTestAll = onTestAll,
                onPingMethodRequested = onPingMethodRequested,
                onDelete = onDelete,
                onEdit = onEdit,
                canReorder = canReorder,
                onReorder = onReorder,
                canApplyRouting = canApplyRouting,
                onApplyRouting = onApplyRouting,
                onDescriptionHiddenChange = onDescriptionHiddenChange,
            )
            AnimatedVisibility(visible = !canCollapse || expanded) {
                Column {
                    if (metadata.hasVisibleSubscriptionSection()) {
                        Spacer(modifier = Modifier.height(SubscriptionBlockGap))
                    }
                    SubscriptionMetadataSection(
                        subscription = subscription,
                        metadata = metadata,
                        onDescriptionUrlClick = onDescriptionUrlClick,
                    )

                    if (servers.isEmpty()) {
                        Text(
                            stringResource(R.string.home_no_servers_in_subscription),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        AnimatedServerRows(
                            servers = servers,
                            selectedServerId = selectedServerId,
                            onServerSelected = onServerSelected,
                            onTestLatency = onTestLatency,
                            onOpenServerConfig = onOpenServerConfig,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionMetadataSection(
    subscription: SubscriptionEntity,
    metadata: SubscriptionMetadataUiState,
    onDescriptionUrlClick: (String) -> Unit,
) {
    val limitedTraffic = metadata.traffic?.takeUnless { it.quotaText == null }

    val hasVisibleMetadata = metadata.announcement.isNotEmpty() ||
        limitedTraffic != null
    if (!hasVisibleMetadata) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = AppMotion.spec())
            .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = SubscriptionMetadataGap),
        verticalArrangement = Arrangement.spacedBy(SubscriptionMetadataGap),
    ) {
        AnimatedVisibility(
            visible = metadata.announcement.isNotEmpty() && !subscription.descriptionHidden,
            enter = fadeIn(animationSpec = AppMotion.spec()),
            exit = fadeOut(animationSpec = AppMotion.spec()),
        ) {
            SubscriptionDescriptionText(
                description = metadata.announcement,
                onUrlClick = onDescriptionUrlClick,
            )
        }

        if (limitedTraffic != null) {
            SubscriptionTrafficUsage(
                state = limitedTraffic,
                expiry = metadata.expiry,
            )
        }
    }
}

private fun SubscriptionMetadataUiState.hasVisibleSubscriptionSection(): Boolean {
    val limitedTraffic = traffic?.takeUnless { it.quotaText == null }
    return announcement.isNotEmpty() ||
        limitedTraffic != null
}

@Composable
private fun SubscriptionTrafficUsage(
    state: SubscriptionTrafficUiState,
    expiry: SubscriptionExpiryUiState?,
) {
    val expiredStatusText = stringResource(R.string.home_subscription_expired_inline)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = state.summary,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        if (expiry != null) {
            Text(
                text = remember(expiry.standaloneText, expiredStatusText) {
                    expiry.standaloneText.withMetadataEmphasis(expiredStatusText)
                },
                modifier = Modifier.align(Alignment.Start),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubscriptionHeader(
    subscription: SubscriptionEntity,
    isRefreshing: Boolean,
    metadata: SubscriptionMetadataUiState,
    defaultPingMethod: PingMethod,
    canCollapse: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onPingMethodRequested: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    canReorder: Boolean,
    onReorder: () -> Unit,
    canApplyRouting: Boolean,
    onApplyRouting: () -> Unit,
    onDescriptionHiddenChange: (Boolean) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val supportUrl = subscription.supportUrl?.trim().orEmpty()
    val hasDescription = subscription.announce?.trim()?.isNotEmpty() == true
    val headerDetailText = metadata.headerDetailText(resources)
    val expiredStatusText = stringResource(R.string.home_subscription_expired_inline)
    val expansionActionDescription = if (canCollapse) {
        stringResource(
            if (expanded) R.string.home_subscription_collapse else R.string.home_subscription_expand,
            subscription.name,
        )
    } else {
        null
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = AppMotion.spec(),
        label = "subscription-chevron-rotation",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (canCollapse) 0.dp else 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canCollapse) {
            IconButton(onClick = { onExpandedChange(!expanded) }) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = expansionActionDescription,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }
        }
        val titleInteractionSource = remember { MutableInteractionSource() }
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .then(
                    if (canCollapse) {
                        Modifier.clickable(
                            interactionSource = titleInteractionSource,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = expansionActionDescription,
                        ) { onExpandedChange(!expanded) }
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = subscription.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!headerDetailText.isNullOrBlank()) {
                Text(
                    text = remember(headerDetailText, expiredStatusText) {
                        headerDetailText.withMetadataEmphasis(expiredStatusText)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRefresh, enabled = !isRefreshing) {
            if (isRefreshing) {
                val updatingDescription = stringResource(
                    R.string.home_subscription_updating_content_description,
                    subscription.name,
                )
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = updatingDescription },
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(
                        R.string.home_subscription_refresh_content_description,
                        subscription.name,
                    ),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onTestAll,
                    onLongClick = onPingMethodRequested,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Speed,
                contentDescription = stringResource(
                    R.string.home_subscription_test_content_description,
                    subscription.name,
                    defaultPingMethod.value,
                ),
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.home_subscription_menu_content_description),
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_choose_ping_method_title)) },
                    leadingIcon = {
                        Icon(Icons.Default.Speed, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onPingMethodRequested()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_action_edit)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.edit_24px), contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onEdit()
                    },
                )
                if (canReorder) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_action_reorder)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.SwapVert, contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onReorder()
                        },
                    )
                }
                if (supportUrl.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_action_support)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.support_24px), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            uriHandler.openUri(supportUrl)
                        },
                    )
                }
                if (hasDescription) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (subscription.descriptionHidden) {
                                        R.string.home_subscription_show_description
                                    } else {
                                        R.string.home_subscription_hide_description
                                    },
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painterResource(
                                    if (subscription.descriptionHidden) {
                                        R.drawable.visibility_24px
                                    } else {
                                        R.drawable.visibility_off_24px
                                    },
                                ),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDescriptionHiddenChange(!subscription.descriptionHidden)
                        },
                    )
                }
                if (canApplyRouting) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_subscription_apply_routing)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.cloud_download_24px), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onApplyRouting()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_action_remove)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.delete_forever_24px), contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionDescriptionText(
    description: String,
    onUrlClick: (String) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotatedDescription = remember(description, linkColor, onUrlClick) {
        description.withUrlLinks(linkColor, onUrlClick)
    }

    SelectionContainer {
        Text(
            text = annotatedDescription,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }
}

private fun String.withMetadataEmphasis(expiredStatusText: String) = buildAnnotatedString {
    metadataTextSegments(this@withMetadataEmphasis, expiredStatusText).forEach { segment ->
        if (segment.emphasized) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(segment.value)
            }
        } else {
            append(segment.value)
        }
    }
}

private fun String.withUrlLinks(
    linkColor: androidx.compose.ui.graphics.Color,
    onUrlClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        ),
    )

    subscriptionUrlRegex.findAll(this@withUrlLinks).forEach { match ->
        val start = match.range.first
        val end = this@withUrlLinks.trimmedUrlEnd(match)
        if (end <= start) return@forEach

        if (cursor < start) {
            append(this@withUrlLinks.substring(cursor, start))
        }

        val url = this@withUrlLinks.substring(start, end)
        val linkStart = length
        append(url)
        addLink(
            LinkAnnotation.Clickable(
                tag = url.normalizedSubscriptionUrl(),
                styles = linkStyles,
                linkInteractionListener = LinkInteractionListener { link ->
                    (link as? LinkAnnotation.Clickable)?.tag?.let(onUrlClick)
                },
            ),
            start = linkStart,
            end = length,
        )
        cursor = end
    }

    if (cursor < this@withUrlLinks.length) {
        append(this@withUrlLinks.substring(cursor))
    }
}

private fun String.trimmedUrlEnd(match: MatchResult): Int {
    var end = match.range.last + 1
    while (end > match.range.first && this[end - 1] in trailingUrlPunctuation) {
        end--
    }
    return end
}

private fun String.normalizedSubscriptionUrl(): String = if (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)) {
    this
} else {
    "https://$this"
}

private val subscriptionUrlRegex = Regex(
    pattern = """(?i)(?<![@\w])(?:https?://[^\s<>"']+|(?:www\.|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,})(?:/[^\s<>"']*)?)""",
)
private val trailingUrlPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
private val SubscriptionBlockGap = 8.dp
private val SubscriptionMetadataGap = 8.dp
