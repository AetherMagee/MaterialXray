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
        val fraction = combinedGeoDataDownloadFraction(
            listOf(
                GeoDataDownloadProgress(bytesDownloaded = 25, totalBytes = 100),
                GeoDataDownloadProgress(bytesDownloaded = 150, totalBytes = 300),
            ),
        )

        assertEquals(0.4375f, fraction)
    }

    @Test
    fun combinedProgressIsUnknownUntilEveryFileSizeIsKnown() {
        val fraction = combinedGeoDataDownloadFraction(
            listOf(
                GeoDataDownloadProgress(bytesDownloaded = 25, totalBytes = 100),
                GeoDataDownloadProgress(bytesDownloaded = 0, totalBytes = null),
            ),
        )

        assertEquals(null, fraction)
    }

    @Test
    fun downloadProgressFractionIsClamped() {
        assertEquals(1f, GeoDataDownloadProgress(bytesDownloaded = 150, totalBytes = 100).fraction)
        assertEquals(0f, GeoDataDownloadProgress(bytesDownloaded = -1, totalBytes = 100).fraction)
        assertEquals(null, GeoDataDownloadProgress(bytesDownloaded = 1, totalBytes = 0).fraction)
    }
}
