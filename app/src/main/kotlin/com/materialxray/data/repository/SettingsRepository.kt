package com.materialxray.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.dataStore

    companion object {
        val TUN_NAME = stringPreferencesKey("tun_name")
        val DNS_SERVERS = stringPreferencesKey("dns_servers")
        val FWMARK = intPreferencesKey("fwmark")
        val ROUTE_TABLE = intPreferencesKey("route_table")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val LAST_SERVER_ID = longPreferencesKey("last_server_id")
    }

    val tunName: Flow<String> = store.data.map { it[TUN_NAME] ?: "xray0" }
    val dnsServers: Flow<String> = store.data.map { it[DNS_SERVERS] ?: "1.1.1.1,8.8.8.8" }
    val fwmark: Flow<Int> = store.data.map { it[FWMARK] ?: 255 }
    val routeTable: Flow<Int> = store.data.map { it[ROUTE_TABLE] ?: 100 }
    val autoConnect: Flow<Boolean> = store.data.map { it[AUTO_CONNECT] ?: false }
    val lastServerId: Flow<Long> = store.data.map { it[LAST_SERVER_ID] ?: -1L }

    suspend fun setTunName(name: String) = store.edit { it[TUN_NAME] = name }
    suspend fun setDnsServers(servers: String) = store.edit { it[DNS_SERVERS] = servers }
    suspend fun setFwmark(mark: Int) = store.edit { it[FWMARK] = mark }
    suspend fun setRouteTable(table: Int) = store.edit { it[ROUTE_TABLE] = table }
    suspend fun setAutoConnect(enabled: Boolean) = store.edit { it[AUTO_CONNECT] = enabled }
    suspend fun setLastServerId(id: Long) = store.edit { it[LAST_SERVER_ID] = id }

    suspend fun getAllAsMap(): Map<String, String> {
        val prefs = store.data.first()
        return prefs.asMap().entries.associate { (k, v) -> k.name to v.toString() }
    }

    suspend fun restoreFromMap(map: Map<String, String>) {
        store.edit { prefs ->
            prefs.clear()
            map["tun_name"]?.let { prefs[TUN_NAME] = it }
            map["dns_servers"]?.let { prefs[DNS_SERVERS] = it }
            map["fwmark"]?.let { prefs[FWMARK] = it.toIntOrNull() ?: 255 }
            map["route_table"]?.let { prefs[ROUTE_TABLE] = it.toIntOrNull() ?: 100 }
            map["auto_connect"]?.let { prefs[AUTO_CONNECT] = it.toBooleanStrictOrNull() ?: false }
            map["last_server_id"]?.let { prefs[LAST_SERVER_ID] = it.toLongOrNull() ?: -1L }
        }
    }
}
