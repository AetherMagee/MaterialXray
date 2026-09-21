package com.material.xray.telemetry

import com.material.xray.model.RootConnectionBackend
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryReporterTest {
    @Test
    fun `disabled reporter emits nothing`() {
        val client = FakeTelemetryClient()
        val reporter = TelemetryReporter(client)

        reporter.recordConnectionAttempt(connection())
        reporter.recordConnectionResult(succeeded = true, durationMillis = 25, connection = connection())

        assertTrue(client.metrics.isEmpty())
        assertTrue(client.distributions.isEmpty())
        assertTrue(client.breadcrumbs.isEmpty())
    }

    @Test
    fun `opt out closes client and stops telemetry`() {
        val client = FakeTelemetryClient()
        val reporter = TelemetryReporter(client)
        reporter.setEnabled(true)
        reporter.recordConnectionAttempt(connection())

        reporter.setEnabled(false)
        val emittedBeforeDisabledCall = client.metrics.size
        reporter.recordConnectionAttempt(connection())

        assertEquals(1, client.enableCount)
        assertEquals(1, client.disableCount)
        assertEquals(emittedBeforeDisabledCall, client.metrics.size)
        assertEquals(TelemetryStatus.Cancelled, client.transactions.single().finishedWith)
    }

    @Test
    fun `sanitizer removes unsafe event context`() {
        val event = SentryEvent().apply {
            request = Request()
            serverName = "device-name"
            logger = "unsafe.logger"
            message = Message().apply { formatted = "user-provided message" }
            exceptions = listOf(
                SentryException().apply {
                    type = "IllegalStateException"
                    value = "secret exception text"
                },
            )
            user = User().apply {
                id = "installation-id"
                email = "person@example.com"
                ipAddress = "192.0.2.1"
            }
        }

        sanitizeTelemetryEvent(event)

        assertNull(event.request)
        assertNull(event.serverName)
        assertNull(event.message)
        assertEquals("IllegalStateException", event.exceptions?.single()?.value)
        assertEquals("installation-id", event.user?.id)
        assertNull(event.user?.email)
        assertNull(event.user?.ipAddress)
    }

    private fun connection() = TelemetryConnectionContext(
        mode = TelemetryServiceMode.Root,
        backend = RootConnectionBackend.Tproxy,
        alwaysOnVpn = false,
        allowIpv6 = true,
        bypassLan = true,
    )
}

private data class RecordedMetric(
    val name: String,
    val attributes: Map<String, Any>,
)

private data class RecordedDistribution(
    val name: String,
    val value: Long,
    val attributes: Map<String, Any>,
)

private data class RecordedBreadcrumb(
    val category: String,
    val message: String,
    val data: Map<String, Any>,
)

private class FakeTelemetryClient : TelemetryClient {
    var enableCount = 0
    var disableCount = 0
    val metrics = mutableListOf<RecordedMetric>()
    val distributions = mutableListOf<RecordedDistribution>()
    val breadcrumbs = mutableListOf<RecordedBreadcrumb>()
    val transactions = mutableListOf<FakeTelemetryTransaction>()

    override fun enable() {
        enableCount++
    }

    override fun disable() {
        disableCount++
    }

    override fun setTag(key: String, value: String) = Unit

    override fun count(name: String, attributes: Map<String, Any>) {
        metrics += RecordedMetric(name, attributes)
    }

    override fun distributionMillis(name: String, value: Long, attributes: Map<String, Any>) {
        distributions += RecordedDistribution(name, value, attributes)
    }

    override fun addBreadcrumb(category: String, message: String, data: Map<String, Any>) {
        breadcrumbs += RecordedBreadcrumb(category, message, data)
    }

    override fun startTransaction(name: String, operation: String): TelemetryTransaction = FakeTelemetryTransaction().also(transactions::add)

    override fun captureException(error: Throwable, fingerprint: List<String>, tags: Map<String, String>) = Unit

    override fun captureMessage(message: String, fingerprint: String, tags: Map<String, String>) = Unit
}

private class FakeTelemetryTransaction : TelemetryTransaction {
    var finishedWith: TelemetryStatus? = null

    override fun setTag(key: String, value: String) = Unit

    override fun startChild(operation: String, description: String): TelemetrySpan = TelemetrySpan {}

    override fun finish(status: TelemetryStatus) {
        finishedWith = status
    }
}
