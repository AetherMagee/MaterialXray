package com.material.xray.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Every destination in the app. Saved and restored through `NavKeySerializer`, so each key is `@Serializable`. */
sealed interface AppNavKey : NavKey

/** A tab: the root of its own back stack, shown in the navigation bar or rail. */
sealed interface TopLevelKey : AppNavKey

/**
 * A subpage pushed on top of the current tab's stack. Fills the window on a narrow one and opens as
 * an end-edge sheet over the tab on a wide one ([DetailSheetSceneStrategy]).
 */
sealed interface DetailKey : AppNavKey

@Serializable
data object HomeKey : TopLevelKey

@Serializable
data object RoutingKey : TopLevelKey

@Serializable
data object LogsKey : TopLevelKey

@Serializable
data object SettingsKey : TopLevelKey

/** Every tab in bar order. Logs is only shown with advanced options on; the caller filters it. */
val TopLevelKeys: List<TopLevelKey> = listOf(HomeKey, RoutingKey, LogsKey, SettingsKey)

@Serializable
data object DnsSettingsKey : DetailKey

@Serializable
data object XrayCoreSettingsKey : DetailKey

@Serializable
data class ConfigViewerKey(val request: ConfigViewerTarget) : DetailKey

/** Which config the viewer shows. */
@Serializable
sealed interface ConfigViewerTarget {
    /** The config file the running Xray core was actually started with. */
    @Serializable
    data object Running : ConfigViewerTarget

    /** The source config a saved server was parsed from. */
    @Serializable
    data class Server(val serverId: Long, val name: String) : ConfigViewerTarget
}

/**
 * The routing-rule viewer. [payload] is a JSON-encoded `RoutingRuleViewerRequest`: that type lives
 * in the routing feature, above this module, so the feature encodes and decodes it.
 */
@Serializable
data class RoutingRuleViewerKey(val payload: String) : DetailKey

/** The routing-rule editor. [payload] is a JSON-encoded `EditableRoutingRule`, as for [RoutingRuleViewerKey]. */
@Serializable
data class RoutingRuleEditorKey(val payload: String) : DetailKey
