package com.material.xray.data.repository

import android.content.Context
import android.content.ContextWrapper
import com.material.xray.model.ThemePreset
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryThemeSettingsTest {
    @Test
    fun `theme settings survive an export and import round trip`() = runTest {
        withTestRepository { repository ->
            repository.setThemePreset(ThemePreset.Indigo)
            repository.setOledDark(true)

            val exported = repository.getAllAsMap()
            assertEquals(ThemePreset.Indigo.value, exported[SettingsRepository.THEME_PRESET.name])
            assertEquals("true", exported[SettingsRepository.OLED_DARK.name])

            repository.restoreFromMap(emptyMap())
            assertEquals(ThemePreset.Dynamic, repository.themePreset.first())
            assertFalse(repository.oledDark.first())

            repository.restoreFromMap(exported)
            assertEquals(ThemePreset.Indigo, repository.themePreset.first())
            assertTrue(repository.oledDark.first())
        }
    }

    @Test
    fun `importing a backup without theme settings uses defaults`() = runTest {
        withTestRepository { repository ->
            repository.restoreFromMap(emptyMap())

            val snapshot = repository.settingsSnapshot.first()
            assertEquals(ThemePreset.Dynamic, snapshot.themePreset)
            assertFalse(snapshot.oledDark)
        }
    }

    /**
     * The repository binds to a process-wide DataStore delegate, so every repository in this class
     * must live under one root that outlives all tests: a fresh directory per test would leave the
     * singleton store pointing at a directory deleted by the previous test.
     */
    private companion object {
        val root: File = createRootDirectory()

        @AfterClass
        @JvmStatic
        fun tearDown() {
            root.deleteRecursively()
        }

        fun withTestRepository(block: suspend (SettingsRepository) -> Unit) {
            runTest { block(SettingsRepository(TestContext(root))) }
        }

        fun createRootDirectory(): File {
            val buildDirectory = File("build").apply { check(mkdirs() || isDirectory) }
            return Files.createTempDirectory(buildDirectory.toPath(), "settings-theme-test-").toFile()
        }
    }
}

private class TestContext(root: File) : ContextWrapper(null) {
    private val filesDirectory = root.resolve("files").apply { mkdirs() }
    private val noBackupDirectory = root.resolve("no-backup").apply { mkdirs() }

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = filesDirectory

    override fun getNoBackupFilesDir(): File = noBackupDirectory
}
