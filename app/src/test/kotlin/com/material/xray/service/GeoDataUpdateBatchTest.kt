package com.material.xray.service

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoDataUpdateBatchTest {
    @Test
    fun `reload waits for both downloads even when the second fails`() = runTest {
        var reloads = 0
        val batch = GeoDataUpdateBatch { reloads++ }
        val geoip = CompletableDeferred<Unit>()
        val geosite = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { batch.run { geoip.await() } }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                batch.run {
                    geosite.await()
                    throw IOException("Download failed")
                }
            }
        }

        geoip.complete(Unit)
        first.join()
        assertEquals(0, reloads)
        geosite.complete(Unit)
        second.join()
        assertEquals(1, reloads)
    }

    @Test
    fun `failed downloads preserve the running core and reset the batch`() = runTest {
        var reloads = 0
        val batch = GeoDataUpdateBatch { reloads++ }
        val geoip = CompletableDeferred<Unit>()
        val geosite = CompletableDeferred<Unit>()
        val jobs = listOf(geoip, geosite).map { completion ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    batch.run {
                        completion.await()
                        throw IOException("Download failed")
                    }
                }
            }
        }

        geoip.complete(Unit)
        geosite.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(0, reloads)
        batch.run { }
        assertEquals(1, reloads)
    }
}
