package com.material.xray.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.material.xray.core.ui.R

@Composable
fun UpdateChecksSetting(
    title: String,
    checked: Boolean,
    description: String,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val layoutDirection = LocalLayoutDirection.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            // Draw the separator at the label's measured edge. IntrinsicSize.Min would ask
            // AnimatedVisibility for its final height and make the whole row jump on expansion.
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).drawBehind {
                val inset = 12.dp.toPx()
                val thickness = 1.dp.toPx()
                val edge = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width - thickness
                drawRect(dividerColor, Offset(edge, inset), Size(thickness, (size.height - 2 * inset).coerceAtLeast(0f)))
            }.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            AnimatedOptionContent(visible = checked, blockOutgoingInput = false) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun <T> UpdateIntervalDialog(
    current: T,
    default: T,
    options: List<DropdownOption<T>>,
    onDismiss: () -> Unit,
    onConfirm: (T) -> Unit,
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(options.indexOfFirst { it.value == current }.coerceAtLeast(0)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_update_interval_title)) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = index == selectedIndex,
                                role = Role.RadioButton,
                                onClick = { selectedIndex = index },
                            )
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = null)
                        Text(if (option.value == default) stringResource(R.string.settings_update_interval_default, option.label) else option.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(options[selectedIndex].value) }) {
                Text(stringResource(R.string.settings_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}
