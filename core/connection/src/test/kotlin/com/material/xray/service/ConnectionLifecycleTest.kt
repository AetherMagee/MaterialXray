package com.material.xray.service

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLifecycleTest {
    @Test
    fun `connection failure does not rerun the entire attempt`() = runTest {
        val requests = mutableListOf<ConnectionRequest>()
        val lifecycle = lifecycle(
            runAttempt = { request ->
                requests += request
                false
            },
        )

        val connected = lifecycle.connect(
            ConnectionRequest(server(), preparation = ConnectionPreparation.FastServerSwitch),
        )

        assertFalse(connected)
        assertEquals(listOf(ConnectionPreparation.FastServerSwitch), requests.map { it.preparation })
    }

    @Test
    fun `non-retryable failure is published without another attempt`() = runTest {
        var attempts = 0
        var exhausted: ConnectionFailure? = null
        val failure = ConnectionFailure("permission denied", retryable = false)
        val lifecycle = lifecycle(
            runAttempt = {
                attempts++
                false
            },
            currentFailure = { failure },
            onExhausted = { exhausted = it },
        )

        assertFalse(lifecycle.connect(ConnectionRequest(server())))
        assertEquals(1, attempts)
        assertEquals(failure, exhausted)
    }

    @Test
    fun `serialized commands do not overlap`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val lifecycle = lifecycle(runAttempt = { true })

        val first = async {
            lifecycle.serialized {
                order += "first-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstStarted.await()
        val second = async {
            lifecycle.serialized { order += "second" }
        }
        testScheduler.runCurrent()
        assertEquals(listOf("first-start"), order)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun `latest commands discard superseded queued work`() = runTest {
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val handled = mutableListOf<String>()
        val lifecycle = lifecycle(runAttempt = { true })

        lifecycle.launch {
            blockerStarted.complete(Unit)
            releaseBlocker.await()
        }
        blockerStarted.await()
        lifecycle.launchLatest { handled += "first" }
        lifecycle.launchLatest { handled += "second" }

        releaseBlocker.complete(Unit)
        runCurrent()

        assertEquals(listOf("second"), handled)
    }

    @Test
    fun `latest commands settle before running`() = runTest {
        val handled = mutableListOf<String>()
        val lifecycle = lifecycle(runAttempt = { true })

        lifecycle.launchLatest(200) { handled += "first" }
        advanceTimeBy(100)
        lifecycle.launchLatest(200) { handled += "second" }
        advanceTimeBy(199)
        runCurrent()
        assertTrue(handled.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("second"), handled)
    }

    @Test
    fun `an unexpected command failure is reported instead of escaping the scope`() = runTest {
        val commandOrder = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val lifecycle = lifecycle(
            runAttempt = { true },
            onCommandFailure = { error ->
                commandOrder += "failure"
                failures += error
            },
        )

        lifecycle.launch { throw IllegalStateException("boom") }
        lifecycle.launch { commandOrder += "next" }
        runCurrent()

        assertEquals(listOf("failure", "next"), commandOrder)
        assertEquals(listOf("boom"), failures.map { it.message })
        assertTrue(backgroundScope.coroutineContext.job.isActive)
    }

    @Test
    fun `a requested command counts as busy until it and everything queued behind it finish`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        var idleCalls = 0
        lateinit var lifecycle: ConnectionLifecycle
        val busyWhenIdle = mutableListOf<Boolean>()
        lifecycle = lifecycle(
            runAttempt = { true },
            onIdle = {
                idleCalls++
                busyWhenIdle += lifecycle.isBusy
            },
        )

        assertFalse(lifecycle.isBusy)
        lifecycle.launch { releaseFirst.await() }
        assertTrue(lifecycle.isBusy)
        lifecycle.launchLatest(200) {}
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertTrue(lifecycle.isBusy)
        assertEquals(0, idleCalls)

        releaseFirst.complete(Unit)
        runCurrent()

        assertFalse(lifecycle.isBusy)
        assertEquals(1, idleCalls)
        assertEquals(listOf(false), busyWhenIdle)
    }

    @Test
    fun `a failed command still returns the lifecycle to idle`() = runTest {
        var idleCalls = 0
        val lifecycle = lifecycle(runAttempt = { true }, onIdle = { idleCalls++ })

        lifecycle.launch { throw IllegalStateException("boom") }
        runCurrent()

        assertFalse(lifecycle.isBusy)
        assertEquals(1, idleCalls)
    }

    private fun TestScope.lifecycle(
        runAttempt: suspend (ConnectionRequest) -> Boolean,
        currentFailure: () -> ConnectionFailure = { ConnectionFailure("failed", retryable = true) },
        onExhausted: (ConnectionFailure) -> Unit = {},
        onCommandFailure: suspend (Throwable) -> Unit = {},
        onIdle: () -> Unit = {},
    ) = ConnectionLifecycle(
        scope = backgroundScope,
        beforeCommand = {},
        afterCommand = {},
        runAttempt = runAttempt,
        currentFailure = currentFailure,
        onConnected = {},
        onExhausted = onExhausted,
        onCommandFailure = onCommandFailure,
        onIdle = onIdle,
    )

    private companion object {
        fun server() = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Test",
            address = "192.0.2.1",
            port = 443,
            password = "test",
        )
    }
}
