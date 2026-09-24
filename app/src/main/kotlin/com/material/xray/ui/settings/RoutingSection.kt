package com.material.xray.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.ipv6DnsServers
import com.material.xray.ui.components.SelectableOptionRow
import com.material.xray.ui.components.SettingsSwitchRow
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource

/** Whether IPv6 is allowed but no configured resolver can be reached over it. */
internal fun hasIpv4OnlyDnsServers(dnsServers: String, domesticDnsServers: String): Boolean {
    val lists = listOf(dnsServers, domesticDnsServers)
    // An empty list hands that lookup to the OS resolver, which a dual-stack network may well have
    // given an IPv6 address. That is an unknown rather than an absence, so there is nothing to claim.
    if (lists.any(String::isBlank)) return false
    return lists.none { ipv6DnsServers(it).isNotEmpty() }
}

@Suppress("LongParameterList")
fun LazyListScope.routingSection(
    bypassLan: Boolean,
    allowIpv6: Boolean,
    dnsServers: String,
    domesticDnsServers: String,
    routingPolicyControl: RoutingPolicyControl,
    ipv6SelectionEnabled: Boolean,
    onBypassLanChange: (Boolean) -> Unit,
    onAllowIpv6Change: (Boolean) -> Unit,
    onRoutingPolicyControlChange: (RoutingPolicyControl) -> Unit,
) {
    item(key = "connection_connectivity") {
        SettingsNestedSection(title = stringResource(R.string.settings_connectivity_title)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_bypass_lan_title),
                description = stringResource(R.string.settings_bypass_lan_description),
                checked = bypassLan,
                onCheckedChange = onBypassLanChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_allow_ipv6_connections),
                checked = allowIpv6,
                onCheckedChange = onAllowIpv6Change,
                enabled = ipv6SelectionEnabled,
            )
            if (allowIpv6 && hasIpv4OnlyDnsServers(dnsServers, domesticDnsServers)) {
                SettingsNotice(text = stringResource(R.string.settings_allow_ipv6_dns_ipv4_only))
            }
        }
    }

    item(key = "connection_routing_policy") {
        SettingsNestedSection(title = stringResource(R.string.settings_routing_policy_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RoutingPolicyControl.entries.forEach { policy ->
                    SelectableOptionRow(
                        title = stringResource(policy.labelResource),
                        description = stringResource(policy.descriptionResource),
                        selected = policy == routingPolicyControl,
                        onSelected = { onRoutingPolicyControlChange(policy) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
