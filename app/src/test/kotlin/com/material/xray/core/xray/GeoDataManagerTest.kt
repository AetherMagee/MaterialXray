package com.material.xray.core.xray

import org.junit.Assert.assertEquals
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
}
