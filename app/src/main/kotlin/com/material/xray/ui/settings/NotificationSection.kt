package com.material.xray.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.material.xray.R
import com.material.xray.model.NotificationSettings
import com.material.xray.ui.text.labelResource

@Suppress("LongParameterList")
internal fun LazyListScope.notificationSection(
    settings: NotificationSettings,
    access: NotificationAccess,
    onRequestAccess: () -> Unit,
    onConfigureFields: () -> Unit,
    onConfigureStyle: () -> Unit,
    onConfigureFrequency: () -> Unit,
) {
    item(key = "notification_header") {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_notification_title),
            showDivider = true,
        )
    }

    if (access != NotificationAccess.Available) {
        item(key = "notification_permission") {
            SettingsActionRow(
                title = stringResource(R.string.settings_notification_permission_unavailable),
                subtitle = stringResource(R.string.settings_notification_permission_unavailable_description),
                onClick = onRequestAccess,
            )
        }
    }

    item(key = "notification_fields") {
        SettingsActionRow(
            title = stringResource(R.string.settings_configure_notification_fields),
            subtitle = notificationFieldSummary(settings),
            onClick = onConfigureFields,
        )
    }

    item(key = "notification_field_style") {
        SettingsActionRow(
            title = stringResource(R.string.settings_notification_field_style),
            subtitle = stringResource(settings.style.labelResource),
            onClick = onConfigureStyle,
        )
    }

    item(key = "notification_update_frequency") {
        SettingsActionRow(
            title = stringResource(R.string.settings_notification_update_frequency),
            subtitle = pluralStringResource(
                R.plurals.settings_notification_update_frequency_summary,
                settings.updateIntervalMs,
                settings.updateIntervalMs,
            ),
            onClick = onConfigureFrequency,
        )
    }
}

@Composable
internal fun NotificationPermissionDialog(
    access: NotificationAccess,
    onDismiss: () -> Unit,
    onAllow: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_permission_title)) },
        text = { Text(stringResource(R.string.settings_notification_permission_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onAllow()
                },
            ) {
                Text(
                    stringResource(
                        if (access == NotificationAccess.SystemSettings) {
                            R.string.settings_open_notification_settings
                        } else {
                            R.string.settings_allow_notifications
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

internal fun notificationAccess(context: Context): NotificationAccess {
    val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val permissionGranted = !permissionRequired ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val activity = context as? Activity
    return resolveNotificationAccess(
        permissionRequired = permissionRequired,
        permissionGranted = permissionGranted,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        shouldShowRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS),
        permissionRequested = context.wasNotificationPermissionRequested(),
    )
}

internal fun resolveNotificationAccess(
    permissionRequired: Boolean,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
    shouldShowRationale: Boolean,
    permissionRequested: Boolean,
): NotificationAccess = when {
    !permissionRequired || permissionGranted -> {
        if (notificationsEnabled) NotificationAccess.Available else NotificationAccess.SystemSettings
    }
    shouldShowRationale -> NotificationAccess.Rationale
    permissionRequested -> NotificationAccess.SystemSettings
    else -> NotificationAccess.Requestable
}

internal fun Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
}

internal fun Context.recordNotificationPermissionRequest() {
    getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
        .apply()
}

internal fun Context.wasNotificationPermissionRequested(): Boolean = getSharedPreferences(
    NOTIFICATION_PERMISSION_PREFS,
    Context.MODE_PRIVATE,
).getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)

internal enum class NotificationAccess {
    Available,
    Requestable,
    Rationale,
    SystemSettings,
}

@Composable
internal fun notificationFieldSummary(settings: NotificationSettings): String {
    val enabledFields = settings.normalizedFieldOrder()
        .filter(settings::isFieldEnabled)
        .map { stringResource(it.labelResource) }
    return if (enabledFields.isEmpty()) {
        stringResource(R.string.settings_no_custom_notification_fields)
    } else {
        enabledFields.joinToString(stringResource(R.string.settings_notification_field_separator))
    }
}

private const val NOTIFICATION_PERMISSION_PREFS = "notification_permission"
private const val NOTIFICATION_PERMISSION_REQUESTED = "requested"
