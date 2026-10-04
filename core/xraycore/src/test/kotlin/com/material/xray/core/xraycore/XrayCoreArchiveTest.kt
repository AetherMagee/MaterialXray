package com.material.xray.core.xraycore

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayCoreArchiveTest {
    @Test
    fun `falls through to the next URL until a download matches the checksum`() = withDir { dir ->
        val good = "official".toByteArray()
        val requested = mutableListOf<String>()
        val client = client { url ->
            requested += url
            when (url) {
                "https://mirror-a/asset" -> null
                "https://mirror-b/asset" -> "tampered".toByteArray()
                else -> good
            }
        }
        val destination = File(dir, "archive.zip")

        runTest {
            downloadVerified(client, listOf("https://mirror-a/asset", "https://mirror-b/asset", "https://github.com/asset"), sha256(good), destination) { _, _ -> }
        }

        assertEquals(listOf("https://mirror-a/asset", "https://mirror-b/asset", "https://github.com/asset"), requested)
        assertArrayEquals(good, destination.readBytes())
    }

    @Test
    fun `reports an integrity failure when no download matches`() = withDir { dir ->
        val destination = File(dir, "archive.zip")
        val error = assertThrows(XrayCoreException::class.java) {
            runTest {
                downloadVerified(client { "tampered".toByteArray() }, listOf("https://github.com/asset"), sha256("official".toByteArray()), destination) { _, _ -> }
            }
        }

        assertEquals(XrayCoreFailure.Integrity, error.failure)
        assertFalse(destination.exists())
    }

    @Test
    fun `reports a network failure when nothing answers`() = withDir { dir ->
        val error = assertThrows(XrayCoreException::class.java) {
            runTest {
                downloadVerified(client { null }, listOf("https://github.com/asset"), sha256(byteArrayOf()), File(dir, "archive.zip")) { _, _ -> }
            }
        }

        assertEquals(XrayCoreFailure.Network, error.failure)
    }

    @Test
    fun `extracts only the top-level xray executable`() = withDir { dir ->
        val archive = zip(dir, "geoip.dat" to "geo", "nested/xray" to "wrong", "xray" to "core")
        val executable = File(dir, "libxray.so")

        val sha256 = runTestReturning { extractXrayExecutable(archive, executable) }

        assertTrue(isZip(archive))
        assertFalse(isZip(executable))
        assertEquals("core", executable.readText())
        assertEquals(sha256("core".toByteArray()), sha256)
    }

    @Test
    fun `a zip without the executable is unsupported`() = withDir { dir ->
        val archive = zip(dir, "nested/xray" to "core")

        val error = assertThrows(XrayCoreException::class.java) {
            runTest { extractXrayExecutable(archive, File(dir, "libxray.so")) }
        }

        assertEquals(XrayCoreFailure.Unsupported, error.failure)
    }

    private fun client(body: (String) -> ByteArray?) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val bytes = body(request.url.toString())
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (bytes == null) 502 else 200)
                .message("")
                .body((bytes ?: byteArrayOf()).toResponseBody())
                .build()
        }
        .build()

    private fun zip(dir: File, vararg entries: Pair<String, String>) = File(dir, "release.zip").apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun <T> runTestReturning(block: suspend () -> T): T {
        var result: T? = null
        runTest { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("xray-core-archive-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
