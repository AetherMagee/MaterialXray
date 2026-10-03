package com.material.xray.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.material.xray.core.navigation.ConfigViewerKey
import com.material.xray.core.navigation.ConfigViewerTarget
import com.material.xray.core.navigation.HomeKey
import com.material.xray.core.navigation.LogsKey
import com.material.xray.core.navigation.Navigator
import com.material.xray.core.navigation.RoutingKey
import com.material.xray.core.navigation.RoutingRuleEditorKey
import com.material.xray.core.navigation.RoutingRuleViewerKey
import com.material.xray.core.navigation.SettingsKey
import com.material.xray.data.repository.SettingsSnapshot
import com.material.xray.ui.configviewer.ConfigViewerRequest
import com.material.xray.ui.configviewer.ConfigViewerScreen
import com.material.xray.ui.home.HomeScreen
import com.material.xray.ui.logs.LogsScreen
import com.material.xray.ui.routing.EditableRoutingRule
import com.material.xray.ui.routing.RoutingRuleEditorScreen
import com.material.xray.ui.routing.RoutingRuleViewerRequest
import com.material.xray.ui.routing.RoutingRuleViewerScreen
import com.material.xray.ui.routing.RoutingScreen
import com.material.xray.ui.routing.RoutingViewModel
import com.material.xray.ui.settings.SettingsScreen
import kotlinx.serialization.json.Json
import org.koin.compose.viewmodel.koinViewModel

/**
 * Every destination's entry. `NavDisplay` keeps an entry once it is created, so anything that changes
 * while the entry is on screen is read through a [State] rather than captured.
 */
internal fun appEntryProvider(
    navigator: Navigator,
    chrome: State<TabChromeState>,
    settings: State<SettingsSnapshot>,
    pendingSubscriptionLink: State<String?>,
    onSubscriptionLinkHandled: State<() -> Unit>,
    addSubscriptionFocusRequester: FocusRequester,
): (NavKey) -> NavEntry<NavKey> = entryProvider {
    entry<HomeKey> {
        TabChrome(chrome.value) {
            HomeScreen(
                showTitleBarLogo = settings.value.showTitleBarLogo,
                floatingConnectButton = settings.value.floatingConnectButton,
                pendingSubscriptionLink = pendingSubscriptionLink.value,
                onSubscriptionLinkHandled = { onSubscriptionLinkHandled.value() },
                onOpenServerConfig = { serverId, name ->
                    navigator.openDetail(ConfigViewerKey(ConfigViewerTarget.Server(serverId, name)))
                },
                onViewRunningConfig = { navigator.openDetail(ConfigViewerKey(ConfigViewerTarget.Running)) },
                addSubscriptionFocusRequester = addSubscriptionFocusRequester,
            )
        }
    }
    entry<RoutingKey> {
        TabChrome(chrome.value) {
            RoutingScreen(
                showTitleBarLogo = settings.value.showTitleBarLogo,
                onViewRule = { request -> navigator.openDetail(RoutingRuleViewerKey(Json.encodeToString(request))) },
                onEditRule = { rule -> navigator.openDetail(RoutingRuleEditorKey(Json.encodeToString(rule))) },
                viewModel = activityRoutingViewModel(),
            )
        }
    }
    entry<LogsKey> {
        TabChrome(chrome.value) { LogsScreen(settings.value.showTitleBarLogo) }
    }
    entry<SettingsKey> {
        TabChrome(chrome.value) { SettingsScreen(settings.value.showTitleBarLogo) }
    }
    entry<ConfigViewerKey> { key ->
        val request = remember(key) { key.request.toConfigViewerRequest() }
        ConfigViewerScreen(request = request, onBack = navigator::closeDetail)
    }
    entry<RoutingRuleViewerKey> { key ->
        val request = remember(key) { Json.decodeFromString<RoutingRuleViewerRequest>(key.payload) }
        RoutingRuleViewerScreen(request = request, onBack = navigator::closeDetail)
    }
    entry<RoutingRuleEditorKey> { key ->
        val rule = remember(key) { Json.decodeFromString<EditableRoutingRule>(key.payload) }
        RoutingRuleEditorScreen(editableRule = rule, viewModel = activityRoutingViewModel(), onBack = navigator::closeDetail)
    }
}

/**
 * The Routing tab's view model, scoped to the Activity rather than to an entry, so the rule editor
 * (an entry of its own) edits through the same instance the tab shows.
 */
@Composable
private fun activityRoutingViewModel(): RoutingViewModel = koinViewModel(viewModelStoreOwner = requireNotNull(LocalActivity.current as? ViewModelStoreOwner))

private fun ConfigViewerTarget.toConfigViewerRequest(): ConfigViewerRequest = when (this) {
    ConfigViewerTarget.Running -> ConfigViewerRequest.Running
    is ConfigViewerTarget.Server -> ConfigViewerRequest.Server(serverId, name)
}
