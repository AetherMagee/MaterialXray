package com.material.xray.service

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootCertificateBundleTest {
    private val directory: File = Files.createTempDirectory("root-ca-bundle-test").toFile()
    private val bundleFile = directory.resolve("ca-certificates.pem")

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a missing bundle is written as an atomic PEM file before the core starts`() = runTest {
        bundle(
            loadBundledCertificates = { listOf(byteArrayOf(4, 5, 6)) },
            loadCertificates = { listOf(byteArrayOf(1, 2, 3)) },
        ).prepare(bundleFile)

        assertEquals(
            """
            -----BEGIN CERTIFICATE-----
            AQID
            -----END CERTIFICATE-----
            -----BEGIN CERTIFICATE-----
            BAUG
            -----END CERTIFICATE-----

            """.trimIndent(),
            bundleFile.readText(),
        )
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a cached bundle is used immediately and refreshed once after the delay`() = runTest {
        bundleFile.writeText("stale")
        var loads = 0
        val bundle = bundle(
            loadCertificates = {
                loads++
                listOf(byteArrayOf(1, 2, 3))
            },
        )

        bundle.prepare(bundleFile)
        bundle.prepare(bundleFile)
        runCurrent()
        assertEquals(0, loads)
        assertEquals("stale", bundleFile.readText())

        advanceTimeBy(REFRESH_DELAY_MS + 1)
        runCurrent()
        bundle.prepare(bundleFile)
        runCurrent()

        assertEquals(1, loads)
        assertTrue(bundleFile.readText().contains("AQID"))
    }

    @Test
    fun `a bundle written in this process is not refreshed again`() = runTest {
        var loads = 0
        val bundle = bundle(
            loadCertificates = {
                loads++
                listOf(byteArrayOf(1, 2, 3))
            },
        )

        bundle.prepare(bundleFile)
        bundle.prepare(bundleFile)
        advanceTimeBy(REFRESH_DELAY_MS + 1)
        runCurrent()

        assertEquals(1, loads)
    }

    @Test
    fun `an unchanged bundle is not rewritten by the refresh`() = runTest {
        bundle(loadCertificates = { listOf(byteArrayOf(1, 2, 3)) }).prepare(bundleFile)
        val writtenAt = 1_000_000L
        bundleFile.setLastModified(writtenAt)

        bundle(loadCertificates = { listOf(byteArrayOf(1, 2, 3)) }).prepare(bundleFile)
        advanceTimeBy(REFRESH_DELAY_MS + 1)
        runCurrent()

        assertEquals(writtenAt, bundleFile.lastModified())
    }

    @Test
    fun `a failed refresh keeps the cached bundle`() = runTest {
        bundleFile.writeText("cached")

        bundle(loadCertificates = { error("store unavailable") }).prepare(bundleFile)
        advanceTimeBy(REFRESH_DELAY_MS + 1)
        runCurrent()

        assertEquals("cached", bundleFile.readText())
    }

    private fun TestScope.bundle(
        loadBundledCertificates: () -> List<ByteArray> = { emptyList() },
        loadCertificates: () -> List<ByteArray>,
    ) = AndroidRootCertificateBundle(
        refreshScope = this,
        refreshDelayMillis = REFRESH_DELAY_MS,
        loadBundledCertificates = loadBundledCertificates,
        loadCertificates = loadCertificates,
        // A real IO dispatcher would let runTest skip the refresh delay while prepare() waits on it.
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private companion object {
        const val REFRESH_DELAY_MS = 60_000L
    }
}
