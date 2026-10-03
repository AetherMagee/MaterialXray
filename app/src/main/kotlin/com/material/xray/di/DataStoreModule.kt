package com.material.xray.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.material.xray.data.repository.AppUpdateDataStore
import com.material.xray.data.repository.PreferenceDataStores
import com.material.xray.data.repository.SettingsDataStore
import java.io.File
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

/**
 * Opens the preference stores in the files the androidx `preferencesDataStore(name)` delegate
 * used before (`Context.preferencesDataStoreFile(name)`), so existing settings are read unchanged.
 */
@Module
class DataStoreModule {

    @Singleton
    @SettingsDataStore
    fun settingsDataStore(context: Context): DataStore<Preferences> = PreferenceDataStores.settings {
        context.preferencesDataStoreFile(PreferenceDataStores.SETTINGS)
    }

    @Singleton
    @AppUpdateDataStore
    fun appUpdateDataStore(context: Context): DataStore<Preferences> = PreferenceDataStores.appUpdate {
        context.preferencesDataStoreFile(PreferenceDataStores.APP_UPDATE)
    }
}

private fun Context.preferencesDataStoreFile(name: String): File = File(applicationContext.filesDir, "datastore/${PreferenceDataStores.fileName(name)}")
