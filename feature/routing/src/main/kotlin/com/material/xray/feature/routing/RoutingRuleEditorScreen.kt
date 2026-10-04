package com.material.xray.feature.routing

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.core.model.RoutingPolicyControl
import com.material.xray.core.model.RoutingRule
import com.material.xray.core.model.RoutingRuleOperator
import com.material.xray.core.model.XrayOutbound
import com.material.xray.core.ui.R
import com.material.xray.core.ui.components.DropdownOption
import com.material.xray.core.ui.components.FadingOutlinedTextField as OutlinedTextField
import com.material.xray.core.ui.components.ReadOnlyDropdownField
import com.material.xray.core.ui.components.TooltipIconButton
import com.material.xray.core.ui.text.catchAllEffectResource
import com.material.xray.core.ui.text.descriptionResource
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.compose.viewmodel.koinViewModel

private val routingRuleSaver: Saver<RoutingRule?, String> = Saver(
    save = { value -> value?.let { Json.encodeToString(it) } },
    restore = { saved -> runCatching { Json.decodeFromString<RoutingRule>(saved) }.getOrNull() },
)

private val protocolOptions = listOf("http", "tls", "quic", "bittorrent")
private data class MatchModeOption(
    val value: RoutingRuleOperator,
    @param:StringRes val labelResource: Int,
    @param:StringRes val descriptionResource: Int,
)

