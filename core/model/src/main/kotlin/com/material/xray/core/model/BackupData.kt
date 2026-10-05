package com.material.xray.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BackupData(
    // Version 2 was omitted from older JSON because it was the default.
    val version: Int = 2,
    val subscriptions: List<BackupSubscription>,
    val servers: List<BackupServer> = emptyList(),
    val bypassedApps: List<String>,
    val settings: Map<String, String>,
    val appRoutes: List<BackupAppRoute> = emptyList(),
    val selectedServerKey: String? = null,
    /** The hand-edited runtime config of the selected server, if one was saved. */
    val activeConfigOverride: String? = null,
    /** State owned by optional modules, keyed by the module's section name. */
    val sections: Map<String, JsonElement> = emptyMap(),
) {
    @Serializable
    data class BackupSubscription(
        val key: String? = null,
        val name: String,
        val url: String,
        val preferJson: Boolean? = null,
        val autoUpdateIntervalHours: Int = 1,
        val descriptionHidden: Boolean = false,
        val userAgentMode: String? = null,
        val customUserAgent: String? = null,
        val customHeaders: String? = null,
        val allowInsecureUpdates: Boolean = false,
        val lastUpdated: Long = 0,
        val lastAutoRefreshFailureAt: Long = 0,
        val metadata: SubscriptionMetadata? = null,
        val appRouting: SubscriptionAppRouting? = null,
        val routing: SubscriptionRouting? = null,
    )

    @Serializable
    data class BackupServer(
        val key: String? = null,
        val subscriptionKey: String? = null,
        val subscriptionUrl: String?,
        val config: ServerConfig,
        val edited: Boolean = false,
        val guarded: Boolean = false,
    )

    @Serializable
    data class BackupAppRoute(
        val packageName: String,
        val profileId: Int,
        val mode: String,
        val serverKey: String? = null,
        val manual: Boolean = true,
        val alwaysProxied: Boolean = false,
    )

    companion object {
        const val STABLE_RELATIONSHIP_KEYS_VERSION = 3
        const val APP_ROUTES_VERSION = 3
        const val SPARSE_SETTINGS_VERSION = 4
        const val COMPLETE_STATE_VERSION = 5
        const val CURRENT_VERSION = COMPLETE_STATE_VERSION
    }
}
