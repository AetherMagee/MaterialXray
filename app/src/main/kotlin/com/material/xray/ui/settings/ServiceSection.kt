package com.material.xray.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.model.RootConnectionBackend
import com.material.xray.service.OemAutostartGuidance
import com.material.xray.ui.components.SelectableOptionRow
import com.material.xray.ui.components.SettingsSwitchRow
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource

@Composable
internal fun OemAutostartBanner(
    directSettingsAvailable: Boolean,
    onOpenSettings: () -> Unit,
) {
    SettingsNotice(
        text = stringResource(
            if (directSettingsAvailable) {
                R.string.settings_oem_autostart_required
            } else {
                R.string.settings_oem_autostart_external_required
            },
        ),
        action = {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    stringResource(
                        if (directSettingsAvailable) {
                            R.string.settings_open_autostart_settings
                        } else {
                            R.string.settings_open_app_settings
                        },
                    ),
                )
            }
        },
    )
}

@Composable
internal fun tproxyCompatibilitySupportingText(compatibility: TproxyCompatibility): String? = when (compatibility) {
    TproxyCompatibility.Unknown,
    TproxyCompatibility.Checking,
    -> null
    is TproxyCompatibility.Supported -> null
    is TproxyCompatibility.Unsupported -> stringResource(R.string.settings_tproxy_unsupported)
}

@Suppress("LongParameterList")
fun LazyListScope.serviceSection(
    rootAvailable: Boolean?,
    rootServiceAvailable: Boolean,
    rootServiceActive: Boolean,
    useRootService: Boolean,
    rootConnectionBackend: RootConnectionBackend,
    tunnelTetheredClients: Boolean,
    tproxyCompatibility: TproxyCompatibility,
    autoConnect: Boolean,
    oemAutostartGuidance: OemAutostartGuidance,
    onUseRootServiceChange: (Boolean) -> Unit,
    onRootConnectionBackendChange: (RootConnectionBackend) -> Unit,
    onTunnelTetheredClientsChange: (Boolean) -> Unit,
    onRetryTproxyCompatibility: () -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onOpenOemAutostartSettings: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    item(key = "service") {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_section_connection),
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                showDivider = false,
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_use_root_service),
                        description = stringResource(R.string.settings_unavailable).takeIf { rootAvailable == false },
                        checked = useRootService && rootAvailable != false,
                        onCheckedChange = onUseRootServiceChange,
                        enabled = rootServiceAvailable,
                        titleColor = if (rootAvailable == false) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )

                    if (rootServiceActive) {
                        val tproxySelectable = tproxyCompatibility !is TproxyCompatibility.Unsupported
                        val supportingText = tproxyCompatibilitySupportingText(tproxyCompatibility)
                        SettingsNestedSection(title = stringResource(R.string.settings_root_connection_backend)) {
                            RootConnectionBackend.entries.forEach { backend ->
                                val enabled = backend == RootConnectionBackend.Tun || tproxySelectable
                                SelectableOptionRow(
                                    title = stringResource(backend.labelResource),
                                    description = stringResource(backend.descriptionResource),
                                    selected = backend == rootConnectionBackend,
                                    onSelected = { onRootConnectionBackendChange(backend) },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    enabled = enabled,
                                )
                            }
                            supportingText?.let { text ->
                                Text(
                                    text = text,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (tproxyCompatibility is TproxyCompatibility.Unsupported) {
                            TextButton(
                                onClick = onRetryTproxyCompatibility,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) {
                                Text(stringResource(R.string.settings_retry_compatibility_check))
                            }
                        }

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_tunnel_tethered_clients_title),
                            description = stringResource(R.string.settings_tunnel_tethered_clients_description),
                            checked = tunnelTetheredClients,
                            onCheckedChange = onTunnelTetheredClientsChange,
                        )
                    }

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_connect_on_boot),
                        checked = autoConnect,
                        onCheckedChange = onAutoConnectChange,
                        enabled = !useRootService || rootServiceActive,
                    )

                    if (autoConnect && oemAutostartGuidance.required && !oemAutostartGuidance.granted) {
                        OemAutostartBanner(
                            directSettingsAvailable = oemAutostartGuidance.directSettingsAvailable,
                            onOpenSettings = onOpenOemAutostartSettings,
                        )
                    }
                }
            }
        }
    }
}

fun LazyListScope.connectionDnsSection(onOpenDnsSettings: () -> Unit) {
    connectionDnsSection(
        onOpenDnsSettings = onOpenDnsSettings,
        expanded = true,
    )
}

fun LazyListScope.connectionDnsSection(
    onOpenDnsSettings: () -> Unit,
    expanded: Boolean,
) {
    item(key = "connection_dns") {
        Column {
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SettingsActionRow(
                        title = stringResource(R.string.settings_dns_title),
                        subtitle = stringResource(R.string.settings_dns_row_subtitle),
                        onClick = onOpenDnsSettings,
                    )
                }
            }
        }
    }
}

fun LazyListScope.connectionHardwareIdSection(
    subscriptionSendHardwareId: Boolean,
    hwidLockedBySubscription: Boolean,
    onSubscriptionSendHardwareIdChange: (Boolean) -> Unit,
) {
    connectionHardwareIdSection(
        subscriptionSendHardwareId = subscriptionSendHardwareId,
        hwidLockedBySubscription = hwidLockedBySubscription,
        onSubscriptionSendHardwareIdChange = onSubscriptionSendHardwareIdChange,
        expanded = true,
    )
}

fun LazyListScope.connectionHardwareIdSection(
    subscriptionSendHardwareId: Boolean,
    hwidLockedBySubscription: Boolean,
    onSubscriptionSendHardwareIdChange: (Boolean) -> Unit,
    expanded: Boolean,
) {
    item(key = "connection_hardware_id") {
        Column {
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_send_hardware_id_title),
                        description = stringResource(
                            if (hwidLockedBySubscription) {
                                R.string.settings_send_hardware_id_locked
                            } else {
                                R.string.settings_send_hardware_id_description
                            },
                        ),
                        checked = subscriptionSendHardwareId,
                        onCheckedChange = onSubscriptionSendHardwareIdChange,
                        enabled = !hwidLockedBySubscription,
                    )
                }
            }
        }
    }
}
