package com.material.xray.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.material.xray.core.database.AppDatabase
import com.material.xray.core.database.DatabaseMigrations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

/**
 * Builds the [AppDatabase] on Android. `:core:database` is platform-free, so the file location
 * and the SQLite driver are chosen here.
 */
@Module
class DatabaseModule {

    // The platform SQLite engine (AndroidSQLiteDriver) and getDatabasePath keep opening the file
    // that the pre-driver Room.databaseBuilder(context, AppDatabase::class.java, name) used.
    @Singleton
    fun database(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): AppDatabase = Room.databaseBuilder<AppDatabase>(
        context = context,
        name = context.getDatabasePath(AppDatabase.DATABASE_NAME).absolutePath,
    )
        .setDriver(AndroidSQLiteDriver())
        .addMigrations(*DatabaseMigrations.all)
        .addCallback(AppDatabase.VALUE_VALIDATION_CALLBACK)
        .setQueryCoroutineContext(ioDispatcher)
        // An incompatible schema must fail without deleting subscriptions or routing data.
        .build()
}
