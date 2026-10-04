package com.material.xray.core.xray

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayCoreStoreTest {
    @Test
    fun `committed core is listed and can be selected`() = withPaths { paths ->
        val store = XrayCoreStore(paths)
        val core = install(store, "v26.9.9")

        assertEquals(listOf(core), store.installed())
        assertNull(store.selectedExecutable())

        store.select("v26.9.9")

        assertEquals("v26.9.9", store.selectedId())
        assertEquals(File(paths.filesDir, "cores/v26.9.9/libxray.so"), store.selectedExecutable())
    }

    @Test
    fun `selecting the bundled core clears the selection`() = withPaths { paths ->
        val store = XrayCoreStore(paths)
        install(store, "v26.9.9")
        store.select("v26.9.9")

        store.select(null)

        assertNull(store.selectedId())
        assertNull(store.selectedExecutable())
    }

    @Test
    fun `the selected core cannot be deleted`() = withPaths { paths ->
        val store = XrayCoreStore(paths)
        install(store, "v26.9.9")
        store.select("v26.9.9")

        assertThrows(IllegalStateException::class.java) { store.delete("v26.9.9") }

        store.select(null)
        store.delete("v26.9.9")
        assertTrue(store.installed().isEmpty())
    }

    @Test
    fun `installing an id that is already installed keeps the existing copy`() = withPaths { paths ->
        val store = XrayCoreStore(paths)
        install(store, "v26.9.9")
        store.select("v26.9.9")
        val staging = store.createStagingDir()
        store.stagedExecutable(staging).writeText("other")

        store.commit(staging, InstalledXrayCore("v26.9.9", "26.9.9", "cd".repeat(32), XrayCoreSource.Release))

        assertFalse(staging.exists())
        assertEquals("core", File(paths.filesDir, "cores/v26.9.9/libxray.so").readText())
        assertEquals("ab".repeat(32), store.installed().single().sha256)
    }

    @Test
    fun `a selection whose core is gone falls back to the bundled core`() = withPaths { paths ->
        val store = XrayCoreStore(paths)
        install(store, "v26.9.9")
        store.select("v26.9.9")
        File(paths.filesDir, "cores/v26.9.9/libxray.so").delete()

        assertNull(store.selectedExecutable())
        assertTrue(store.installed().isEmpty())
    }

    @Test
    fun `directories without a matching manifest are not cores`() = withPaths { paths ->
        val store = XrayCoreStore(paths)
        val stray = File(paths.filesDir, "cores/v1.0.0").apply { mkdirs() }
        File(stray, "libxray.so").writeText("core")
        File(stray, "core.json").writeText("""{"id":"other","version":"1.0.0","sha256":"","source":"Release"}""")
        File(paths.filesDir, "cores/broken").apply { mkdirs() }.resolve("core.json").writeText("not json")

        assertTrue(store.installed().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.select("v1.0.0") }
    }

    @Test
    fun `abandoned staging directories are removed`() = withPaths { paths ->
        val store = XrayCoreStore(paths)
        val staging = store.createStagingDir()
        install(store, "v26.9.9")

        store.removeAbandonedStaging()

        assertFalse(staging.exists())
        assertEquals(1, store.installed().size)
    }

    @Test
    fun `core ids cannot escape the cores directory`() {
        assertTrue(isValidCoreId("v26.9.9"))
        assertTrue(isValidCoreId("file-0123456789ab"))
        assertFalse(isValidCoreId(".."))
        assertFalse(isValidCoreId(".staging-x"))
        assertFalse(isValidCoreId("a/b"))
        assertFalse(isValidCoreId(""))
    }

    @Test
    fun `XrayBinary runs the selected core through the system linker`() = withPaths { paths ->
        val store = XrayCoreStore(paths)
        install(store, "v26.9.9")
        store.select("v26.9.9")
        val core = File(paths.filesDir, "cores/v26.9.9/libxray.so").absolutePath

        val binary = XrayBinary(paths)

        assertEquals(core, binary.binaryPath)
        assertEquals(listOf(SYSTEM_LINKER_64, core), binary.userCommand)
    }

    private fun install(store: XrayCoreStore, id: String): InstalledXrayCore {
        val staging = store.createStagingDir()
        store.stagedExecutable(staging).writeText("core")
        return store.commit(staging, InstalledXrayCore(id, id.removePrefix("v"), "ab".repeat(32), XrayCoreSource.Release))
    }

    private fun withPaths(block: (XrayPaths) -> Unit) {
        val dir = Files.createTempDirectory("xray-core-store-test").toFile()
        try {
            block(
                object : XrayPaths {
                    override val filesDir = dir
                    override val cacheDir = dir
                    override val nativeLibraryDir: File? = null
                },
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
