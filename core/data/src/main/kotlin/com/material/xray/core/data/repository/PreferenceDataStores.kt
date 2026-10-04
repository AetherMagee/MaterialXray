package com.material.xray.core.data.repository

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.annotation.Qualifier

/** Qualifies the [DataStore] behind [SettingsRepository]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class SettingsDataStore

/** Qualifies the [DataStore] behind [AppUpdateRepository]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class AppUpdateDataStore

/**
 * Opens the preference stores the data layer keeps. The platform only chooses the file; the
 * migrations, corruption handling and scope stay the ones the stores were created with. On
 * Android the file is `<filesDir>/datastore/<name>.preferences_pb`, where the androidx
 * `preferencesDataStore(name)` delegate kept it.
 */
object PreferenceDataStores {
    const val SETTINGS = "settings"
    const val APP_UPDATE = "app_update"

    /** The file name a store called [name] uses. */
    fun fileName(name: String): String = "$name.preferences_pb"

    fun settings(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        produceFile: () -> File,
    ): DataStore<Preferences> = create(listOf(SettingsDefaultMigration()), ioDispatcher, produceFile)

    fun appUpdate(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        produceFile: () -> File,
    ): DataStore<Preferences> = create(emptyList(), ioDispatcher, produceFile)

    // The same arguments the preferencesDataStore delegate passes by default: no corruption
    // handler and a scope of its own on the IO dispatcher.
    private fun create(
        migrations: List<DataMigration<Preferences>>,
        ioDispatcher: CoroutineDispatcher,
        produceFile: () -> File,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = null,
        migrations = migrations,
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
        produceFile = produceFile,
    )
}
