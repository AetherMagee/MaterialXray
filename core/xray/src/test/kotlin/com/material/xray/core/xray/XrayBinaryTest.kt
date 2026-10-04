package com.material.xray.core.xray

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayBinaryTest {

    @Test
    fun `ensureAvailable finds the core and TUN launcher in the native library directory`() = withTempDir { dir ->
        val nativeDir = File(dir, "lib").apply { mkdirs() }
        val core = nativeExecutable(nativeDir, "libxray.so")
        val launcher = nativeExecutable(nativeDir, "libxrayroot.so")
        val xrayBinary = XrayBinary(FakeEnvironment(filesDir = dir, nativeLibraryDir = nativeDir))

        assertTrue(xrayBinary.ensureAvailable())

        assertEquals(core.absolutePath, xrayBinary.binaryPath)
        assertEquals(listOf(core.absolutePath), xrayBinary.userCommand)
        assertEquals(launcher.absolutePath, xrayBinary.rootLauncherPath)
        assertTrue(File(dir, "bin").isDirectory)
    }

    @Test
    fun `ensureAvailable fails without the TUN launcher`() = withTempDir { dir ->
        val nativeDir = File(dir, "lib").apply { mkdirs() }
        nativeExecutable(nativeDir, "libxray.so")

        assertFalse(XrayBinary(FakeEnvironment(filesDir = dir, nativeLibraryDir = nativeDir)).ensureAvailable())
    }

    @Test
    fun `ensureAvailable fails without a native library directory`() = withTempDir { dir ->
        val xrayBinary = XrayBinary(FakeEnvironment(filesDir = dir))

        assertFalse(xrayBinary.ensureAvailable())
        assertNull(xrayBinary.binaryPath)
    }

    @Test
    fun `ensureAvailable removes the binary older versions extracted for root mode`() = withTempDir { dir ->
        val binDir = File(dir, "bin").apply { mkdirs() }
        File(binDir, "xray").writeText("linux build")
        File(binDir, "version").writeText("1000")
        val geoip = File(binDir, "geoip.dat").apply { writeText("geo") }

        XrayBinary(FakeEnvironment(filesDir = dir)).ensureAvailable()

        assertFalse(File(binDir, "xray").exists())
        assertFalse(File(binDir, "version").exists())
        assertTrue(geoip.exists())
    }

    @Test
    fun `writeConfig writes config to files dir`() = withTempDir { dir ->
        val xrayBinary = XrayBinary(FakeEnvironment(filesDir = dir))

        xrayBinary.writeConfig("""{"log":{}}""")

        assertEquals(File(dir, "config.json").absolutePath, xrayBinary.configPath())
        assertEquals("""{"log":{}}""", File(dir, "config.json").readText())
        assertEquals("""{"log":{}}""", xrayBinary.readConfig())
    }

    @Test
    fun `readVersion returns version from bundled executable`() = withTempDir { dir ->
        val nativeDir = File(dir, "lib").apply { mkdirs() }
        File(nativeDir, "libxray.so").apply {
            writeText("#!/bin/sh\nprintf 'Xray 26.6.7 (Xray, Penetrates Everything.)\\n'")
            setExecutable(true, false)
        }
        val environment = FakeEnvironment(
            filesDir = dir,
            nativeLibraryDir = nativeDir,
        )

        assertEquals("26.6.7", XrayBinary(environment).readVersion())
    }

    @Test
    fun `parseXrayVersion returns null for unrecognized output`() {
        assertNull(parseXrayVersion("not xray"))
    }

    private class FakeEnvironment(
        override val filesDir: File,
        override val nativeLibraryDir: File? = null,
    ) : XrayPaths {
        override val cacheDir: File
            get() = filesDir
    }

    private fun nativeExecutable(dir: File, name: String) = File(dir, name).apply {
        writeText("native")
        setExecutable(true, false)
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("xray-binary-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
