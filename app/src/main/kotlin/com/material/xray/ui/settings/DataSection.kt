package com.material.xray.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.core.xray.GeoDataAsset
import com.material.xray.core.xray.GeoDataDownloadProgress
import com.material.xray.model.ConnectionState
import com.material.xray.model.GeoDataUpdateInterval
import com.material.xray.ui.components.SettingsSwitchRow

@Composable
internal fun GeoDataCircularProgress(
    progress: GeoDataDownloadProgress?,
    description: String,
) {
    val fraction = progress?.fraction
    if (fraction == null) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(24.dp)
                .semantics { contentDescription = description },
            strokeWidth = 2.dp,
        )
    } else {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .size(24.dp)
                .semantics { contentDescription = description },
            strokeWidth = 2.dp,
        )
    }
}

@Composable
internal fun GeoDataDownloadStatus(progress: GeoDataDownloadProgress?) {
    val context = LocalContext.current
    val text = when {
        progress == null -> stringResource(R.string.settings_updating)
        progress.totalBytes != null && progress.totalBytes > 0L -> stringResource(
            R.string.settings_geodata_download_progress_with_total,
            Formatter.formatShortFileSize(context, progress.bytesDownloaded),
            Formatter.formatShortFileSize(context, progress.totalBytes),
        )
        else -> stringResource(
            R.string.settings_geodata_download_progress,
            Formatter.formatShortFileSize(context, progress.bytesDownloaded),
        )
    }
    Text(text)
}

@Suppress("LongParameterList")
fun LazyListScope.dataSection(
    showAdvancedOptions: Boolean,
    diagnosticsEnabled: Boolean,
    geoipUpdating: Boolean,
    geositeUpdating: Boolean,
    geoDataClearing: Boolean,
    geoDataOperationInProgress: Boolean,
    connectionState: ConnectionState,
    geoDataDownloadProgress: Map<GeoDataAsset, GeoDataDownloadProgress>,
    editingGeoDataUpdateIntervalHours: String,
    isGeoDataUpdateIntervalHoursValid: Boolean,
    hasGeoDataUpdateIntervalHoursChanges: Boolean,
    editingGeoipUrl: String,
    hasGeoipUrlChanges: Boolean,
    editingGeositeUrl: String,
    hasGeositeUrlChanges: Boolean,
    backupBusy: Boolean,
    databaseResetting: Boolean,
    onDiagnosticsEnabledChange: (Boolean) -> Unit,
    onEditingGeoDataUpdateIntervalHoursChange: (String) -> Unit,
    onSaveGeoDataUpdateIntervalHours: () -> Unit,
    onEditingGeoipUrlChange: (String) -> Unit,
    onSaveGeoipUrl: () -> Unit,
    onUpdateGeoipAsset: (String) -> Unit,
    onEditingGeositeUrlChange: (String) -> Unit,
    onSaveGeositeUrl: () -> Unit,
    onUpdateGeositeAsset: (String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onClearGeoData: () -> Unit,
    onResetDatabase: () -> Unit,
) {
    item(key = "data_geo_interval") {
        AdvancedIntegerSetting(
            value = editingGeoDataUpdateIntervalHours,
            onValueChange = onEditingGeoDataUpdateIntervalHoursChange,
            label = stringResource(R.string.settings_geo_data_update_interval_label),
            supportingText = stringResource(
                R.string.settings_geo_data_update_interval_supporting_text,
                GeoDataUpdateInterval.MIN_HOURS,
                GeoDataUpdateInterval.MAX_HOURS,
                GeoDataUpdateInterval.DEFAULT_HOURS,
            ),
            suffix = stringResource(R.string.settings_hours_abbreviation),
            isValid = isGeoDataUpdateIntervalHoursValid,
            hasChanges = hasGeoDataUpdateIntervalHoursChanges,
            onSave = onSaveGeoDataUpdateIntervalHours,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    item(key = "data_geoip") {
        SettingsTextFieldWithSave(
            value = editingGeoipUrl,
            onValueChange = onEditingGeoipUrlChange,
            label = stringResource(R.string.settings_geoip_url_label),
            supportingText = {
                if (geoipUpdating) {
                    GeoDataDownloadStatus(geoDataDownloadProgress[GeoDataAsset.GEOIP])
                } else {
                    Text(stringResource(R.string.settings_geoip_url_supporting_text))
                }
            },
            trailingIcon = {
                IconButton(
                    onClick = { onUpdateGeoipAsset(editingGeoipUrl) },
                    enabled = !geoDataOperationInProgress,
                ) {
                    if (geoipUpdating) {
                        val description = stringResource(R.string.settings_geoip_updating)
                        GeoDataCircularProgress(
                            progress = geoDataDownloadProgress[GeoDataAsset.GEOIP],
                            description = description,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.settings_update_geoip),
                        )
                    }
                }
            },
            hasChanges = hasGeoipUrlChanges,
            onSave = onSaveGeoipUrl,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    item(key = "data_geosite") {
        SettingsTextFieldWithSave(
            value = editingGeositeUrl,
            onValueChange = onEditingGeositeUrlChange,
            label = stringResource(R.string.settings_geosite_url_label),
            supportingText = {
                if (geositeUpdating) {
                    GeoDataDownloadStatus(geoDataDownloadProgress[GeoDataAsset.GEOSITE])
                } else {
                    Text(stringResource(R.string.settings_geosite_url_supporting_text))
                }
            },
            trailingIcon = {
                IconButton(
                    onClick = { onUpdateGeositeAsset(editingGeositeUrl) },
                    enabled = !geoDataOperationInProgress,
                ) {
                    if (geositeUpdating) {
                        val description = stringResource(R.string.settings_geosite_updating)
                        GeoDataCircularProgress(
                            progress = geoDataDownloadProgress[GeoDataAsset.GEOSITE],
                            description = description,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.settings_update_geosite),
                        )
                    }
                }
            },
            hasChanges = hasGeositeUrlChanges,
            onSave = onSaveGeositeUrl,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    item(key = "data_backup") {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = !backupBusy,
                onClick = onExportBackup,
            ) {
                Text(stringResource(R.string.settings_export))
            }
            OutlinedButton(
                enabled = !backupBusy,
                onClick = onImportBackup,
            ) {
                Text(stringResource(R.string.settings_import))
            }
        }
    }

    if (showAdvancedOptions) {
        item(key = "data_clear_geodata") {
            OutlinedButton(
                onClick = onClearGeoData,
                enabled = connectionState is ConnectionState.Disconnected && !geoDataOperationInProgress,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(
                        if (geoDataClearing) {
                            R.string.settings_clearing_geodata
                        } else {
                            R.string.settings_clear_geodata
                        },
                    ),
                )
            }
        }

        item(key = "data_reset_database") {
            SettingsActionRow(
                title = stringResource(R.string.settings_reset_internal_database),
                subtitle = stringResource(
                    if (databaseResetting) {
                        R.string.settings_resetting_internal_database
                    } else {
                        R.string.settings_reset_internal_database_description
                    },
                ),
                enabled = !databaseResetting,
                onClick = onResetDatabase,
            )
        }
    }

    item(key = "data_diagnostics") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_diagnostics_title),
            description = stringResource(R.string.settings_diagnostics_description),
            checked = diagnosticsEnabled,
            onCheckedChange = onDiagnosticsEnabledChange,
        )
    }
}
