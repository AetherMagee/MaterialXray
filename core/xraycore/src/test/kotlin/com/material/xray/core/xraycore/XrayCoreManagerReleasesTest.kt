package com.material.xray.core.xraycore

import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.platform.JvmPlatformInfo
import com.material.xray.core.common.platform.PlatformInfo
import com.material.xray.core.network.AppHttpClient
import com.material.xray.core.network.DirectHttpClient
import com.material.xray.core.xray.XrayPaths
import java.io.File
import java.io.IOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XrayCoreManagerReleasesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `scheduled checks fetch fresh releases without replacing picker cache`() = runTest {
        var requests = 0
        val client = client { requests++ }
        val manager = manager(DirectHttpClient(client))
        manager.releases(1)
        manager.releases(2)
        val cached = manager.cachedReleasePages
        manager.findUpdate()

        assertEquals(3, requests)
        assertEquals(2, cached.size)
        assertEquals(cached, manager.cachedReleasePages)
    }

    @Test
    fun `cancelled successful request does not write picker cache`() = runTest {
        val client = client {}
        val httpClient = object : AppHttpClient {
            override suspend fun <T> use(block: suspend (OkHttpClient) -> T): T {
                val result = block(client)
                currentCoroutineContext().cancel()
                return result
            }
        }
        val manager = manager(httpClient)
        val request = launch { manager.releases(1) }
        request.join()

        assertTrue(request.isCancelled)
        assertTrue(manager.cachedReleasePages.isEmpty())
        assertTrue(!File(temporary.root, "xray-core-releases.json").exists())
    }

    @Test
    fun `cancelled failed request remains cancellation without reporting a network error`() = runTest {
        val log = LogBuffer()
        val httpClient = object : AppHttpClient {
            override suspend fun <T> use(block: suspend (OkHttpClient) -> T): T {
                currentCoroutineContext().cancel()
                throw IOException("Cancelled request")
            }
        }
        val manager = manager(httpClient, log)
        val request = launch { manager.releases(1) }
        request.join()

        assertTrue(request.isCancelled)
        assertTrue(log.entries.value.isEmpty())
        assertTrue(manager.cachedReleasePages.isEmpty())
    }

    private fun TestScope.manager(httpClient: AppHttpClient, log: LogBuffer = LogBuffer()) = XrayCoreManager(
        paths = object : XrayPaths {
            override val filesDir: File = temporary.root
            override val cacheDir: File = temporary.root
            override val nativeLibraryDir: File? = null
        },
        httpClient = httpClient,
        platformInfo = object : PlatformInfo by JvmPlatformInfo {
            override val primaryAbi = "arm64-v8a"
        },
        log = log,
        scope = backgroundScope,
    )

    private fun client(onRequest: () -> Unit): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            onRequest()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("[]".toResponseBody())
                .build()
        }
        .build()
}
