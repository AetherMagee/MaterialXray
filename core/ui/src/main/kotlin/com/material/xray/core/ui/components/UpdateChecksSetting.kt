package com.material.xray.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.material.xray.core.model.AppUpdateInterval
import com.material.xray.core.ui.R
import com.material.xray.core.ui.text.descriptionResource
import com.material.xray.core.ui.text.labelResource

@Composable
fun UpdateChecksSetting(
    title: String,
    checked: Boolean,
    interval: AppUpdateInterval,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (checked) {
                Text(
                    stringResource(interval.descriptionResource),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        VerticalDivider(modifier = Modifier.fillMaxHeight().padding(vertical = 12.dp))
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun UpdateIntervalDialog(
    current: AppUpdateInterval,
    default: AppUpdateInterval = AppUpdateInterval.default,
    onDismiss: () -> Unit,
    onConfirm: (AppUpdateInterval) -> Unit,
) {
    var selectedHours by rememberSaveable { mutableStateOf(current.hours) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_update_interval_title)) },
        text = {
            Column {
                AppUpdateInterval.entries.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = interval.hours == selectedHours,
                                role = Role.RadioButton,
                                onClick = { selectedHours = interval.hours },
                            )
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = interval.hours == selectedHours, onClick = null)
                        val label = stringResource(interval.labelResource)
                        Text(if (interval == default) stringResource(R.string.settings_update_interval_default, label) else label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(AppUpdateInterval.fromHours(selectedHours)) }) {
                Text(stringResource(R.string.settings_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}
