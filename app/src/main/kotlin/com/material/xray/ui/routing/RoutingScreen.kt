package com.material.xray.ui.routing

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.data.parser.ProfileRoutingRule
import com.material.xray.data.parser.ProfileRoutingTarget
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleCatalog
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.XrayOutbound
import com.material.xray.ui.apps.AppBypassContent
import com.material.xray.ui.apps.AppRoutingMenuActions
import com.material.xray.ui.components.AppBarTitle
import com.material.xray.ui.components.DropdownOption
import com.material.xray.ui.components.ReadOnlyDropdownField
import com.material.xray.ui.components.ScrollFadeEdges
import com.material.xray.ui.components.SegmentedTabRow
import com.material.xray.ui.text.catchAllEffectResource
import com.material.xray.ui.text.descriptionResource
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private enum class RoutingTab(@StringRes val titleResource: Int) {
    Rules(R.string.routing_tab_rules),
    Apps(R.string.routing_tab_apps),
}

@Serializable
private data class EditableRoutingRule(
    val rule: RoutingRule,
    val isNew: Boolean,
    val profileOriginalRuleJson: String? = null,
    val profileOriginalIndex: Int? = null,
)

/** Persists an in-progress rule edit across configuration changes and process death. */
private val editableRoutingRuleSaver: Saver<EditableRoutingRule?, String> = jsonSaver()

/** Persists the rule pending catch-all confirmation across configuration changes. */
private val routingRuleSaver: Saver<RoutingRule?, String> = jsonSaver()

private inline fun <reified T : Any> jsonSaver(): Saver<T?, String> = Saver(
    save = { value -> value?.let { Json.encodeToString(it) } },
    restore = { saved -> runCatching { Json.decodeFromString<T>(saved) }.getOrNull() },
)

private sealed interface RoutingRuleAction {
    data object Add : RoutingRuleAction
    data object EnableAll : RoutingRuleAction
    data object DisableAll : RoutingRuleAction
    data object ResetToDefault : RoutingRuleAction
    data class Edit(val rule: RoutingRule) : RoutingRuleAction
    data class Toggle(val rule: RoutingRule, val enabled: Boolean) : RoutingRuleAction
    data class Delete(val ruleIds: Set<String>) : RoutingRuleAction
}

private sealed interface ProfileRoutingRuleAction {
    data class Edit(val rule: ProfileRoutingRule) : ProfileRoutingRuleAction
    data class Toggle(val rule: ProfileRoutingRule, val enabled: Boolean) : ProfileRoutingRuleAction
}

