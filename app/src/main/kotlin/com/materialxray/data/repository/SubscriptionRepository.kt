package com.materialxray.data.repository

import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.db.entity.SubscriptionEntity
import com.materialxray.data.parser.SubscriptionFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val fetcher: SubscriptionFetcher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll()

    suspend fun add(name: String, url: String): Long {
        val id = subscriptionDao.insert(SubscriptionEntity(name = name, url = url))
        refresh(id, url)
        return id
    }

    suspend fun refresh(subId: Long, url: String) {
        val configs = fetcher.fetch(url)
        serverDao.deleteBySubscription(subId)
        serverDao.insertAll(configs.mapIndexed { index, config ->
            ServerEntity(
                subscriptionId = subId,
                name = config.name,
                protocol = config.protocol.name,
                address = config.address,
                port = config.port,
                configJson = json.encodeToString(config),
                sortOrder = index,
            )
        })
        subscriptionDao.getById(subId)?.let {
            subscriptionDao.update(it.copy(lastUpdated = System.currentTimeMillis()))
        }
    }

    suspend fun refreshAll() {
        subscriptionDao.getAll().forEach { sub ->
            runCatching { refresh(sub.id, sub.url) }
        }
    }

    suspend fun delete(sub: SubscriptionEntity) {
        subscriptionDao.delete(sub)
    }
}
