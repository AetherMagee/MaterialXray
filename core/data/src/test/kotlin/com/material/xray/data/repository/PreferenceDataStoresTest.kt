package com.material.xray.data.repository

import androidx.datastore.preferences.core.edit
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferenceDataStoresTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `settings store applies the defaults migration and writes the given file`() = runBlocking {
        val file = File(folder.root, "datastore/${PreferenceDataStores.fileName(PreferenceDataStores.SETTINGS)}")
        val store = PreferenceDataStores.settings { file }

        assertEquals(CURRENT_SETTINGS_DEFAULTS_REVISION, store.data.first()[SETTINGS_DEFAULTS_REVISION])
        store.edit { it[SettingsRepository.TUN_NAME] = "tun9" }

        assertTrue(file.isFile)
        assertEquals("settings.preferences_pb", file.name)
    }

    @Test
    fun `app update store has no migrations`() = runBlocking {
        val store = PreferenceDataStores.appUpdate { File(folder.root, "app_update.preferences_pb") }

        assertTrue(store.data.first().asMap().isEmpty())
    }
}
