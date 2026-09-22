package com.material.xray.ui.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.parser.ProfileRouting
import com.material.xray.data.parser.ProfileRoutingInspector
import com.material.xray.data.parser.ProfileRoutingRule
import com.material.xray.data.repository.ProviderRoutingAvailability
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.selectedProviderRoutingAvailability
import com.material.xray.model.ProfileRoutingOverrideEngine
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleCatalog
import com.material.xray.model.SubscriptionRouting
import com.material.xray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RoutingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val routingChangeManager: RoutingChangeManager,
    private val serverRepository: ServerRepository,
    private val subscriptionDao: SubscriptionDao,
) : ViewModel() {
    val rules: StateFlow<List<RoutingRule>> = settingsRepository.customRoutingRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    internal val profileRouting: StateFlow<ProfileRouting?> = combine(
        settingsRepository.lastServerId,
        serverRepository.observeAll(),
    ) { selectedServerId, servers ->
        servers.firstOrNull { it.id == selectedServerId }
            ?.let { entity -> runCatching { serverRepository.parseConfig(entity) }.getOrNull() }
            ?.let(ProfileRoutingInspector::inspect)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val selectedProviderRouting: StateFlow<ProviderRoutingAvailability?> = combine(
        settingsRepository.lastServerId,
        serverRepository.observeAll(),
        subscriptionDao.observeAll(),
        ::selectedProviderRoutingAvailability,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null,
    )
    val routingPolicyControl: StateFlow<RoutingPolicyControl> = combine(
        settingsRepository.routingPolicyControl,
        selectedProviderRouting,
        profileRouting,
    ) { policy, provider, profile ->
        val providerRulesAvailable = provider?.xrayRoutingProvided == true || profile?.rules?.any { !it.orphaned } == true
        if (policy == RoutingPolicyControl.SubscriptionProvider && providerRulesAvailable) {
            RoutingPolicyControl.SubscriptionProvider
        } else {
            RoutingPolicyControl.User
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutingPolicyControl.User)
    val automaticRoutingProviderName: StateFlow<String?> = selectedProviderRouting
        .map { it?.providerName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val subscriptionRules: StateFlow<List<RoutingRule>> = combine(
        settingsRepository.subscriptionRoutingRules,
        routingPolicyControl,
    ) { rules, policy ->
        rules.takeIf { policy == RoutingPolicyControl.SubscriptionProvider }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateRule(rule: RoutingRule) {
        viewModelScope.launch {
            settingsRepository.setRoutingRule(rule)
            routingChangeManager.markPendingChanges()
        }
    }

    fun addRule(rule: RoutingRule) {
        viewModelScope.launch {
            settingsRepository.setRoutingRules(rules.value + rule)
            routingChangeManager.markPendingChanges()
        }
    }

    fun deleteRules(ruleIds: Set<String>) {
        if (ruleIds.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setRoutingRules(rules.value.filterNot { it.id in ruleIds })
            routingChangeManager.markPendingChanges()
        }
    }

    fun setAllRulesEnabled(enabled: Boolean) {
        val updatedRules = rules.value.map { it.copy(enabled = enabled) }
        if (updatedRules == rules.value) return
        viewModelScope.launch {
            settingsRepository.setRoutingRules(updatedRules)
            routingChangeManager.markPendingChanges()
        }
    }

    fun resetRulesToDefaults() {
        viewModelScope.launch {
            settingsRepository.setCustomRouting(SubscriptionRouting(RoutingRuleCatalog.defaults()))
            routingChangeManager.markPendingChanges()
        }
    }

    internal fun setProfileRuleEnabled(rule: ProfileRoutingRule, enabled: Boolean) {
        updateProfileRule(
            rule = rule,
            replacement = rule.editableRule.takeIf { rule.locallyEdited },
            enabled = enabled,
        )
    }

    internal fun updateProfileRule(
        rule: ProfileRoutingRule,
        replacement: RoutingRule,
    ) {
        updateProfileRule(rule, replacement, enabled = rule.enabled)
    }

    private fun updateProfileRule(
        rule: ProfileRoutingRule,
        replacement: RoutingRule?,
        enabled: Boolean,
    ) {
        if (rule.orphaned) return
        viewModelScope.launch {
            val serverId = settingsRepository.lastServerId.first()
            val entity = serverRepository.getById(serverId) ?: return@launch
            val config = runCatching { serverRepository.parseConfig(entity) }.getOrNull() ?: return@launch
            val overrides = ProfileRoutingOverrideEngine.replace(
                overrides = config.profileRoutingOverrides,
                originalRuleJson = rule.originalRuleJson,
                originalIndex = rule.originalIndex,
                replacement = replacement,
                enabled = enabled,
            )
            settingsRepository.setRoutingPolicyControl(RoutingPolicyControl.User)
            serverRepository.saveEditedConfig(serverId, config.copy(profileRoutingOverrides = overrides))
            routingChangeManager.markPendingChanges()
        }
    }

    fun applyPendingChangesIfNeeded() {
        routingChangeManager.maybeReloadActiveConnection()
    }

    fun switchToManualRouting() {
        viewModelScope.launch {
            settingsRepository.setRoutingPolicyControl(RoutingPolicyControl.User)
        }
    }
}
