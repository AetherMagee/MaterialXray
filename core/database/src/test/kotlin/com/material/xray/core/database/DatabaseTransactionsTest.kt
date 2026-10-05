package com.material.xray.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.material.xray.core.database.entity.ServerEntity
import com.material.xray.core.database.entity.SubscriptionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseTransactionsTest {
    private val database = Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .addCallback(AppDatabase.VALUE_VALIDATION_CALLBACK)
        .build()

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun writeTransactionCommitsDaoCallsTogether() = runTest {
        val id = database.withWriteTransaction {
            database.subscriptionDao().insert(SubscriptionEntity(name = "First", url = "https://example.com"))
                .also { database.serverDao().insertAll(listOf(server(it))) }
        }

        assertEquals(listOf(id), database.subscriptionDao().getAll().map { it.id })
        assertEquals(1, database.serverDao().getBySubscription(id).size)
    }

    @Test
    fun writeTransactionRollsBackDaoCallsWhenTheBlockThrows() = runTest {
        val failure = runCatching {
            database.withWriteTransaction {
                database.subscriptionDao().insert(SubscriptionEntity(name = "Lost", url = "https://example.com"))
                error("abort")
            }
        }.exceptionOrNull()

        assertEquals("abort", failure?.message)
        assertTrue(database.subscriptionDao().getAll().isEmpty())
    }

    private fun server(subscriptionId: Long) = ServerEntity(
        subscriptionId = subscriptionId,
        name = "Server",
        protocol = "VLESS",
        address = "example.com",
        port = 443,
        configJson = "{}",
        sortOrder = 0,
    )
}
