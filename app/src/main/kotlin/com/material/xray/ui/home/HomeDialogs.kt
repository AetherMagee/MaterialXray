@file:Suppress("TooManyFunctions")

package com.material.xray.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.material.xray.R
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.PingMethod
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.ui.components.SelectableOptionRow
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource

@Composable
internal fun HwidRequiredDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.home_hwid_required_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = { Text(stringResource(R.string.home_hwid_required_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.home_hwid_required_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_hwid_required_cancel))
            }
        },
    )
}

@Composable
internal fun CameraPermissionDialogHost(
    requestedAccess: CameraPermissionAccess?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val access = requestedAccess ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_camera_permission_title)) },
        text = { Text(stringResource(R.string.home_camera_permission_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (access == CameraPermissionAccess.SystemSettings) {
                            R.string.home_open_app_settings
                        } else {
                            R.string.home_allow_camera
                        },
                    ),
                )
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
internal fun DiscardEditedActiveConfigDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_discard_edited_config_title)) },
        text = { Text(stringResource(R.string.home_discard_edited_config_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.home_discard_edited_config_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_discard_edited_config_cancel))
            }
        },
    )
}

@Composable
internal fun AddSubscriptionDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, Boolean, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    if (!visible) return

    AddSubscriptionDialog(
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun QrScannerDialogHost(
    keepDialog: Boolean,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onLinkScanned: (String) -> Unit,
) {
    if (!keepDialog) return

    Dialog(
        onDismissRequest = { onVisibleChange(false) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(durationMillis = QR_SCANNER_TRANSITION_MS)),
            exit = fadeOut(animationSpec = tween(durationMillis = QR_SCANNER_TRANSITION_MS)),
        ) {
            QrScannerOverlay(
                onQrCodeScanned = { link ->
                    onVisibleChange(false)
                    onLinkScanned(link)
                },
                onClose = { onVisibleChange(false) },
            )
        }
    }
}

@Composable
internal fun ApplySubscriptionRoutingDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    ApplySubscriptionRoutingDialog(
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun RootFallbackDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.home_root_fallback_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.home_action_continue))
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
internal fun RemoveSubscriptionDialogHost(
    request: Pair<SubscriptionEntity, Int>?,
    onDismiss: () -> Unit,
    onConfirm: (SubscriptionEntity) -> Unit,
) {
    val (subscription, serverCount) = request ?: return

    RemoveSubscriptionDialog(
        serverCount = serverCount,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(subscription) },
    )
}

@Composable
internal fun ReorderSubscriptionsDialogHost(
    visible: Boolean,
    subscriptions: List<SubscriptionEntity>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    if (!visible || subscriptions.size < 2) return

    ReorderSubscriptionsDialog(
        subscriptions = subscriptions,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
internal fun EditSubscriptionDialogHost(
    subscription: SubscriptionEntity?,
    onDismiss: () -> Unit,
    onConfirm: (SubscriptionEntity, String, String, Boolean, Boolean, Int, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    subscription ?: return

    EditSubscriptionDialog(
        subscription = subscription,
        onDismiss = onDismiss,
        onConfirm = { name, url, preferJson, allowInsecureUpdates, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders ->
            onConfirm(
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
        },
    )
}

@Composable
internal fun InstallPermissionRationaleDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_app_update_permission_title)) },
        text = { Text(stringResource(R.string.home_app_update_permission_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.home_app_update_permission_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_app_update_permission_not_now))
            }
        },
    )
}

@Composable
internal fun PingMethodDialogHost(
    visible: Boolean,
    selectedMethod: PingMethod,
    onDismiss: () -> Unit,
    onSelected: (PingMethod) -> Unit,
) {
    if (!visible) return

    PingMethodDialog(
        selectedMethod = selectedMethod,
        onDismiss = onDismiss,
        onSelected = onSelected,
    )
}

@Composable
private fun PingMethodDialog(
    selectedMethod: PingMethod,
    onDismiss: () -> Unit,
    onSelected: (PingMethod) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_close))
            }
        },
        title = { Text(stringResource(R.string.home_choose_ping_method_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PingMethod.entries.forEach { method ->
                    SelectableOptionRow(
                        title = stringResource(method.labelResource),
                        description = stringResource(method.descriptionResource),
                        selected = method == selectedMethod,
                        onSelected = { onSelected(method) },
                    )
                }
            }
        },
    )
}

@Composable
internal fun SubscriptionDescriptionDialogHost(
    url: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val pendingUrl = url ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_open_link_title)) },
        text = {
            SelectionContainer {
                Text(
                    text = pendingUrl,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pendingUrl) }) {
                Text(stringResource(R.string.home_action_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}
