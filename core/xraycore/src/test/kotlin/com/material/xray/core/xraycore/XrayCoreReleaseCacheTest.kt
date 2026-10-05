package com.material.xray.core.xraycore

import com.material.xray.core.xray.XrayPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XrayCoreReleaseCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val paths: XrayPaths
        get() = object : XrayPaths {
            override val filesDir: File = temporary.root
            override val cacheDir: File = temporary.root
            override val nativeLibraryDir: File? = null
        }

    @Test
    fun `loaded pages and pagination survive reopening`() {
        val cache = XrayCoreReleaseCache(paths, "arm64-v8a")
        val first = page("v26.9.30", hasMore = true)
        val second = page("v26.8.1", hasMore = false)
        cache.save(1, first)
        cache.save(2, second)

        assertEquals(listOf(first, second), XrayCoreReleaseCache(paths, "arm64-v8a").pages)
    }

    @Test
    fun `refreshing first page removes stale subsequent pages`() {
        val cache = XrayCoreReleaseCache(paths, "arm64-v8a")
        cache.save(1, page("v26.9.30", hasMore = true))
        cache.save(2, page("v26.8.1", hasMore = false))
        val refreshed = page("v26.10.1", hasMore = true)
        cache.save(1, refreshed)

        assertEquals(listOf(refreshed), XrayCoreReleaseCache(paths, "arm64-v8a").pages)
    }

    @Test
    fun `missing corrupt or different ABI cache is ignored`() {
        assertTrue(XrayCoreReleaseCache(paths, "arm64-v8a").pages.isEmpty())
        File(temporary.root, "xray-core-releases.json").writeText("broken")
        assertTrue(XrayCoreReleaseCache(paths, "arm64-v8a").pages.isEmpty())
        XrayCoreReleaseCache(paths, "arm64-v8a").save(1, page("v26.9.30", hasMore = true))
        assertTrue(XrayCoreReleaseCache(paths, "x86_64").pages.isEmpty())
    }

    @Test
    fun `cache write failure still keeps loaded pages in memory`() {
        File(temporary.root, "xray-core-releases.json").mkdir()
        val cache = XrayCoreReleaseCache(paths, "arm64-v8a")
        val loaded = page("v26.9.30", hasMore = true)
        cache.save(1, loaded)
        assertEquals(listOf(loaded), cache.pages)
    }

    @Test
    fun `skipping a page does not leave gaps in cache`() {
        val cache = XrayCoreReleaseCache(paths, "arm64-v8a")
        cache.save(2, page("v26.8.1", hasMore = false))
        assertTrue(cache.pages.isEmpty())
    }

    private fun page(tag: String, hasMore: Boolean) = XrayCoreReleasePage(
        listOf(
            XrayCoreRelease(
                tag = tag,
                publishedAt = "2026-09-30T00:00:00Z",
                prerelease = false,
                assetUrl = "https://github.com/XTLS/Xray-core/releases/download/$tag/Xray-android-arm64-v8a.zip",
                assetSha256 = "ab".repeat(32),
            ),
        ),
        hasMore = hasMore,
    )
}
