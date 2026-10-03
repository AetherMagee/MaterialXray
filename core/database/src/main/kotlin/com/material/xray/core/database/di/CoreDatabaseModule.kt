package com.material.xray.core.database.di

import android.content.Context
import androidx.room.Room
import com.material.xray.data.db.AppDatabase
import com.material.xray.data.db.DatabaseMigrations
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@ComponentScan("com.material.xray.data.db")
class CoreDatabaseModule {

    @Singleton
    fun database(context: Context): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME,
    )
        .addMigrations(*DatabaseMigrations.all)
        .addCallback(AppDatabase.VALUE_VALIDATION_CALLBACK)
        // An incompatible schema must fail without deleting subscriptions or routing data.
        .build()

    @Factory
    fun serverDao(db: AppDatabase): ServerDao = db.serverDao()

    @Factory
    fun subscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptionDao()

    @Factory
    fun appBypassDao(db: AppDatabase): AppBypassDao = db.appBypassDao()
}
