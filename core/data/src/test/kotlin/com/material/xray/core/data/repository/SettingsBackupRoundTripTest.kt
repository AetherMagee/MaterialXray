package com.material.xray.core.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.material.xray.core.model.BackupData
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsBackupRoundTripTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy {
        PreferenceDataStores.settings { File(folder.root, "settings.preferences_pb") }
    }
    private val repository by lazy { SettingsRepository(store) { } }

    @Test
    fun `every settings key survives an export and import`() = runBlocking {
        // Companion properties compile to static fields of the outer class.
        val keys = SettingsRepository::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Preferences.Key::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
            .map { it.get(null) as Preferences.Key<*> }
            .filterNot { it.name in MIGRATED_LEGACY_KEYS }
        assertTrue(keys.size > SAMPLES.size / 2)
        store.edit { prefs -> keys.forEach { prefs.putSample(it) } }

        val exported = repository.getAllAsMap()
        repository.restoreFromMap(exported, sourceBackupVersion = BackupData.CURRENT_VERSION)

        assertEquals(keys.map { it.name }.sorted(), repository.getAllAsMap().keys.filter { key -> keys.any { it.name == key } }.sorted())
    }

    @Test
    fun `importing an older backup keeps the current diagnostics choice`() = runBlocking {
        store.edit { it[SettingsRepository.DIAGNOSTICS_ENABLED] = false }

        repository.restoreFromMap(mapOf("tun_name" to "tun9"), sourceBackupVersion = BackupData.SPARSE_SETTINGS_VERSION)

        assertEquals(false, store.data.first()[SettingsRepository.DIAGNOSTICS_ENABLED])
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.putSample(key: Preferences.Key<*>) {
        val sample = requireNotNull(SAMPLES[key.name]) {
            "Add a sample for ${key.name} here and restore it in SettingsRepository.restoreFromMap"
        }
        this[key as Preferences.Key<Any>] = sample
    }

    private companion object {
        /** Converted into other keys by the import, so they cannot round-trip by name. */
        val MIGRATED_LEGACY_KEYS = setOf("allow_ipv6", "last_xray_log_level", "geo_data_base_url")

        val SAMPLES: Map<String, Any> = mapOf(
            "tun_name" to "tun9",
            "dns_servers" to "9.9.9.9",
            "domestic_dns_servers" to "8.8.8.8",
            "prefer_profile_dns" to false,
            "fwmark" to 77,
            "route_table" to 77,
            "xray_buffer_size_kib" to 1024,
            "tun_mtu" to 1400,
            "xray_memory_restart_threshold_mib" to 256,
            "passive_health_monitoring_enabled" to false,
            "auto_connect" to true,
            "bypass_lan" to false,
            "tunnel_tethered_clients" to true,
            "other_vpn_mode" to "auto",
            "ipv6_mode" to "off",
            "last_server_id" to 7L,
            "geoip_url" to "https://example.com/geoip.dat",
            "geosite_url" to "https://example.com/geosite.dat",
            "geo_data_update_interval_hours" to 48,
            "latency_check_url" to "https://example.com/204",
            "default_ping_method" to "httping",
            "sort_outbounds_by_latency" to true,
            "show_both_latency_results" to true,
            "xray_log_level" to "debug",
            "default_outbound" to "direct",
            "launcher_icon" to "default",
            "show_title_bar_logo" to false,
            "floating_connect_button" to true,
            "show_advanced_options" to true,
            "route_mxray_traffic_through_xray" to false,
            "app_specific_server_note_shown" to true,
            "routing_policy_control" to "user",
            "routing_rules" to "[]",
            "routing_rules_version" to 2,
            "routing_rule_states" to "{}",
            "deleted_default_routing_rule_ids" to setOf("ads"),
            "routing_domain_strategy" to "AsIs",
            "routing_domain_matcher" to "hybrid",
            "routing_fallback_outbound" to "direct",
            "provider_routing_rules" to "[]",
            "provider_routing_rules_version" to 2,
            "provider_routing_domain_strategy" to "AsIs",
            "provider_routing_domain_matcher" to "linear",
            "provider_routing_fallback_outbound" to "direct",
            "use_root_service" to true,
            "root_connection_backend" to "tproxy",
            "notification_update_interval_ms" to 2000,
            "notification_style" to "Compact",
            "notification_show_traffic_speed" to false,
            "notification_show_ram_usage" to true,
            "notification_show_connection_count" to true,
            "notification_show_ping" to true,
            "notification_show_session_traffic" to true,
            "notification_field_order" to "PING",
            "subscription_send_hwid" to false,
            "subscription_prefer_json" to true,
            "app_update_checks_enabled" to false,
            "app_update_interval_hours" to 24,
            "diagnostics_notice_shown" to true,
            "diagnostics_enabled" to false,
        )
    }
}