private val protocolOptions = listOf("http", "tls", "quic", "bittorrent")
private val defaultRoutingRulesById = RoutingRuleCatalog.defaults().associateBy(RoutingRule::id)

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
fun RoutingScreen(
    showTitleBarLogo: Boolean,
    onViewRule: (RoutingRuleViewerRequest) -> Unit,
    viewModel: RoutingViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val subscriptionRules by viewModel.subscriptionRules.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val automaticRoutingProviderName by viewModel.automaticRoutingProviderName.collectAsStateWithLifecycle()
    val profileRouting by viewModel.profileRouting.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { RoutingTab.entries.size })
    val coroutineScope = rememberCoroutineScope()
    var previousTab by remember { mutableIntStateOf(pagerState.currentPage) }
    var selectedRuleIds by remember { mutableStateOf(emptySet<String>()) }
    var editingRule by rememberSaveable(stateSaver = editableRoutingRuleSaver) {
        mutableStateOf<EditableRoutingRule?>(null)
    }
    var pendingProfileAction by remember { mutableStateOf<ProfileRoutingRuleAction?>(null) }
    var confirmResetToDefault by remember { mutableStateOf(false) }
    val selectionMode by remember { derivedStateOf { selectedRuleIds.isNotEmpty() } }
    val selectedTab = pagerState.currentPage
    val newRuleName = stringResource(R.string.routing_new_rule_name)

    fun applyRuleAction(action: RoutingRuleAction) {
        when (action) {
            RoutingRuleAction.Add -> {
                editingRule = EditableRoutingRule(
                    rule = RoutingRule(
                        id = "custom-${System.currentTimeMillis()}",
                        name = newRuleName,
                        outboundTag = "proxy",
                    ),
                    isNew = true,
                )
            }
            RoutingRuleAction.EnableAll -> viewModel.setAllRulesEnabled(true)
            RoutingRuleAction.DisableAll -> viewModel.setAllRulesEnabled(false)
            RoutingRuleAction.ResetToDefault -> confirmResetToDefault = true
            is RoutingRuleAction.Edit -> editingRule = EditableRoutingRule(rule = action.rule, isNew = false)
            is RoutingRuleAction.Toggle -> viewModel.updateRule(action.rule.copy(enabled = action.enabled))
            is RoutingRuleAction.Delete -> {
                viewModel.deleteRules(action.ruleIds)
                selectedRuleIds = emptySet()
            }
        }
    }

    fun applyProfileRuleAction(action: ProfileRoutingRuleAction) {
        when (action) {
            is ProfileRoutingRuleAction.Edit -> {
                val editable = action.rule.editableRule ?: return
                editingRule = EditableRoutingRule(
                    rule = editable,
                    isNew = false,
                    profileOriginalRuleJson = action.rule.originalRuleJson,
                    profileOriginalIndex = action.rule.originalIndex,
                )
            }
            is ProfileRoutingRuleAction.Toggle -> viewModel.setProfileRuleEnabled(action.rule, action.enabled)
        }
    }

    fun requestProfileRuleAction(action: ProfileRoutingRuleAction) {
        if (routingPolicyControl == RoutingPolicyControl.SubscriptionProvider) {
            pendingProfileAction = action
        } else {
            applyProfileRuleAction(action)
        }
    }

    LaunchedEffect(routingPolicyControl) {
        if (routingPolicyControl == RoutingPolicyControl.User) {
            pendingProfileAction?.let(::applyProfileRuleAction)
            pendingProfileAction = null
        }
    }

    LaunchedEffect(selectedTab) {
        if (previousTab != selectedTab) {
            if (previousTab == RoutingTab.Rules.ordinal) {
                selectedRuleIds = emptySet()
            }
            viewModel.applyPendingChangesIfNeeded()
        }
        previousTab = selectedTab
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            RoutingTopBar(
                selectedTab = RoutingTab.entries[pagerState.currentPage],
                showTitleBarLogo = showTitleBarLogo,
                selectionMode = selectionMode,
                selectedRuleIds = selectedRuleIds,
                rules = rules,
                onClearSelection = { selectedRuleIds = emptySet() },
                onRuleAction = ::applyRuleAction,
            )
        },
        bottomBar = {
            SegmentedTabRow(
                labels = RoutingTab.entries.map { stringResource(it.titleResource) },
                selectedIndex = selectedTab,
                onSelected = { index ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            when (RoutingTab.entries[page]) {
                RoutingTab.Rules -> RoutingRulesTab(
                    customRules = rules,
                    subscriptionRules = subscriptionRules,
                    profileRules = profileRouting?.rules.orEmpty(),
                    providerManaged = routingPolicyControl == RoutingPolicyControl.SubscriptionProvider,
                    providerName = automaticRoutingProviderName,
                    selectionMode = selectionMode,
                    selectedRuleIds = selectedRuleIds,
                    onRuleToggled = { rule, enabled -> applyRuleAction(RoutingRuleAction.Toggle(rule, enabled)) },
                    onRuleClick = { rule ->
                        if (selectionMode) {
                            selectedRuleIds = selectedRuleIds.toggle(rule.id)
                        } else {
                            applyRuleAction(RoutingRuleAction.Edit(rule))
                        }
                    },
                    onRuleLongClick = { rule ->
                        selectedRuleIds = selectedRuleIds.toggle(rule.id)
                    },
                    onSubscriptionRuleClick = { rule -> onViewRule(rule.toViewerRequest()) },
                    onProfileRuleClick = { rule ->
                        if (rule.orphaned || rule.editableRule == null) {
                            onViewRule(rule.toViewerRequest())
                        } else {
                            requestProfileRuleAction(ProfileRoutingRuleAction.Edit(rule))
                        }
                    },
                    onProfileRuleToggled = { rule, enabled ->
                        requestProfileRuleAction(ProfileRoutingRuleAction.Toggle(rule, enabled))
                    },
                )
                RoutingTab.Apps -> AppBypassContent(active = selectedTab == RoutingTab.Apps.ordinal)
            }
        }
    }

    editingRule?.let { editableRule ->
        EditRoutingRuleDialog(
            rule = editableRule.rule,
            onDismiss = { editingRule = null },
            onSave = { updatedRule ->
                saveEditedRoutingRule(
                    editableRule = editableRule,
                    updatedRule = updatedRule,
                    profileRules = profileRouting?.rules.orEmpty(),
                    onUpdateProfile = viewModel::updateProfileRule,
                    onAdd = viewModel::addRule,
                    onUpdate = viewModel::updateRule,
                )
                editingRule = null
            },
        )
    }

    if (pendingProfileAction != null) {
        AutomaticRuleRoutingDialog(
            providerName = automaticRoutingProviderName,
            onDismiss = { pendingProfileAction = null },
            onSwitchToManual = viewModel::switchToManualRouting,
        )
    }

    if (confirmResetToDefault) {
        AlertDialog(
            onDismissRequest = { confirmResetToDefault = false },
            title = { Text(stringResource(R.string.routing_reset_to_default_title)) },
            text = { Text(stringResource(R.string.routing_reset_to_default_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetRulesToDefaults()
                        confirmResetToDefault = false
                    },
                ) {
                    Text(stringResource(R.string.routing_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetToDefault = false }) {
                    Text(stringResource(R.string.routing_cancel))
                }
            },
        )
    }
}

private fun saveEditedRoutingRule(
    editableRule: EditableRoutingRule,
    updatedRule: RoutingRule,
    profileRules: List<ProfileRoutingRule>,
    onUpdateProfile: (ProfileRoutingRule, RoutingRule) -> Unit,
    onAdd: (RoutingRule) -> Unit,
    onUpdate: (RoutingRule) -> Unit,
) {
    if (editableRule.profileOriginalRuleJson != null) {
        profileRules.firstOrNull {
            it.originalIndex == editableRule.profileOriginalIndex &&
                it.originalRuleJson == editableRule.profileOriginalRuleJson
        }?.let { profileRule -> onUpdateProfile(profileRule, updatedRule) }
    } else if (editableRule.isNew) {
        onAdd(updatedRule)
    } else {
        onUpdate(updatedRule)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingTopBar(
    selectedTab: RoutingTab,
    showTitleBarLogo: Boolean,
    selectionMode: Boolean,
    selectedRuleIds: Set<String>,
    rules: List<RoutingRule>,
    onClearSelection: () -> Unit,
    onRuleAction: (RoutingRuleAction) -> Unit,
) {
    var rulesMenuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            AppBarTitle(
                if (selectedTab == RoutingTab.Rules && selectionMode) {
                    pluralStringResource(
                        R.plurals.routing_rules_selected,
                        selectedRuleIds.size,
                        selectedRuleIds.size,
                    )
                } else {
                    stringResource(R.string.routing_title)
                },
                showTitleBarLogo,
            )
        },
        expandedHeight = 52.dp,
        windowInsets = TopAppBarDefaults.windowInsets,
        actions = {
            when {
                selectedTab == RoutingTab.Apps -> AppRoutingMenuActions()
                selectionMode -> {
                    IconButton(onClick = onClearSelection) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.routing_clear_selection),
                        )
                    }
                    IconButton(
                        onClick = { onRuleAction(RoutingRuleAction.Delete(selectedRuleIds)) },
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.routing_delete_selected_rules),
                        )
                    }
                }
                else -> {
                    IconButton(onClick = { onRuleAction(RoutingRuleAction.Add) }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.routing_add_rule),
                        )
                    }
                    Box {
                        IconButton(onClick = { rulesMenuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.routing_rules_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = rulesMenuExpanded,
                            onDismissRequest = { rulesMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.routing_enable_all)) },
                                enabled = rules.any { !it.enabled },
                                onClick = {
                                    rulesMenuExpanded = false
                                    onRuleAction(RoutingRuleAction.EnableAll)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.routing_disable_all)) },
                                enabled = rules.any { it.enabled },
                                onClick = {
                                    rulesMenuExpanded = false
                                    onRuleAction(RoutingRuleAction.DisableAll)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.routing_reset_to_default)) },
                                onClick = {
                                    rulesMenuExpanded = false
                                    onRuleAction(RoutingRuleAction.ResetToDefault)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoutingRulesTab(
    customRules: List<RoutingRule>,
    subscriptionRules: List<RoutingRule>,
    profileRules: List<ProfileRoutingRule>,
    providerManaged: Boolean,
    providerName: String?,
    selectionMode: Boolean,
    selectedRuleIds: Set<String>,
    onRuleToggled: (RoutingRule, Boolean) -> Unit,
    onRuleClick: (RoutingRule) -> Unit,
    onRuleLongClick: (RoutingRule) -> Unit,
    onSubscriptionRuleClick: (RoutingRule) -> Unit,
    onProfileRuleClick: (ProfileRoutingRule) -> Unit,
    onProfileRuleToggled: (ProfileRoutingRule, Boolean) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (providerManaged) {
                item(contentType = "providerRoutingBanner") {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = when {
                                    customRules.isNotEmpty() && providerName != null -> stringResource(
                                        R.string.routing_some_rules_managed_by_provider,
                                        providerName,
                                    )
                                    customRules.isNotEmpty() -> stringResource(
                                        R.string.routing_some_rules_managed_by_selected_subscription,
                                    )
                                    providerName != null -> stringResource(
                                        R.string.routing_rules_managed_by_provider,
                                        providerName,
                                    )
                                    else -> stringResource(R.string.routing_rules_managed_by_selected_subscription)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (customRules.isNotEmpty()) {
                item(contentType = "routingScopeHeader") {
                    RoutingScopeHeader(R.string.routing_scope_custom)
                }
            }
            items(items = customRules, key = { it.id }, contentType = { "routingRule" }) { rule ->
                val selected = rule.id in selectedRuleIds
                val containerColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    label = "routingRuleContainerColor",
                )
                val borderColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    label = "routingRuleBorderColor",
                )
                val contentText = routingRuleContentText(rule)

                Surface(
                    color = containerColor,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .combinedClickable(
                            onClick = { onRuleClick(rule) },
                            onLongClick = { onRuleLongClick(rule) },
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedVisibility(
                            visible = selectionMode,
                            enter = expandHorizontally(
                                animationSpec = tween(durationMillis = 180),
                                expandFrom = Alignment.Start,
                            ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                            exit = shrinkHorizontally(
                                animationSpec = tween(durationMillis = 150),
                                shrinkTowards = Alignment.Start,
                            ) + fadeOut(animationSpec = tween(durationMillis = 90)),
                        ) {
                            Box(modifier = Modifier.padding(end = 12.dp)) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = null,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = routingRuleDisplayName(rule),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Switch(
                            checked = rule.enabled,
                            enabled = !selectionMode,
                            onCheckedChange = { enabled -> onRuleToggled(rule, enabled) },
                        )
                    }
                }
            }
            if (subscriptionRules.isNotEmpty()) {
                item(contentType = "routingScopeHeader") {
                    RoutingScopeHeader(R.string.routing_scope_subscription_wide)
                }
                itemsIndexed(
                    items = subscriptionRules,
                    key = { index, rule -> "subscription-$index-${rule.id}" },
                    contentType = { _, _ -> "subscriptionRoutingRule" },
                ) { _, rule ->
                    SubscriptionRoutingRuleCard(rule = rule, onClick = { onSubscriptionRuleClick(rule) })
                }
            }
            if (profileRules.isNotEmpty()) {
                item(contentType = "routingScopeHeader") {
                    RoutingScopeHeader(R.string.routing_scope_profile_specific)
                }
                itemsIndexed(
                    items = profileRules,
                    key = { index, rule -> "profile-$index-${rule.id}" },
                    contentType = { _, _ -> "profileRoutingRule" },
                ) { _, rule ->
                    ProfileRoutingRuleCard(
                        rule = rule,
                        onClick = { onProfileRuleClick(rule) },
                        onToggled = { enabled -> onProfileRuleToggled(rule, enabled) },
                    )
                }
            }
        }
        ScrollFadeEdges()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionRoutingRuleCard(rule: RoutingRule, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = routingRuleDisplayName(rule),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = routingRuleContentText(rule),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoutingScopeHeader(@StringRes titleResource: Int) {
    Text(
        text = stringResource(titleResource),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileRoutingRuleCard(
    rule: ProfileRoutingRule,
    onClick: () -> Unit,
    onToggled: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (rule.orphaned) 0.55f else 1f)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profileRoutingRuleContentText(rule),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rule.target?.let { target ->
                    Text(
                        text = target.displayText(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rule.orphaned) {
                    Text(
                        text = stringResource(R.string.routing_profile_rule_orphaned),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(
                checked = rule.enabled && !rule.orphaned,
                enabled = !rule.orphaned,
                onCheckedChange = onToggled,
            )
        }
    }
}

@Composable
private fun AutomaticRuleRoutingDialog(
    providerName: String?,
    onDismiss: () -> Unit,
    onSwitchToManual: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.routing_rules_automatic_title),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                providerName?.let {
                    stringResource(R.string.routing_rules_automatic_provider_description, it)
                } ?: stringResource(R.string.routing_rules_automatic_selected_subscription_description),
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSwitchToManual,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.routing_switch_to_manual_mode))
                }
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.routing_leave_as_is))
                }
            }
        },
    )
}

