package com.material.xray.telemetry

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.RootConnectionBackend
import dagger.hilt.android.qualifiers.ApplicationContext
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
            options.maxBreadcrumbs = 0
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
    fun recordConnectionAttempt(mode: TelemetryServiceMode, backend: RootConnectionBackend) {
        count("connection.attempted", attributes(mode, backend))
        if (!enabled) return
        activeConnectionTrace?.finish(SpanStatus.ABORTED)
        activeConnectionTrace = Sentry.startTransaction("connection.setup", "connection").apply {
            attributes(mode, backend).forEach { (key, value) -> setTag(key, value.toString()) }
        }
    }

    @Synchronized
    fun recordConnectionResult(
        succeeded: Boolean,
        durationMillis: Long,
        mode: TelemetryServiceMode,
        backend: RootConnectionBackend,
    ) {
        val attributes = attributes(mode, backend)
        count(if (succeeded) "connection.succeeded" else "connection.failed", attributes)
        if (enabled) {
            Sentry.metrics().distribution(
                "connection.duration",
                durationMillis.toDouble(),
                MetricsUnit.Duration.MILLISECOND,
                SentryMetricsParameters.create(attributes),
            )
            activeConnectionTrace?.finish(if (succeeded) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)
            activeConnectionTrace = null
        }
    }

    @Synchronized
    internal fun startConnectionStep(progress: ConnectionProgress): TelemetrySpan? {
        if (!enabled) return null
        val span = activeConnectionTrace?.startChild("connection.step", progress.telemetryValue()) ?: return null
        return TelemetrySpan { succeeded ->
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
        if (cause == CoreRecoveryCause.ProcessExit && shouldReportIssue("core_exit")) {
            captureSafeMessage("Xray core exited unexpectedly", "core-exit")
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

    private fun captureSafeMessage(message: String, fingerprint: String) {
        if (!enabled) return
        val event = io.sentry.SentryEvent().apply {
            level = SentryLevel.ERROR
            logger = SAFE_EVENT_LOGGER
            this.message = Message().apply { formatted = message }
            fingerprints = listOf(fingerprint)
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

    private fun attributes(
        mode: TelemetryServiceMode,
        backend: RootConnectionBackend,
    ): Map<String, Any> = mapOf(
        "service_mode" to mode.value,
        "root_backend" to if (mode == TelemetryServiceMode.Root) backend.persistedValue else "none",
    )

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
        const val DEBUG_TRACE_SAMPLE_RATE = 1.0
        const val RELEASE_TRACE_SAMPLE_RATE = 0.1
    }
}
