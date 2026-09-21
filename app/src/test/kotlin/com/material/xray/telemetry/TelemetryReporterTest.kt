package com.material.xray.telemetry

import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.model.ConnectionProgress
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
        reporter.recordConnectionCompletion(
            outcome = ConnectionOutcome.Success,
            durationMillis = 25,
            connection = connection(),
        )

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
    fun `connection completion records one exhaustive outcome`() {
        val client = FakeTelemetryClient()
        val reporter = TelemetryReporter(client).apply { setEnabled(true) }

        reporter.recordConnectionAttempt(connection())
        reporter.recordConnectionCompletion(
            outcome = ConnectionOutcome.Failure,
            durationMillis = 25,
            connection = connection(),
        )

        assertEquals(
            listOf(
                RecordedMetric(
                    name = "connection.attempted",
                    attributes = mapOf("service_mode" to "root", "root_backend" to "tproxy"),
                ),
                RecordedMetric(
                    name = "connection.completed",
                    attributes = mapOf(
                        "service_mode" to "root",
                        "root_backend" to "tproxy",
                        "outcome" to "failure",
                    ),
                ),
            ),
            client.metrics,
        )
        assertEquals(
            RecordedDistribution(
                name = "connection.duration",
                value = 25,
                attributes = mapOf(
                    "service_mode" to "root",
                    "root_backend" to "tproxy",
                    "outcome" to "failure",
                ),
            ),
            client.distributions.single(),
        )
        assertEquals(TelemetryStatus.InternalError, client.transactions.single().finishedWith)
    }

    @Test
    fun `interrupted connection is counted and timed`() {
        val client = FakeTelemetryClient()
        val reporter = TelemetryReporter(client).apply { setEnabled(true) }

        reporter.recordConnectionAttempt(connection())
        reporter.recordConnectionCompletion(
            outcome = ConnectionOutcome.Interrupted,
            durationMillis = 40,
            connection = connection(),
        )

        assertEquals("interrupted", client.metrics.last().attributes["outcome"])
        assertEquals("interrupted", client.distributions.single().attributes["outcome"])
        assertEquals(TelemetryStatus.Aborted, client.transactions.single().finishedWith)
    }

    @Test
    fun `specific connection step uses stable trace id`() {
        val client = FakeTelemetryClient()
        val reporter = TelemetryReporter(client).apply { setEnabled(true) }
        reporter.recordConnectionAttempt(connection())

        reporter.startConnectionStep(
            ConnectionProgress.ConfiguringRouting,
            ConnectionTelemetryStep.ActivateTproxy,
        )

        assertEquals(
            RecordedChildSpan("connection.step", "tproxy.activate"),
            client.transactions.single().children.single(),
        )
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

    @Test
    fun `TPROXY compatibility uses fixed low cardinality attributes`() {
        val client = FakeTelemetryClient()
        val reporter = TelemetryReporter(client, elapsedRealtime = { 0 }).apply { setEnabled(true) }

        reporter.recordTproxyCompatibility(
            TproxyCompatibility.Unsupported(
                reason = TproxyCompatibility.Reason.OwnerMatchUnavailable,
                details = "secret shell output",
            ),
            cached = true,
        )

        assertEquals(
            RecordedMetric(
                name = "tproxy.compatibility.checked",
                attributes = mapOf(
                    "result" to "unsupported",
                    "reason" to "owner_match_unavailable",
                    "ipv6_supported" to false,
                    "cached" to true,
                ),
            ),
            client.metrics.single(),
        )
        assertTrue(client.messages.isEmpty())
        assertTrue(client.metrics.single().attributes.values.none { it == "secret shell output" })
    }

    @Test
    fun `fresh probe malfunctions create a fixed issue without raw output`() {
        val client = FakeTelemetryClient()
        val reporter = TelemetryReporter(client, elapsedRealtime = { 0 }).apply { setEnabled(true) }

        reporter.recordTproxyCompatibility(
            TproxyCompatibility.Unsupported(
                reason = TproxyCompatibility.Reason.CommandTimedOut,
                details = "secret shell output",
            ),
            cached = false,
        )

        assertEquals(
            RecordedMessage(
                message = "TPROXY compatibility probe malfunctioned",
                fingerprint = "tproxy-compatibility-command_timed_out",
                tags = mapOf("reason" to "command_timed_out"),
            ),
            client.messages.single(),
        )
        assertTrue(client.messages.single().toString().contains("secret shell output").not())
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

private data class RecordedMessage(
    val message: String,
    val fingerprint: String,
    val tags: Map<String, String>,
)

private data class RecordedChildSpan(
    val operation: String,
    val description: String,
)

private class FakeTelemetryClient : TelemetryClient {
    var enableCount = 0
    var disableCount = 0
    val metrics = mutableListOf<RecordedMetric>()
    val distributions = mutableListOf<RecordedDistribution>()
    val breadcrumbs = mutableListOf<RecordedBreadcrumb>()
    val transactions = mutableListOf<FakeTelemetryTransaction>()
    val messages = mutableListOf<RecordedMessage>()

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

    override fun captureMessage(message: String, fingerprint: String, tags: Map<String, String>) {
        messages += RecordedMessage(message, fingerprint, tags)
    }
}

private class FakeTelemetryTransaction : TelemetryTransaction {
    var finishedWith: TelemetryStatus? = null
    val children = mutableListOf<RecordedChildSpan>()

    override fun setTag(key: String, value: String) = Unit

    override fun startChild(operation: String, description: String): TelemetrySpan {
        children += RecordedChildSpan(operation, description)
        return TelemetrySpan {}
    }

    override fun finish(status: TelemetryStatus) {
        finishedWith = status
    }
}
