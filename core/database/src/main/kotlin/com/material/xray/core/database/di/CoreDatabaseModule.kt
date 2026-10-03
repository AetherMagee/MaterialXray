package com.material.xray.core.database.di

import com.material.xray.data.db.AppDatabase
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

/**
 * The DAOs and the platform-free database helpers. The [AppDatabase] itself is built by the host,
 * which owns the file location and the SQLite driver.
 */
@Module
@ComponentScan("com.material.xray.data.db")
class CoreDatabaseModule {

    @Factory
    fun serverDao(db: AppDatabase): ServerDao = db.serverDao()

    @Factory
    fun subscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptionDao()

    @Factory
    fun appBypassDao(db: AppDatabase): AppBypassDao = db.appBypassDao()
}
