package com.material.xray.core.data.xraycore

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayCoreReleasesTest {
    @Test
    fun `lists supported releases with an official asset and digest, newest first`() {
        val releases = parseXrayCoreReleases(
            """
            [
              ${release("v26.7.11", "2026-07-11")},
              ${release("v26.9.30", "2026-09-30", prerelease = true)},
              ${release("v26.9.9", "2026-09-09", draft = true)},
              ${release("v25.12.8", "2025-12-08")},
              ${release("v26.8.1", "2026-08-01", digest = null)},
              ${release("v26.8.2", "2026-08-02", url = "https://example.com/Xray-android-arm64-v8a.zip")},
              ${release("v26.8.3", "2026-08-03", url = "https://github.com/XTLS/Xray-core/releases/download/v26.8.2/Xray-android-arm64-v8a.zip")},
              ${release("../x", "2026-08-04")}
            ]
            """,
            assetName = ASSET,
        )

        assertEquals(listOf("v26.9.30", "v26.7.11"), releases.map { it.tag })
        assertTrue(releases.first().prerelease)
        assertEquals("ab".repeat(32), releases.first().assetSha256)
    }

    @Test
    fun `releases without this device's asset are skipped`() {
        assertTrue(parseXrayCoreReleases("[${release("v26.9.30", "2026-09-30")}]", "Xray-android-amd64.zip").isEmpty())
    }

    @Test
    fun `unexpected JSON shapes are reported as IO failures`() {
        listOf("{}", "[1]", """[{"tag_name": "v26.9.30", "assets": {}}]""", "not json").forEach { body ->
            assertThrows(IOException::class.java) { parseXrayCoreReleases(body, ASSET) }
        }
    }

    @Test
    fun `parses GitHub digests`() {
        assertEquals("ab".repeat(32), parseApiDigest("sha256:" + "AB".repeat(32)))
        assertNull(parseApiDigest("sha512:" + "ab".repeat(32)))
        assertNull(parseApiDigest("sha256:abc"))
        assertNull(parseApiDigest(null))
    }

    @Test
    fun `compares versions numerically`() {
        assertEquals(1, compareXrayVersions("v26.10.1", "26.9.30"))
        assertEquals(0, compareXrayVersions("v26.1.23", "26.1.23"))
        assertEquals(-1, compareXrayVersions("26.1", "26.1.1"))
        assertNull(compareXrayVersions("custom", "26.1.1"))
        assertTrue(isSupportedXrayVersion(MINIMUM_XRAY_VERSION))
        assertTrue(!isSupportedXrayVersion("v25.12.8"))
    }

    @Test
    fun `maps ABIs to upstream assets`() {
        assertEquals("Xray-android-arm64-v8a.zip", xrayReleaseAssetName("arm64-v8a"))
        assertEquals("Xray-android-amd64.zip", xrayReleaseAssetName("x86_64"))
        assertNull(xrayReleaseAssetName("armeabi-v7a"))
    }

    private fun release(
        tag: String,
        publishedAt: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        digest: String? = "sha256:" + "ab".repeat(32),
        url: String = "https://github.com/XTLS/Xray-core/releases/download/$tag/$ASSET",
    ) = """
        {
          "tag_name": "$tag",
          "published_at": "${publishedAt}T00:00:00Z",
          "prerelease": $prerelease,
          "draft": $draft,
          "assets": [
            {"name": "$ASSET", "browser_download_url": "$url"${digest?.let { ", \"digest\": \"$it\"" }.orEmpty()}}
          ]
        }
    """

    private companion object {
        const val ASSET = "Xray-android-arm64-v8a.zip"
    }
}
