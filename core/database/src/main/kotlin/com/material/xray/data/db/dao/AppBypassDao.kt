package com.material.xray.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.material.xray.data.db.entity.AppBypassEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppBypassDao {
    @Query("SELECT * FROM app_bypass ORDER BY profileId, packageName")
    fun observeAll(): Flow<List<AppBypassEntity>>

    @Query("SELECT * FROM app_bypass ORDER BY profileId, packageName")
    suspend fun getAll(): List<AppBypassEntity>

    @Upsert
    suspend fun upsert(entity: AppBypassEntity)

    @Query("UPDATE app_bypass SET serverId = :newServerId WHERE serverId = :oldServerId")
    suspend fun updateServerId(oldServerId: Long, newServerId: Long)

    @Query("DELETE FROM app_bypass")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AppBypassEntity>)

    /** Replaces the table with [merge] of its current rows, atomically; returns whether it changed. */
    @Transaction
    suspend fun replaceAllWith(merge: (List<AppBypassEntity>) -> List<AppBypassEntity>): Boolean {
        val current = getAll()
        val target = merge(current)
        if (current == target) return false
        deleteAll()
        insertAll(target)
        return true
    }
}
