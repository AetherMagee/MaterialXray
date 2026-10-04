package com.material.xray.core.runtime

import com.material.xray.core.xray.GEOIP_FILE_NAME
import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GeoDataManagerTest {
    @Test
    fun normalizeTrimsWhitespace() {
        val url = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"

        assertEquals(
            url,
            normalizeGeoDataUrl(" $url "),
        )
    }

    @Test
    fun combinedProgressIsWeightedByFileSize() {
        val progress = combinedGeoDataDownloadProgress(
            listOf(
                GeoDataDownloadProgress(bytesDownloaded = 25, totalBytes = 100),
                GeoDataDownloadProgress(bytesDownloaded = 150, totalBytes = 300),
            ),
        )

        assertEquals(175L, progress?.bytesDownloaded)
        assertEquals(400L, progress?.totalBytes)
        assertEquals(0.4375f, progress?.fraction)
    }

    @Test
    fun combinedProgressIsUnknownUntilEveryFileSizeIsKnown() {
        val progress = combinedGeoDataDownloadProgress(
            listOf(
                GeoDataDownloadProgress(bytesDownloaded = 25, totalBytes = 100),
                GeoDataDownloadProgress(bytesDownloaded = 10, totalBytes = null),
            ),
        )

        assertEquals(35L, progress?.bytesDownloaded)
        assertEquals(null, progress?.totalBytes)
        assertEquals(null, progress?.fraction)
    }

    @Test
    fun downloadProgressFractionIsClamped() {
        assertEquals(1f, GeoDataDownloadProgress(bytesDownloaded = 150, totalBytes = 100).fraction)
        assertEquals(0f, GeoDataDownloadProgress(bytesDownloaded = -1, totalBytes = 100).fraction)
        assertEquals(null, GeoDataDownloadProgress(bytesDownloaded = 1, totalBytes = 0).fraction)
    }

    @Test
    fun bundledDataSeedsMissingDefaultFile() {
        val directory = Files.createTempDirectory("geodata").toFile()
        try {
            val target = directory.resolve(GEOIP_FILE_NAME)
            val source = directory.resolve("geoip-source")

            seedBundledGeoData("default", "default", target, source) {
                ByteArrayInputStream("bundled".toByteArray())
            }

            assertEquals("bundled", target.readText())
            assertEquals("default", source.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bundledDataDoesNotReplaceExistingOrCustomData() {
        val directory = Files.createTempDirectory("geodata").toFile()
        try {
            val target = directory.resolve(GEOIP_FILE_NAME)
            val source = directory.resolve("geoip-source")
            target.writeText("updated")
            source.writeText("default")

            seedBundledGeoData("default", "default", target, source) {
                error("Bundled file should not be opened")
            }
            assertEquals("updated", target.readText())

            target.delete()
            seedBundledGeoData("custom", "default", target, source) {
                error("Bundled file should not be opened")
            }
            assertFalse(target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