private val matchModeOptions = listOf(
    MatchModeOption(
        value = RoutingRuleOperator.AND,
        labelResource = R.string.routing_match_all_label,
        descriptionResource = R.string.routing_match_all_description,
    ),
    MatchModeOption(
        value = RoutingRuleOperator.OR,
        labelResource = R.string.routing_match_any_label,
        descriptionResource = R.string.routing_match_any_description,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingRuleEditorScreen(
    editableRule: EditableRoutingRule,
    onBack: () -> Unit,
    viewModel: RoutingViewModel = koinViewModel(),
) {
    BackHandler(onBack = onBack)
    val rule = editableRule.rule
    val profileRouting by viewModel.profileRouting.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val providerName by viewModel.automaticRoutingProviderName.collectAsStateWithLifecycle()
    var pendingProviderRule by rememberSaveable(rule.id, stateSaver = routingRuleSaver) {
        mutableStateOf<RoutingRule?>(null)
    }
    val profileRule = editableRule.profileOriginalRuleJson?.let { originalJson ->
        profileRouting?.rules?.firstOrNull {
            it.originalIndex == editableRule.profileOriginalIndex && it.originalRuleJson == originalJson
        }
    }
    val canSave = editableRule.profileOriginalRuleJson == null || profileRule != null

    fun saveRule(updatedRule: RoutingRule) {
        if (editableRule.subscriptionWide) {
            viewModel.updateSubscriptionRule(updatedRule)
        } else if (editableRule.profileOriginalRuleJson != null) {
            viewModel.updateProfileRule(profileRule ?: return, updatedRule)
        } else if (editableRule.isNew) {
            viewModel.addRule(updatedRule)
        } else {
            viewModel.updateRule(updatedRule)
        }
        onBack()
    }

    val name = rememberSaveable(rule.id, saver = TextFieldState.Saver) { TextFieldState(rule.name) }
    val domains = rememberSaveable(rule.id, saver = TextFieldState.Saver) { TextFieldState(rule.domains.joinToString(", ")) }
    val ips = rememberSaveable(rule.id, saver = TextFieldState.Saver) { TextFieldState(rule.ips.joinToString(", ")) }
    val port = rememberSaveable(rule.id, saver = TextFieldState.Saver) { TextFieldState(rule.port.orEmpty()) }
    var selectedOutbound by rememberSaveable(rule.id) { mutableStateOf(rule.outboundTag) }
    var selectedOperator by rememberSaveable(rule.id) { mutableStateOf(rule.operator) }
    var selectedProtocols by rememberSaveable(rule.id) { mutableStateOf(rule.protocols.toSet()) }
    var pendingCatchAllRule by rememberSaveable(rule.id, stateSaver = routingRuleSaver) {
        mutableStateOf<RoutingRule?>(null)
    }
    val availableProtocolOptions = remember(rule.protocols) { (protocolOptions + rule.protocols).distinct() }
    val outboundOption = remember(selectedOutbound) { XrayOutbound.fromTag(selectedOutbound) }
    val matchModeOption = remember(selectedOperator) { matchModeOptions.first { it.value == selectedOperator } }
    val outboundDescription = stringResource(outboundOption.descriptionResource)
    val matchModeLabel = stringResource(matchModeOption.labelResource)
    val matchModeDescription = stringResource(matchModeOption.descriptionResource)
    val outboundOptions = XrayOutbound.entries.map { option ->
        DropdownOption(
            value = option.tag,
            label = option.tag,
            description = stringResource(option.descriptionResource),
        )
    }
    val localizedMatchModeOptions = matchModeOptions.map { option ->
        DropdownOption(
            value = option.value.name,
            label = stringResource(option.labelResource),
            description = stringResource(option.descriptionResource),
        )
    }

    fun editedRule(): RoutingRule = rule.copy(
        name = name.text.toString().trim().ifEmpty { rule.name },
        outboundTag = selectedOutbound,
        domains = splitCsv(domains.text.toString()),
        ips = splitCsv(ips.text.toString()),
        port = port.text.toString().trim().ifEmpty { null },
        protocols = availableProtocolOptions.filter { it in selectedProtocols },
        operator = selectedOperator,
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_edit_rule_title)) },
                navigationIcon = {
                    TooltipIconButton(tooltip = stringResource(R.string.routing_rule_viewer_back), onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.routing_rule_viewer_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Button(
                enabled = canSave,
                onClick = {
                    val edited = editedRule()
                    if (edited.matchesAllTraffic()) {
                        pendingCatchAllRule = edited
                    } else if (editableRule.providerSourced &&
                        routingPolicyControl == RoutingPolicyControl.SubscriptionProvider
                    ) {
                        pendingProviderRule = edited
                    } else {
                        saveRule(edited)
                    }
                },
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.routing_save))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                state = name,
                label = { Text(stringResource(R.string.routing_name_label)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth(),
            )

            ReadOnlyDropdownField(
                label = stringResource(R.string.routing_outbound_tag_label),
                selectedText = outboundOption.tag,
                supportingText = outboundDescription,
                options = outboundOptions,
                onSelected = { selectedOutbound = it },
            )

            ReadOnlyDropdownField(
                label = stringResource(R.string.routing_match_mode_label),
                selectedText = matchModeLabel,
                supportingText = matchModeDescription,
                options = localizedMatchModeOptions,
                onSelected = { selectedOperator = RoutingRuleOperator.valueOf(it) },
            )

            OutlinedTextField(
                state = domains,
                label = { Text(stringResource(R.string.routing_domains_label)) },
                supportingText = { Text(stringResource(R.string.routing_domains_supporting_text)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                state = ips,
                label = { Text(stringResource(R.string.routing_ips_label)) },
                supportingText = { Text(stringResource(R.string.routing_ips_supporting_text)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                state = port,
                label = { Text(stringResource(R.string.routing_port_label)) },
                supportingText = { Text(stringResource(R.string.routing_port_supporting_text)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.routing_protocols_label), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.routing_protocols_empty_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            availableProtocolOptions.forEach { protocol ->
                val checked = protocol in selectedProtocols
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = if (checked) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    border = BorderStroke(
                        1.dp,
                        if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                onValueChange = { enabled ->
                                    selectedProtocols = if (enabled) {
                                        selectedProtocols + protocol
                                    } else {
                                        selectedProtocols - protocol
                                    }
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = protocol.uppercase(Locale.ROOT),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            editableRule.rawJson?.let { rawJson ->
                Text(
                    text = stringResource(R.string.routing_rule_raw_json),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                HighlightedJson(rawJson)
            }
        }
    }

    pendingCatchAllRule?.let { candidate ->
        CatchAllRuleWarningDialog(
            outbound = XrayOutbound.fromTag(candidate.outboundTag),
            onKeepEditing = { pendingCatchAllRule = null },
            onSaveAnyway = {
                pendingCatchAllRule = null
                if (editableRule.providerSourced &&
                    routingPolicyControl == RoutingPolicyControl.SubscriptionProvider
                ) {
                    pendingProviderRule = candidate
                } else {
                    saveRule(candidate)
                }
            },
        )
    }

    if (pendingProviderRule != null) {
        AutomaticRuleRoutingDialog(
            providerName = providerName,
            onDismiss = { pendingProviderRule = null },
            onSwitchToManual = { pendingProviderRule?.let(::saveRule) },
        )
    }
}

@Composable
private fun CatchAllRuleWarningDialog(
    outbound: XrayOutbound,
    onKeepEditing: () -> Unit,
    onSaveAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.routing_catch_all_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.routing_catch_all_warning_description))
                Text(
                    text = stringResource(outbound.catchAllEffectResource),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSaveAnyway) {
                Text(stringResource(R.string.routing_catch_all_save_anyway))
            }
        },
        dismissButton = {
            Button(onClick = onKeepEditing) {
                Text(stringResource(R.string.routing_catch_all_keep_editing))
            }
        },
    )
}

private fun splitCsv(value: String): List<String> = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