@Composable
private fun EditRoutingRuleDialog(
    rule: RoutingRule,
    onDismiss: () -> Unit,
    onSave: (RoutingRule) -> Unit,
) {
    var name by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(rule.name))
    }
    var domains by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(rule.domains.joinToString(", ")))
    }
    var ips by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(rule.ips.joinToString(", ")))
    }
    var port by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(rule.port.orEmpty()))
    }
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
    val scrollState = rememberScrollState()

    fun editedRule(): RoutingRule = rule.copy(
        name = name.text.trim().ifEmpty { rule.name },
        outboundTag = selectedOutbound,
        domains = splitCsv(domains.text),
        ips = splitCsv(ips.text),
        port = port.text.trim().ifEmpty { null },
        protocols = availableProtocolOptions.filter { it in selectedProtocols },
        operator = selectedOperator,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val edited = editedRule()
                    if (edited.matchesAllTraffic()) {
                        pendingCatchAllRule = edited
                    } else {
                        onSave(edited)
                    }
                },
            ) {
                Text(stringResource(R.string.routing_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.routing_cancel))
            }
        },
        title = { Text(stringResource(R.string.routing_edit_rule_title)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(maxHeight = 520.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.routing_name_label)) },
                        singleLine = true,
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
                        value = domains,
                        onValueChange = { domains = it },
                        label = { Text(stringResource(R.string.routing_domains_label)) },
                        supportingText = { Text(stringResource(R.string.routing_domains_supporting_text)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ips,
                        onValueChange = { ips = it },
                        label = { Text(stringResource(R.string.routing_ips_label)) },
                        supportingText = { Text(stringResource(R.string.routing_ips_supporting_text)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
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
                }
                DialogScrollbar(
                    scrollValue = scrollState.value,
                    maxScrollValue = scrollState.maxValue,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp),
                )
            }
        },
    )

    pendingCatchAllRule?.let { candidate ->
        CatchAllRuleWarningDialog(
            outbound = XrayOutbound.fromTag(candidate.outboundTag),
            onKeepEditing = { pendingCatchAllRule = null },
            onSaveAnyway = {
                pendingCatchAllRule = null
                onSave(candidate)
            },
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

@Composable
private fun DialogScrollbar(
    scrollValue: Int,
    maxScrollValue: Int,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)

    Canvas(modifier = modifier) {
        val radius = size.width / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(radius, radius),
        )
        if (maxScrollValue <= 0 || size.height <= 0f) return@Canvas

        val contentHeight = size.height + maxScrollValue
        val thumbHeight = (size.height * size.height / contentHeight).coerceAtLeast(32.dp.toPx())
        val thumbOffset = (scrollValue / maxScrollValue.toFloat()) * (size.height - thumbHeight)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, thumbOffset),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

@Composable
private fun routingRuleContentText(rule: RoutingRule): String {
    val domains = rule.domains.map(String::trim).filter(String::isNotEmpty)
    val ips = rule.ips.map(String::trim).filter(String::isNotEmpty)
    val protocols = rule.protocols.map(String::trim).filter(String::isNotEmpty)
    val domainText = compactDomainText(domains)
    val ipText = compactListText(ips, R.plurals.routing_rule_ips, R.plurals.routing_rule_more_ips)
    val portText = rule.port?.takeIf(String::isNotBlank)?.let {
        stringResource(R.string.routing_rule_port, it)
    }
    val protocolText = compactListText(
        protocols,
        R.plurals.routing_rule_protocols,
        R.plurals.routing_rule_more_protocols,
    )
    return listOfNotNull(domainText, ipText, portText, protocolText)
        .joinToString("\n")
        .ifBlank { stringResource(R.string.routing_no_match_content) }
}

@Composable
private fun profileRoutingRuleContentText(rule: ProfileRoutingRule): String {
    val domainText = compactDomainText(rule.domains)
    val ipText = compactListText(rule.ips, R.plurals.routing_rule_ips, R.plurals.routing_rule_more_ips)
    val portText = rule.port?.let { stringResource(R.string.routing_rule_port, it) }
    val protocolText = compactListText(
        rule.protocols,
        R.plurals.routing_rule_protocols,
        R.plurals.routing_rule_more_protocols,
    )
    val additionalText = rule.additionalConditionFields.takeIf(List<String>::isNotEmpty)?.let {
        stringResource(R.string.routing_rule_additional_conditions, it.joinToString(", "))
    }
    return listOfNotNull(domainText, ipText, portText, protocolText, additionalText)
        .joinToString("\n")
        .ifBlank { stringResource(R.string.routing_no_match_content) }
}

@Composable
private fun compactDomainText(domains: List<String>): String? {
    if (domains.isEmpty()) return null
    val preview = routingDomainPreview(domains)
    val visibleText = preview.visibleValues.joinToString(", ")
    val domainList = if (preview.omittedCount > 0) {
        pluralStringResource(
            R.plurals.routing_rule_more_domains,
            preview.omittedCount,
            visibleText,
            preview.omittedCount,
        )
    } else {
        visibleText
    }
    return pluralStringResource(R.plurals.routing_rule_domains, domains.size, domainList)
}

@Composable
private fun compactListText(
    values: List<String>,
    @PluralsRes labelResource: Int,
    @PluralsRes moreResource: Int,
): String? {
    val preview = routingListPreview(values)
    if (preview.visibleValues.isEmpty()) return null
    val visibleText = preview.visibleValues.joinToString(", ")
    val valueList = if (preview.omittedCount > 0) {
        pluralStringResource(moreResource, preview.omittedCount, visibleText, preview.omittedCount)
    } else {
        visibleText
    }
    return pluralStringResource(labelResource, values.size, valueList)
}

private fun RoutingRule.toViewerRequest(): RoutingRuleViewerRequest = RoutingRuleViewerRequest(
    name = name,
    domains = domains,
    ips = ips,
    port = port,
    protocols = protocols,
    targetKind = RoutingRuleViewerTargetKind.Outbound,
    targetTag = outboundTag,
)

private fun ProfileRoutingRule.toViewerRequest(): RoutingRuleViewerRequest = RoutingRuleViewerRequest(
    name = name,
    domains = domains,
    ips = ips,
    port = port,
    protocols = protocols,
    targetKind = when (target) {
        is ProfileRoutingTarget.Outbound -> RoutingRuleViewerTargetKind.Outbound
        is ProfileRoutingTarget.Balancer -> RoutingRuleViewerTargetKind.Balancer
        null -> null
    },
    targetTag = target?.tag,
    additionalConditionFields = additionalConditionFields,
    rawJson = rawJson,
)

@Composable
private fun ProfileRoutingTarget.displayText(): String = when (this) {
    is ProfileRoutingTarget.Outbound -> stringResource(R.string.routing_rule_target_outbound, tag)
    is ProfileRoutingTarget.Balancer -> stringResource(R.string.routing_rule_target_balancer, tag)
}

@Composable
private fun routingRuleDisplayName(rule: RoutingRule): String {
    if (rule.name != defaultRoutingRulesById[rule.id]?.name) return rule.name
    return when (rule.id) {
        "ru-direct" -> stringResource(R.string.routing_default_rule_ru_name)
        "block-ads" -> stringResource(R.string.routing_default_rule_block_ads_name)
        else -> rule.name
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

private fun splitCsv(value: String): List<String> = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
