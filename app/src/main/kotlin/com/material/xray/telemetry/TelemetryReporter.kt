package com.material.xray.telemetry

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import com.material.xray.model.RootConnectionBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.android.core.SentryAndroid
import io.sentry.metrics.MetricsUnit
import io.sentry.metrics.SentryMetricsParameters
import io.sentry.protocol.Message
import io.sentry.protocol.User
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class TelemetryServiceMode(val value: String) {
    Root("root"),
    Vpn("vpn"),
}

enum class CoreRecoveryCause(val value: String) {
    ProcessExit("process_exit"),
    MemoryLimit("memory_limit"),
    TunnelUnavailable("tunnel_unavailable"),
    RuntimeModeChanged("runtime_mode_changed"),
}

internal fun interface TelemetrySpan {
    fun finish(succeeded: Boolean)
}

/**
 * Fixed, low-cardinality description of how a connection was requested. Everything in here is an
 * enum or a boolean so it can safely become a metric attribute, a scope tag, or breadcrumb data.
 */
data class TelemetryConnectionContext(
    val mode: TelemetryServiceMode,
    val backend: RootConnectionBackend,
    val alwaysOnVpn: Boolean,
    val allowIpv6: Boolean,
    val bypassLan: Boolean,
) {
    internal val metricAttributes: Map<String, Any> = mapOf(
        "service_mode" to mode.value,
        "root_backend" to if (mode == TelemetryServiceMode.Root) backend.persistedValue else "none",
    )

    internal val scopeTags: Map<String, String> = metricAttributes.mapValues { it.value.toString() } +
        mapOf(
            "always_on_vpn" to alwaysOnVpn.toString(),
            "allow_ipv6" to allowIpv6.toString(),
            "bypass_lan" to bypassLan.toString(),
        )
}

