package com.material.xray.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import com.material.xray.core.database.dao.AppBypassDao
import com.material.xray.core.database.dao.ServerDao
import com.material.xray.core.database.dao.SubscriptionDao
import com.material.xray.core.database.entity.AppBypassEntity
import com.material.xray.core.database.entity.DatabaseMetadataEntity
import com.material.xray.core.database.entity.ServerEntity
import com.material.xray.core.database.entity.SubscriptionEntity

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, AppBypassEntity::class, DatabaseMetadataEntity::class],
    version = 23,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun appBypassDao(): AppBypassDao

    companion object {
        const val DATABASE_NAME = "material-xray.db"

        val VALUE_VALIDATION_CALLBACK = object : Callback() {
            override fun onOpen(connection: SQLiteConnection) {
                DatabaseValueValidator.validateIfNeeded(connection)
            }
        }
    }
}
