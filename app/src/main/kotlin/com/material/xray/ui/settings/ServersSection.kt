package com.material.xray.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.ui.components.SettingsSwitchRow

@Suppress("LongParameterList")
fun LazyListScope.serversSection(
    sortOutboundsByLatency: Boolean,
    showBothLatencyResults: Boolean,
    showAdvancedOptions: Boolean,
    editingLatencyCheckUrl: String,
    hasLatencyCheckUrlChanges: Boolean,
    onSortOutboundsByLatencyChange: (Boolean) -> Unit,
    onShowBothLatencyResultsChange: (Boolean) -> Unit,
    onEditingLatencyCheckUrlChange: (String) -> Unit,
    onSaveLatencyCheckUrl: () -> Unit,
) {
    item(key = "servers_header") {
        SettingsSectionHeader(
            title = stringResource(R.string.settings_section_servers),
            showDivider = true,
        )
    }

    item(key = "servers_sort_by_latency") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_sort_outbounds_by_latency_title),
            description = stringResource(R.string.settings_sort_outbounds_by_latency_description),
            checked = sortOutboundsByLatency,
            onCheckedChange = onSortOutboundsByLatencyChange,
        )
    }

    item(key = "servers_both_latency_results") {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_show_both_latency_results_title),
            description = stringResource(R.string.settings_show_both_latency_results_description),
            checked = showBothLatencyResults,
            onCheckedChange = onShowBothLatencyResultsChange,
        )
    }

    if (showAdvancedOptions) {
        item(key = "servers_latency_check_url") {
            SettingsTextFieldWithSave(
                value = editingLatencyCheckUrl,
                onValueChange = onEditingLatencyCheckUrlChange,
                label = stringResource(R.string.settings_latency_check_url_label),
                supportingText = { Text(stringResource(R.string.settings_latency_check_url_supporting_text)) },
                hasChanges = hasLatencyCheckUrlChanges,
                onSave = onSaveLatencyCheckUrl,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
