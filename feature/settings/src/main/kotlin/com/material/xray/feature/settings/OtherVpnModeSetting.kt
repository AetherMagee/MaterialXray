package com.material.xray.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.material.xray.core.model.OtherVpnMode
import com.material.xray.core.ui.R
import com.material.xray.core.ui.components.DropdownOption
import com.material.xray.core.ui.components.ReadOnlyDropdownField
import com.material.xray.core.ui.components.TooltipIconButton
import com.material.xray.core.ui.text.descriptionResource
import com.material.xray.core.ui.text.detailedDescriptionResource
import com.material.xray.core.ui.text.labelResource

@Composable
internal fun OtherVpnModeSetting(mode: OtherVpnMode, onModeChange: (OtherVpnMode) -> Unit) {
    var explanationMode by rememberSaveable { mutableStateOf<OtherVpnMode?>(null) }
    val helpLabel = stringResource(R.string.settings_other_vpn_mode_help, stringResource(mode.labelResource))

    ReadOnlyDropdownField(
        label = stringResource(R.string.settings_other_vpn_mode),
        selectedText = stringResource(mode.labelResource),
        supportingText = stringResource(mode.descriptionResource),
        supportingAction = if (mode == OtherVpnMode.StandDown) {
            null
        } else {
            {
                TooltipIconButton(tooltip = helpLabel, onClick = { explanationMode = mode }) {
                    Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = helpLabel)
                }
            }
        },
        options = OtherVpnMode.entries.map { option ->
            DropdownOption(
                value = option,
                label = stringResource(option.labelResource),
                description = stringResource(option.descriptionResource),
            )
        },
        onSelected = onModeChange,
        modifier = Modifier.padding(horizontal = 16.dp),
    )

    explanationMode?.let { selectedMode ->
        AlertDialog(
            onDismissRequest = { explanationMode = null },
            title = { Text(stringResource(selectedMode.labelResource)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(selectedMode.detailedDescriptionResource))
                }
            },
            confirmButton = {
                TextButton(onClick = { explanationMode = null }) {
                    Text(stringResource(R.string.settings_understand))
                }
            },
        )
    }
}