@Singleton
class TelemetryReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @Volatile private var enabled = false
    private val lastIssueAt = mutableMapOf<String, Long>()
    private var activeConnectionTrace: io.sentry.ITransaction? = null

    @Synchronized
    fun setEnabled(enable: Boolean) {
        if (enable == enabled) return
        if (!enable) {
            enabled = false
            activeConnectionTrace?.finish(SpanStatus.CANCELLED)
            activeConnectionTrace = null
            Sentry.close()
            lastIssueAt.clear()
            installationIdFile().delete()
            return
        }

        SentryAndroid.init(context) { options ->
            options.dsn = SENTRY_DSN
            options.environment = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "development"
            } else {
                "production"
            }
            options.isSendDefaultPii = false
            // Breadcrumbs are only ever added by this class from a fixed vocabulary; every automatic
            // source (Logcat, network, UI, lifecycle) stays disabled below.
            options.maxBreadcrumbs = MAX_BREADCRUMBS
            // Message events carry their own tags; the calling thread's stack is noise.
            options.isAttachStacktrace = false
            options.tracesSampleRate = if (isDebuggable()) DEBUG_TRACE_SAMPLE_RATE else RELEASE_TRACE_SAMPLE_RATE
            options.profilesSampleRate = 0.0
            options.profileSessionSampleRate = 0.0
            options.isEnableUserInteractionTracing = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableAutoActivityLifecycleTracing = false
            options.isEnableStandaloneAppStartTracing = false
            options.enableAllAutoBreadcrumbs(false)
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isAttachRawTombstone = false
            options.isCollectAdditionalContext = false
            options.isCollectExternalStorageContext = false
            options.isEnableRootCheck = false
            options.isSendModules = false
            options.isAnrEnabled = true
            options.isEnableNdk = true
            options.logs.isEnabled = false
            options.metrics.isEnabled = true
            options.beforeSend = { event, _ ->
                event.request = null
                event.serverName = null
                event.exceptions?.forEach { exception -> exception.value = exception.type }
                if (event.logger != SAFE_EVENT_LOGGER) event.message = null
                event.user = event.user?.id?.let { id -> User().apply { this.id = id } }
                event
            }
        }
        Sentry.setUser(User().apply { id = installationId() })
        enabled = true
    }

    @Synchronized
    fun recordConnectionAttempt(connection: TelemetryConnectionContext) {
        count("connection.attempted", connection.metricAttributes)
        if (!enabled) return
        connection.scopeTags.forEach { (key, value) -> Sentry.setTag(key, value) }
        addBreadcrumb("connection", "attempt", connection.metricAttributes)
        activeConnectionTrace?.finish(SpanStatus.ABORTED)
        activeConnectionTrace = Sentry.startTransaction("connection.setup", "connection").apply {
            connection.metricAttributes.forEach { (key, value) -> setTag(key, value.toString()) }
        }
    }

    @Synchronized
    fun recordConnectionResult(
        succeeded: Boolean,
        durationMillis: Long,
        connection: TelemetryConnectionContext,
    ) {
        val attributes = connection.metricAttributes
        count(if (succeeded) "connection.succeeded" else "connection.failed", attributes)
        if (enabled) {
            Sentry.metrics().distribution(
                "connection.duration",
                durationMillis.toDouble(),
                MetricsUnit.Duration.MILLISECOND,
                SentryMetricsParameters.create(attributes),
            )
            addBreadcrumb(
                "connection",
                if (succeeded) "succeeded" else "failed",
                mapOf("duration_ms" to durationMillis),
            )
            activeConnectionTrace?.finish(if (succeeded) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)
            activeConnectionTrace = null
        }
    }

    fun recordConnectionState(state: ConnectionState) {
        if (!enabled) return
        val value = state.telemetryValue()
        Sentry.setTag("connection_state", value)
        Sentry.setTag("core_running", (state is ConnectionState.Connected).toString())
        val data = if (state is ConnectionState.Error) mapOf("retryable" to state.retryable) else emptyMap()
        addBreadcrumb("connection.state", value, data)
    }

    @Synchronized
    internal fun startConnectionStep(progress: ConnectionProgress): TelemetrySpan? {
        if (!enabled) return null
        val step = progress.telemetryValue()
        val span = activeConnectionTrace?.startChild("connection.step", step) ?: return null
        return TelemetrySpan { succeeded ->
            addBreadcrumb("connection.step", step, mapOf("succeeded" to succeeded))
            span.finish(if (succeeded) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)
        }
    }

    @Synchronized
    fun finishInterruptedConnectionTrace() {
        activeConnectionTrace?.finish(SpanStatus.INTERNAL_ERROR)
        activeConnectionTrace = null
    }

    fun recordCoreRecovery(cause: CoreRecoveryCause, succeeded: Boolean) {
        val attributes = mapOf("cause" to cause.value, "succeeded" to succeeded)
        count("core.recovery", attributes)
        addBreadcrumb("core.recovery", cause.value, mapOf("succeeded" to succeeded))
        if (cause == CoreRecoveryCause.ProcessExit && shouldReportIssue("core_exit")) {
            captureSafeMessage(
                message = "Xray core exited unexpectedly",
                fingerprint = "core-exit",
                tags = mapOf("cause" to cause.value, "recovery_succeeded" to succeeded.toString()),
            )
        }
    }

    fun recordUnexpectedCommandFailure(error: Throwable) {
        if (!enabled || !shouldReportIssue("unexpected_command_failure")) return
        Sentry.captureException(error) { scope ->
            scope.fingerprint = listOf("unexpected-connection-command", error.javaClass.name)
            scope.setTag("failure_category", "unexpected_connection_command")
        }
    }

    private fun count(name: String, attributes: Map<String, Any>) {
        if (!enabled) return
        Sentry.metrics().count(name, 1.0, "none", SentryMetricsParameters.create(attributes))
    }

    private fun addBreadcrumb(category: String, message: String, data: Map<String, Any>) {
        if (!enabled) return
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                this.category = category
                this.message = message
                level = SentryLevel.INFO
                data.forEach { (key, value) -> setData(key, value) }
            },
        )
    }

    private fun captureSafeMessage(message: String, fingerprint: String, tags: Map<String, String>) {
        if (!enabled) return
        val event = io.sentry.SentryEvent().apply {
            level = SentryLevel.ERROR
            logger = SAFE_EVENT_LOGGER
            this.message = Message().apply { formatted = message }
            fingerprints = listOf(fingerprint)
            tags.forEach { (key, value) -> setTag(key, value) }
        }
        Sentry.captureEvent(event)
    }

    @Synchronized
    private fun shouldReportIssue(key: String): Boolean {
        if (!enabled) return false
        val now = SystemClock.elapsedRealtime()
        val previous = lastIssueAt[key]
        if (previous != null && now - previous < ISSUE_REPORT_INTERVAL_MS) return false
        lastIssueAt[key] = now
        return true
    }

    private fun installationId(): String {
        val file = installationIdFile()
        val existing = runCatching { UUID.fromString(file.readText().trim()).toString() }.getOrNull()
        if (existing != null) return existing
        return UUID.randomUUID().toString().also { id ->
            runCatching { file.writeText(id) }
        }
    }

    private fun installationIdFile(): File = context.noBackupFilesDir.resolve(INSTALLATION_ID_FILE)

    private fun isDebuggable(): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun ConnectionState.telemetryValue(): String = when (this) {
        ConnectionState.Disconnected -> "disconnected"
        ConnectionState.Connecting -> "connecting"
        ConnectionState.ApplyingRoutingChanges -> "applying_routing_changes"
        ConnectionState.UpdatingRoutingData -> "updating_routing_data"
        is ConnectionState.RestartRequired -> "restart_required"
        is ConnectionState.InterfaceBusy -> "interface_busy"
        is ConnectionState.Connected -> "connected"
        ConnectionState.Disconnecting -> "disconnecting"
        is ConnectionState.Error -> "error"
    }

    private fun ConnectionProgress.telemetryValue(): String = when (this) {
        ConnectionProgress.PreparingRuntime -> "preparing_runtime"
        ConnectionProgress.PreparingCore -> "preparing_core"
        ConnectionProgress.UpdatingRoutingData -> "updating_routing_data"
        ConnectionProgress.ResolvingEntryServer -> "resolving_entry_server"
        ConnectionProgress.GeneratingConfiguration -> "generating_configuration"
        ConnectionProgress.StartingCore -> "starting_core"
        ConnectionProgress.ConfiguringTunnel -> "configuring_tunnel"
        ConnectionProgress.ConfiguringRouting -> "configuring_routing"
        ConnectionProgress.WaitingForCore -> "waiting_for_core"
        ConnectionProgress.StoppingCore -> "stopping_core"
        ConnectionProgress.CleaningRuntime -> "cleaning_runtime"
        ConnectionProgress.InspectingSavedRuntime -> "inspecting_saved_runtime"
        ConnectionProgress.VerifyingRuntime -> "verifying_runtime"
        ConnectionProgress.RestoringControlApi -> "restoring_control_api"
        ConnectionProgress.UpdatingNetworkRoute -> "updating_network_route"
        ConnectionProgress.UpdatingAppRouting -> "updating_app_routing"
    }

    private companion object {
        const val SENTRY_DSN =
            "https://d061f2516e2af78d352e5b5eed19108e@o4512086151397376.ingest.de.sentry.io/4512086156509264"
        const val SAFE_EVENT_LOGGER = "materialxray.telemetry"
        const val INSTALLATION_ID_FILE = "diagnostics-installation-id"
        const val ISSUE_REPORT_INTERVAL_MS = 15 * 60 * 1_000L
        const val MAX_BREADCRUMBS = 60
        const val DEBUG_TRACE_SAMPLE_RATE = 1.0
        const val RELEASE_TRACE_SAMPLE_RATE = 0.1
    }
}
