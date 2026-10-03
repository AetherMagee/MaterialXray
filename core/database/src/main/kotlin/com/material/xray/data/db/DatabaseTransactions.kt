package com.material.xray.data.db

import androidx.room.RoomDatabase
import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Runs [block] in one immediate transaction on the writer connection, committing when it returns
 * and rolling back when it throws.
 *
 * The connection is confined to the calling coroutine, so suspending DAO calls made inside [block]
 * join the transaction. This is the driver-API replacement for room-ktx's Android-only
 * `withTransaction`.
 */
suspend fun <R> RoomDatabase.withWriteTransaction(block: suspend () -> R): R = useWriterConnection { transactor ->
    transactor.immediateTransaction { block() }
}

/**
 * Deletes every row of every table, like Android's `RoomDatabase.clearAllTables()`, which the
 * driver API has no counterpart for: the deletes run in one transaction with foreign keys
 * deferred, then a WAL checkpoint and `VACUUM` erase the freed pages. Room's own bookkeeping tables
 * keep their rows, so the schema identity survives.
 */
suspend fun RoomDatabase.deleteAllRows() {
    useWriterConnection { transactor ->
        transactor.immediateTransaction {
            val tables = usePrepared(USER_TABLES_QUERY) { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
            execSQL("PRAGMA defer_foreign_keys = TRUE")
            tables.forEach { table -> execSQL("DELETE FROM `$table`") }
        }
        transactor.execSQL("PRAGMA wal_checkpoint(FULL)")
        transactor.execSQL("VACUUM")
    }
}

private const val USER_TABLES_QUERY =
    "SELECT name FROM sqlite_master WHERE type = 'table' " +
        "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND name NOT IN ('room_master_table', 'android_metadata')"
