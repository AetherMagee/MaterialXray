package com.material.xray.core.database

import androidx.room.RoomDatabase
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
