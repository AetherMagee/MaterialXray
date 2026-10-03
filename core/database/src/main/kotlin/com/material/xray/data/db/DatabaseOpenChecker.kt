package com.material.xray.data.db

import androidx.room.useWriterConnection
import com.material.xray.core.common.log.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Singleton

@Singleton
class DatabaseOpenChecker(
    private val database: AppDatabase,
    private val logger: AppLogger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var result: Boolean? = null

    suspend fun canRead(): Boolean = mutex.withLock {
        result?.takeIf { it } ?: withContext(ioDispatcher) {
            try {
                // Opening runs Room migrations and schema validation; the query also verifies
                // that the main user-data table can be read before any screen uses it.
                database.useWriterConnection { connection ->
                    connection.usePrepared("SELECT 1 FROM servers LIMIT 1") { it.step() }
                }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Room reports schema and SQLite failures with different exception types.
                logger.e(LOG_TAG, "Unable to open app database", error)
                false
            }
        }.also { result = it }
    }

    private companion object {
        const val LOG_TAG = "DatabaseOpenChecker"
    }
}
