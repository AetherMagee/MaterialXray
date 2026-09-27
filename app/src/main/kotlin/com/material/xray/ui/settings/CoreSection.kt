package com.material.xray.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.model.RootConnectionBackend
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.ui.components.DropdownOption
import com.material.xray.ui.components.ReadOnlyDropdownField
import com.material.xray.ui.components.SettingsSwitchRow
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource

internal fun shouldShowTunMtu(
    rootServiceActive: Boolean,
    backend: RootConnectionBackend,
): Boolean = !rootServiceActive || backend == RootConnectionBackend.Tun

@Composable
internal fun RootTunNameSetting(
    visible: Boolean,
    editingTunName: String,
    hasTunNameChanges: Boolean,
    onEditingTunNameChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    SettingsTextFieldWithSave(
        value = editingTunName,
        onValueChange = onEditingTunNameChange,
        label = stringResource(R.string.settings_tun_interface_name_label),
        supportingText = { Text(stringResource(R.string.settings_tun_interface_name_automatic)) },
        hasChanges = hasTunNameChanges,
        onSave = onSave,
        modifier = modifier,
    )
}

@Composable
internal fun TunMtuSetting(
    visible: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    isValid: Boolean,
    hasChanges: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    SettingsTextFieldWithSave(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.settings_tun_mtu_label),
        supportingText = {
            Text(
                stringResource(
                    R.string.settings_tun_mtu_supporting_text,
                    XrayRuntimeSettings.MIN_TUN_MTU,
                    XrayRuntimeSettings.MAX_TUN_MTU,
                    XrayRuntimeSettings.DEFAULT_TUN_MTU,
                ),
            )
        },
        suffix = { Text(stringResource(R.string.settings_bytes_abbreviation)) },
        isError = value.isNotEmpty() && !isValid,
        hasChanges = hasChanges,
        onSave = onSave,
        saveEnabled = isValid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Suppress("LongParameterList")
fun LazyListScope.coreSection(
    rootServiceActive: Boolean,
    rootConnectionBackend: RootConnectionBackend,
    showAdvancedOptions: Boolean,
    editingTunName: String,
    hasTunNameChanges: Boolean,
    editingXrayBufferSizeKiB: String,
    isXrayBufferSizeKiBValid: Boolean,
    hasXrayBufferSizeKiBChanges: Boolean,
    editingTunMtu: String,
    isTunMtuValid: Boolean,
    hasTunMtuChanges: Boolean,
    editingXrayMemoryRestartThresholdMiB: String,
    isXrayMemoryRestartThresholdMiBValid: Boolean,
    hasXrayMemoryRestartThresholdMiBChanges: Boolean,
    passiveHealthMonitoringEnabled: Boolean,
    defaultOutbound: XrayOutbound,
    xrayLogLevel: XrayLogLevel,
    onEditingTunNameChange: (String) -> Unit,
    onSaveTunName: () -> Unit,
    onShowAdvancedOptionsChange: (Boolean) -> Unit,
    onEditingXrayBufferSizeKiBChange: (String) -> Unit,
    onSaveXrayBufferSizeKiB: () -> Unit,
    onEditingTunMtuChange: (String) -> Unit,
    onSaveTunMtu: () -> Unit,
    onEditingXrayMemoryRestartThresholdMiBChange: (String) -> Unit,
    onSaveXrayMemoryRestartThresholdMiB: () -> Unit,
    onPassiveHealthMonitoringEnabledChange: (Boolean) -> Unit,
    onDefaultOutboundChange: (XrayOutbound) -> Unit,
    onXrayLogLevelChange: (XrayLogLevel) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    item(key = "core") {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_core),
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                showDivider = true,
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (rootServiceActive && rootConnectionBackend == RootConnectionBackend.Tun) {
                        RootTunNameSetting(
                            visible = true,
                            editingTunName = editingTunName,
                            hasTunNameChanges = hasTunNameChanges,
                            onEditingTunNameChange = onEditingTunNameChange,
                            onSave = onSaveTunName,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_advanced_options),
                        checked = showAdvancedOptions,
                        onCheckedChange = onShowAdvancedOptionsChange,
                    )

                    if (showAdvancedOptions) {
                        AdvancedIntegerSetting(
                            value = editingXrayBufferSizeKiB,
                            onValueChange = onEditingXrayBufferSizeKiBChange,
                            label = stringResource(R.string.settings_xray_buffer_size_label),
                            supportingText = stringResource(
                                R.string.settings_xray_buffer_size_supporting_text,
                                XrayRuntimeSettings.MIN_XRAY_BUFFER_SIZE_KIB,
                                XrayRuntimeSettings.MAX_XRAY_BUFFER_SIZE_KIB,
                                XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
                            ),
                            suffix = stringResource(R.string.settings_kib_abbreviation),
                            isValid = isXrayBufferSizeKiBValid,
                            hasChanges = hasXrayBufferSizeKiBChanges,
                            onSave = onSaveXrayBufferSizeKiB,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        if (shouldShowTunMtu(rootServiceActive, rootConnectionBackend)) {
                            TunMtuSetting(
                                visible = true,
                                value = editingTunMtu,
                                onValueChange = onEditingTunMtuChange,
                                isValid = isTunMtuValid,
                                hasChanges = hasTunMtuChanges,
                                onSave = onSaveTunMtu,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }

                        AdvancedIntegerSetting(
                            value = editingXrayMemoryRestartThresholdMiB,
                            onValueChange = onEditingXrayMemoryRestartThresholdMiBChange,
                            label = stringResource(R.string.settings_xray_memory_restart_threshold_label),
                            supportingText = stringResource(
                                R.string.settings_xray_memory_restart_threshold_supporting_text,
                                XrayRuntimeSettings.MIN_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                                XrayRuntimeSettings.MAX_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                                XrayRuntimeSettings.DEFAULT_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                            ),
                            suffix = stringResource(R.string.settings_mib_abbreviation),
                            isValid = isXrayMemoryRestartThresholdMiBValid,
                            hasChanges = hasXrayMemoryRestartThresholdMiBChanges,
                            onSave = onSaveXrayMemoryRestartThresholdMiB,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_passive_health_monitoring_title),
                            description = stringResource(R.string.settings_passive_health_monitoring_description),
                            checked = passiveHealthMonitoringEnabled,
                            onCheckedChange = onPassiveHealthMonitoringEnabledChange,
                        )

                        ReadOnlyDropdownField(
                            label = stringResource(R.string.settings_default_outbound_label),
                            selectedText = stringResource(defaultOutbound.labelResource),
                            supportingText = stringResource(defaultOutbound.descriptionResource),
                            options = XrayOutbound.entries.map { outbound ->
                                DropdownOption(
                                    value = outbound,
                                    label = stringResource(outbound.labelResource),
                                    description = stringResource(outbound.descriptionResource),
                                )
                            },
                            onSelected = onDefaultOutboundChange,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        ReadOnlyDropdownField(
                            label = stringResource(R.string.settings_xray_log_level_label),
                            selectedText = stringResource(xrayLogLevel.labelResource),
                            supportingText = stringResource(
                                R.string.settings_default_value,
                                stringResource(XrayLogLevel.default.labelResource),
                            ),
                            options = XrayLogLevel.entries.map { level ->
                                DropdownOption(
                                    value = level,
                                    label = stringResource(level.labelResource),
                                )
                            },
                            onSelected = onXrayLogLevelChange,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
