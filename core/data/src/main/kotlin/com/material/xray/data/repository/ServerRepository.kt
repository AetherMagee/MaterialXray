package com.material.xray.data.repository

import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Singleton

@Singleton
class ServerRepository(
    private val serverDao: ServerDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<ServerEntity>> = serverDao.observeAll()

    fun observeBySubscription(subId: Long): Flow<List<ServerEntity>> = serverDao.observeBySubscription(subId)

    suspend fun getById(id: Long): ServerEntity? = serverDao.getById(id)

    fun parseConfig(entity: ServerEntity): ServerConfig = json.decodeFromString(entity.configJson)

    suspend fun updateLatency(id: Long, latencyMs: Int) {
        serverDao.updateLatency(id, latencyMs)
    }

    /** Persists a locally edited config and marks the server as edited. */
    suspend fun saveEditedConfig(id: Long, config: ServerConfig) {
        serverDao.updateEditedConfig(
            id = id,
            name = config.name,
            protocol = config.protocol.name,
            address = config.address,
            port = config.port,
            configJson = json.encodeToString(ServerConfig.serializer(), config),
        )
    }

    suspend fun setGuarded(id: Long, guarded: Boolean) {
        serverDao.updateGuarded(id, guarded)
    }

    suspend fun updateSortOrders(changes: Map<Long, Int>) {
        serverDao.updateSortOrders(changes)
    }
}
