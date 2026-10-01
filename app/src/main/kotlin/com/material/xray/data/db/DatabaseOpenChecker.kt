package com.material.xray.data.db

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Singleton

@Singleton
class DatabaseOpenChecker(private val database: AppDatabase) {
    private val mutex = Mutex()
    private var result: Boolean? = null

    suspend fun canRead(): Boolean = mutex.withLock {
        result?.takeIf { it } ?: withContext(Dispatchers.IO) {
            try {
                // Opening runs Room migrations and schema validation; the query also verifies
                // that the main user-data table can be read before any screen uses it.
                database.openHelper.writableDatabase.query("SELECT 1 FROM servers LIMIT 1").use { it.moveToFirst() }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Room reports schema and SQLite failures with different exception types.
                Log.e(LOG_TAG, "Unable to open app database", error)
                false
            }
        }.also { result = it }
    }

    private companion object {
        const val LOG_TAG = "DatabaseOpenChecker"
    }
}
