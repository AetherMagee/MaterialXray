package com.material.xray.core.network

import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv6DetectorTest {

    @Test
    fun `network key is the global prefixes, ignoring addresses that cannot reach the internet`() {
        val addresses = listOf("192.0.2.10", "fe80::1", "fd00::1", "2001:db8:1:2::abcd", "2001:db8:1:2::1234", "2a00::1")
            .map(InetAddress::getByName)

        assertEquals("20010db800010002,2a00000000000000", ipv6NetworkKey(addresses, hasIpv6DefaultRoute = true))
    }

    @Test
    fun `network without a global address or IPv6 default route has no key`() {
        val global = listOf(InetAddress.getByName("2001:db8::1"))
        val local = listOf("192.0.2.10", "fe80::1", "fd00::1").map(InetAddress::getByName)

        assertNull(ipv6NetworkKey(global, hasIpv6DefaultRoute = false))
        assertNull(ipv6NetworkKey(local, hasIpv6DefaultRoute = true))
    }

    @Test
    fun `callers share one running probe and its verdict is remembered`() = runTest {
        var probes = 0
        val result = CompletableDeferred<Boolean>()
        val cache = Ipv6VerdictCache(backgroundScope, nowMs = { 0L })

        val waiting = List(3) {
            async {
                cache.verdict("key") {
                    probes++
                    result.await()
                }
            }
        }
        runCurrent()
        assertNull(cache.cached("key"))
        result.complete(true)

        assertEquals(listOf(true, true, true), waiting.awaitAll())
        assertEquals(1, probes)
        assertEquals(true, cache.cached("key"))
    }

    @Test
    fun `a failure is retried sooner than a success`() = runTest {
        var now = 0L
        val cache = Ipv6VerdictCache(backgroundScope, nowMs = { now })
        cache.verdict("broken") { false }
        cache.verdict("working") { true }

        now += 10 * 60_000L

        assertNull(cache.cached("broken"))
        assertEquals(true, cache.cached("working"))
        now += 30 * 60_000L
        assertNull(cache.cached("working"))
    }

    @Test
    fun `a probe that throws counts as IPv6 not working`() = runTest {
        val cache = Ipv6VerdictCache(backgroundScope, nowMs = { 0L })

        assertEquals(false, cache.verdict("key") { error("boom") })
    }

    @Test
    fun `two passing checks decide without waiting for a blocked one`() = runTest {
        val failures = quorumFailures(
            needed = 2,
            checks = listOf("a" to { awaitCancellation() }, "b" to { "" }, "c" to { "" }),
        )

        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `two failing checks out of three fail the quorum`() = runTest {
        val failures = quorumFailures(
            needed = 2,
            checks = listOf("a" to { "timeout" }, "b" to { "" }, "c" to { "reset" }),
        )

        assertEquals(listOf("a: timeout", "c: reset"), failures.sorted())
    }

    @Test
    fun `a quorum of all fails on the first failure`() = runTest {
        val failures = quorumFailures(
            needed = 2,
            checks = listOf("a" to { awaitCancellation() }, "b" to { "reset" }),
        )

        assertEquals(listOf("b: reset"), failures)
    }
}
