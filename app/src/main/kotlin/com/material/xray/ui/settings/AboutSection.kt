package com.material.xray.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.model.AppUpdateCheckStatus
import com.material.xray.model.isInProgress
import com.material.xray.ui.components.SettingsSwitchRow

@Composable
internal fun appUpdateCheckDescription(status: AppUpdateCheckStatus): String = when (status) {
    AppUpdateCheckStatus.Starting ->
        stringResource(R.string.settings_update_check_starting)
    is AppUpdateCheckStatus.Fetching ->
        stringResource(R.string.settings_update_check_fetching, status.url)
    is AppUpdateCheckStatus.RetryingAfterHttpError -> stringResource(
        R.string.settings_update_check_retry_http,
        status.url,
        status.statusCode,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.RetryingAfterConnectionFailure -> stringResource(
        R.string.settings_update_check_retry_connection,
        status.url,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.RetryingAfterInvalidResponse -> stringResource(
        R.string.settings_update_check_retry_invalid_response,
        status.url,
        status.statusCode,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.ReleaseReceived -> stringResource(
        R.string.settings_update_check_comparing,
        status.url,
        status.statusCode,
    )
    AppUpdateCheckStatus.UpToDate ->
        stringResource(R.string.settings_update_check_up_to_date)
    is AppUpdateCheckStatus.UpdateAvailable -> stringResource(
        R.string.settings_update_check_available,
        status.version,
    )
    AppUpdateCheckStatus.Failed ->
        stringResource(R.string.settings_update_check_failed)
}

fun LazyListScope.aboutSection(
    appUpdateChecksEnabled: Boolean,
    appUpdateCheckStatus: AppUpdateCheckStatus?,
    appUpdateCheckDescription: String?,
    appVersionText: String,
    xrayCoreVersionText: String,
    onAppUpdateChecksEnabledChange: (Boolean) -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenLicenses: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    item(key = "about_header") {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_section_about),
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            showDivider = true,
        )
    }
    if (!expanded) return

    item(key = "about_update_checks") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_app_update_checks_title),
            description = stringResource(R.string.settings_app_update_checks_description),
            checked = appUpdateChecksEnabled,
            onCheckedChange = onAppUpdateChecksEnabledChange,
        )
    }

    item(key = "about_check_updates") {
        SettingsActionRow(
            title = stringResource(R.string.settings_check_for_updates),
            subtitle = appUpdateCheckDescription,
            enabled = appUpdateCheckStatus?.isInProgress != true,
            inProgress = appUpdateCheckStatus?.isInProgress == true,
            onClick = onCheckForUpdates,
        )
    }

    item(key = "about_licenses") {
        SettingsActionRow(
            title = stringResource(R.string.settings_open_source_licenses),
            subtitle = stringResource(R.string.settings_open_source_licenses_description),
            onClick = onOpenLicenses,
        )
    }

    item(key = "about_app_version") {
        Text(
            text = appVersionText,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    item(key = "about_xray_version") {
        Text(
            text = xrayCoreVersionText,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
