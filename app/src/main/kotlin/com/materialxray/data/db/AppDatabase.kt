package com.materialxray.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import com.materialxray.data.db.entity.AppBypassEntity
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.db.entity.SubscriptionEntity

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, AppBypassEntity::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun appBypassDao(): AppBypassDao
}
