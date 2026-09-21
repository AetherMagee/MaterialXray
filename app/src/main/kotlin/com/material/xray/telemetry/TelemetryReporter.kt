package com.material.xray.telemetry

import android.os.SystemClock
import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import com.material.xray.model.RootConnectionBackend
import javax.inject.Inject
import javax.inject.Singleton

enum class TelemetryServiceMode(val value: String) {
    Root("root"),
    Vpn("vpn"),
}

enum class ConnectionOutcome(val value: String) {
    Success("success"),
    Failure("failure"),
    Interrupted("interrupted"),
}

enum class ConnectionFailureStage(val value: String) {
    Preparation("preparation"),
    Cleanup("cleanup"),
    Configuration("configuration"),
    Core("core"),
    Tunnel("tunnel"),
    Routing("routing"),
    Api("api"),
    Unknown("unknown"),
}

enum class ConnectionFailureReason(val value: String) {
    ServerRoutingRefreshFailed("server_routing_refresh_failed"),
    RuntimeSettingsLoadFailed("runtime_settings_load_failed"),
    RootAccessUnavailable("root_access_unavailable"),
    VpnInterfaceSetupFailed("vpn_interface_setup_failed"),
    PreviousRuntimeCleanupFailed("previous_runtime_cleanup_failed"),
    TunInterfaceDetectionFailed("tun_interface_detection_failed"),
    RuntimeLogPreparationFailed("runtime_log_preparation_failed"),
    TproxyGuardInstallFailed("tproxy_guard_install_failed"),
    ApiAccessSetupFailed("api_access_setup_failed"),
    CoreBinarySetupFailed("core_binary_setup_failed"),
    RoutingDataSetupFailed("routing_data_setup_failed"),
    PhysicalRouteDetectionFailed("physical_route_detection_failed"),
    ServerResolutionFailed("server_resolution_failed"),
    AppRoutingPlanFailed("app_routing_plan_failed"),
    ApiClientSetupFailed("api_client_setup_failed"),
    ConfigGenerationFailed("config_generation_failed"),
    ConfigWriteFailed("config_write_failed"),
    CoreLaunchFailed("core_launch_failed"),
    TunSetupFailed("tun_setup_failed"),
    TproxyActivationFailed("tproxy_activation_failed"),
    ApiReadinessFailed("api_readiness_failed"),
    TproxyVerificationFailed("tproxy_verification_failed"),
    TproxyGuardRemovalFailed("tproxy_guard_removal_failed"),
    AppTunSetupFailed("app_tun_setup_failed"),
    RootRoutingFailed("root_routing_failed"),
    Unknown("unknown"),
}

enum class ConnectionTelemetryStep(
    val value: String,
    internal val failureStage: ConnectionFailureStage,
    internal val failureReason: ConnectionFailureReason,
) {
    RefreshServerRouting(
        "routing.server.refresh",
        ConnectionFailureStage.Preparation,
        ConnectionFailureReason.ServerRoutingRefreshFailed,
    ),
    LoadRuntimeSettings(
        "runtime.settings.load",
        ConnectionFailureStage.Preparation,
        ConnectionFailureReason.RuntimeSettingsLoadFailed,
    ),
    RootAccess("root.access", ConnectionFailureStage.Preparation, ConnectionFailureReason.RootAccessUnavailable),
    VpnInterface("vpn.interface.establish", ConnectionFailureStage.Tunnel, ConnectionFailureReason.VpnInterfaceSetupFailed),
    CleanupPreviousRuntime(
        "runtime.cleanup",
        ConnectionFailureStage.Cleanup,
        ConnectionFailureReason.PreviousRuntimeCleanupFailed,
    ),
    DetectTunInterface(
        "tun.interface.detect",
        ConnectionFailureStage.Tunnel,
        ConnectionFailureReason.TunInterfaceDetectionFailed,
    ),
    PrepareLog(
        "runtime.log.prepare",
        ConnectionFailureStage.Preparation,
        ConnectionFailureReason.RuntimeLogPreparationFailed,
    ),
    InstallTproxyGuard(
        "tproxy.guard.install",
        ConnectionFailureStage.Routing,
        ConnectionFailureReason.TproxyGuardInstallFailed,
    ),
    PrepareApiAccess("api.access.prepare", ConnectionFailureStage.Api, ConnectionFailureReason.ApiAccessSetupFailed),
    PrepareCoreBinary("core.binary.prepare", ConnectionFailureStage.Core, ConnectionFailureReason.CoreBinarySetupFailed),
    PrepareRoutingData(
        "routing.data.prepare",
        ConnectionFailureStage.Preparation,
        ConnectionFailureReason.RoutingDataSetupFailed,
    ),
    DetectPhysicalRoute(
        "network.route.detect",
        ConnectionFailureStage.Routing,
        ConnectionFailureReason.PhysicalRouteDetectionFailed,
    ),
    ResolveServer("server.resolve", ConnectionFailureStage.Preparation, ConnectionFailureReason.ServerResolutionFailed),
    BuildAppRouting("routing.app.plan", ConnectionFailureStage.Routing, ConnectionFailureReason.AppRoutingPlanFailed),
    CreateApiClients("api.clients.create", ConnectionFailureStage.Api, ConnectionFailureReason.ApiClientSetupFailed),
    GenerateConfig(
        "config.generate",
        ConnectionFailureStage.Configuration,
        ConnectionFailureReason.ConfigGenerationFailed,
    ),
    WriteConfig("config.write", ConnectionFailureStage.Configuration, ConnectionFailureReason.ConfigWriteFailed),
    LaunchCore("core.process.launch", ConnectionFailureStage.Core, ConnectionFailureReason.CoreLaunchFailed),
    ConfigureTun("tun.root.configure", ConnectionFailureStage.Tunnel, ConnectionFailureReason.TunSetupFailed),
    ActivateTproxy("tproxy.activate", ConnectionFailureStage.Routing, ConnectionFailureReason.TproxyActivationFailed),
    WaitForApi("api.ready", ConnectionFailureStage.Api, ConnectionFailureReason.ApiReadinessFailed),
    VerifyTproxy("tproxy.verify", ConnectionFailureStage.Routing, ConnectionFailureReason.TproxyVerificationFailed),
    RemoveTproxyGuard(
        "tproxy.guard.remove",
        ConnectionFailureStage.Cleanup,
        ConnectionFailureReason.TproxyGuardRemovalFailed,
    ),
    ConfigureAppTun("tun.app.configure", ConnectionFailureStage.Tunnel, ConnectionFailureReason.AppTunSetupFailed),
    ApplyRootRouting("routing.root.apply", ConnectionFailureStage.Routing, ConnectionFailureReason.RootRoutingFailed),
}

enum class CoreRecoveryCause(val value: String) {
    ProcessExit("process_exit"),
    MemoryLimit("memory_limit"),
    TunnelUnavailable("tunnel_unavailable"),
    RuntimeModeChanged("runtime_mode_changed"),
}

private enum class TproxyCompatibilityResult(val value: String) {
    Supported("supported"),
    Unsupported("unsupported"),
}

fun interface TelemetrySpan {
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
class TelemetryReporter internal constructor(
    private val client: TelemetryClient,
    private val elapsedRealtime: () -> Long,
) {
    @Inject constructor(client: TelemetryClient) : this(client, SystemClock::elapsedRealtime)

    @Volatile private var enabled = false
    private val lastIssueAt = mutableMapOf<String, Long>()
    private var activeConnectionTrace: TelemetryTransaction? = null
    private var activeConnectionContext: TelemetryConnectionContext? = null
    private var activeConnectionAttemptCounted = false
    private var activeConnectionFailure: Pair<ConnectionFailureStage, ConnectionFailureReason>? = null
    private var connectionAttemptActive = false

    @Synchronized
    fun setEnabled(enable: Boolean) {
        if (enable == enabled) return
        if (!enable) {
            enabled = false
            activeConnectionTrace?.finish(TelemetryStatus.Cancelled)
            activeConnectionTrace = null
            activeConnectionContext = null
            activeConnectionAttemptCounted = false
            activeConnectionFailure = null
            connectionAttemptActive = false
            client.disable()
            lastIssueAt.clear()
            return
        }

        client.enable()
        enabled = true
    }

    @Synchronized
    fun recordConnectionAttempt(connection: TelemetryConnectionContext) {
        if (!enabled) return
        activeConnectionContext = connection
        activeConnectionAttemptCounted = false
        activeConnectionFailure = null
        connectionAttemptActive = true
        connection.scopeTags.forEach(client::setTag)
        addBreadcrumb("connection", "attempt", connection.metricAttributes)
        activeConnectionTrace?.finish(TelemetryStatus.Aborted)
        activeConnectionTrace = client.startTransaction("connection.setup", "connection").apply {
            connection.metricAttributes.forEach { (key, value) -> setTag(key, value.toString()) }
        }
    }

    @Synchronized
    fun recordConnectionCompletion(
        outcome: ConnectionOutcome,
        durationMillis: Long,
    ) {
        val connection = activeConnectionContext ?: return
        recordActiveConnectionAttempt()
        val attributes = connection.metricAttributes +
            ("outcome" to outcome.value) +
            failureAttributes(outcome)
        count("connection.completed", attributes)
        if (enabled) {
            client.distributionMillis("connection.duration", durationMillis, attributes)
            addBreadcrumb(
                "connection",
                outcome.value,
                mapOf("duration_ms" to durationMillis),
            )
            activeConnectionTrace?.finish(outcome.telemetryStatus())
            activeConnectionTrace = null
            activeConnectionContext = null
            activeConnectionAttemptCounted = false
            activeConnectionFailure = null
            connectionAttemptActive = false
        }
    }

    fun recordConnectionState(state: ConnectionState) {
        if (!enabled) return
        val value = state.telemetryValue()
        client.setTag("connection_state", value)
        client.setTag("core_running", (state is ConnectionState.Connected).toString())
        val data = if (state is ConnectionState.Error) mapOf("retryable" to state.retryable) else emptyMap()
        addBreadcrumb("connection.state", value, data)
    }

    fun recordTproxyCompatibility(result: TproxyCompatibility, cached: Boolean) {
        val attributes = when (result) {
            is TproxyCompatibility.Supported -> mapOf(
                "result" to TproxyCompatibilityResult.Supported.value,
                "reason" to "none",
                "ipv6_supported" to result.ipv6,
                "cached" to cached,
            )
            is TproxyCompatibility.Unsupported -> mapOf(
                "result" to TproxyCompatibilityResult.Unsupported.value,
                "reason" to result.reason.telemetryValue(),
                "ipv6_supported" to false,
                "cached" to cached,
            )
            TproxyCompatibility.Checking,
            TproxyCompatibility.Unknown,
            -> return
        }
        count("tproxy.compatibility.checked", attributes)
        addBreadcrumb("tproxy.compatibility", attributes.getValue("result").toString(), attributes)

        val malfunction = (result as? TproxyCompatibility.Unsupported).takeUnless { cached }
        if (malfunction?.reason?.isProbeMalfunction() != true) return
        val reason = malfunction.reason.telemetryValue()
        if (!shouldReportIssue("tproxy_compatibility_$reason")) return
        captureSafeMessage(
            message = "TPROXY compatibility probe malfunctioned",
            fingerprint = "tproxy-compatibility-$reason",
            tags = mapOf("reason" to reason),
        )
    }

    @Synchronized
    internal fun startConnectionStep(
        progress: ConnectionProgress,
        telemetryStep: ConnectionTelemetryStep?,
    ): TelemetrySpan? {
        if (!enabled) return null
        val step = telemetryStep?.value ?: progress.telemetryValue()
        val span = activeConnectionTrace?.startChild("connection.step", step) ?: return null
        return TelemetrySpan { succeeded ->
            addBreadcrumb("connection.step", step, mapOf("succeeded" to succeeded))
            span.finish(succeeded)
        }
    }

    @Synchronized
    internal fun recordConnectionStepFailure(step: ConnectionTelemetryStep) {
        if (!enabled || !connectionAttemptActive || activeConnectionFailure != null) return
        activeConnectionFailure = step.failureStage to step.failureReason
    }

    @Synchronized
    internal fun updateConnectionContext(
        connection: TelemetryConnectionContext,
        clearPriorFailure: Boolean = false,
    ) {
        if (!enabled || !connectionAttemptActive) return
        activeConnectionContext = connection
        if (clearPriorFailure) activeConnectionFailure = null
        connection.scopeTags.forEach(client::setTag)
        activeConnectionTrace?.apply {
            connection.metricAttributes.forEach { (key, value) -> setTag(key, value.toString()) }
        }
        recordActiveConnectionAttempt()
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
        client.captureException(
            error = error,
            fingerprint = listOf("unexpected-connection-command", error.javaClass.name),
            tags = mapOf("failure_category" to "unexpected_connection_command"),
        )
    }

    private fun count(name: String, attributes: Map<String, Any>) {
        if (!enabled) return
        client.count(name, attributes)
    }

    private fun recordActiveConnectionAttempt() {
        if (activeConnectionAttemptCounted) return
        val connection = activeConnectionContext ?: return
        count("connection.attempted", connection.metricAttributes)
        activeConnectionAttemptCounted = true
    }

    private fun addBreadcrumb(category: String, message: String, data: Map<String, Any>) {
        if (!enabled) return
        client.addBreadcrumb(category, message, data)
    }

    private fun captureSafeMessage(message: String, fingerprint: String, tags: Map<String, String>) {
        if (!enabled) return
        client.captureMessage(message, fingerprint, tags)
    }

    @Synchronized
    private fun shouldReportIssue(key: String): Boolean {
        if (!enabled) return false
        val now = elapsedRealtime()
        val previous = lastIssueAt[key]
        if (previous != null && now - previous < ISSUE_REPORT_INTERVAL_MS) return false
        lastIssueAt[key] = now
        return true
    }

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

    private fun ConnectionOutcome.telemetryStatus(): TelemetryStatus = when (this) {
        ConnectionOutcome.Success -> TelemetryStatus.Ok
        ConnectionOutcome.Failure -> TelemetryStatus.InternalError
        ConnectionOutcome.Interrupted -> TelemetryStatus.Aborted
    }

    private fun failureAttributes(outcome: ConnectionOutcome): Map<String, Any> {
        if (outcome != ConnectionOutcome.Failure) return emptyMap()
        val (stage, reason) = activeConnectionFailure
            ?: (ConnectionFailureStage.Unknown to ConnectionFailureReason.Unknown)
        return mapOf(
            "failure_stage" to stage.value,
            "failure_reason" to reason.value,
        )
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

    private fun TproxyCompatibility.Reason.isProbeMalfunction(): Boolean = when (this) {
        TproxyCompatibility.Reason.ProbeCleanupFailed,
        TproxyCompatibility.Reason.CommandTimedOut,
        -> true
        else -> false
    }

    private fun TproxyCompatibility.Reason.telemetryValue(): String = when (this) {
        TproxyCompatibility.Reason.RootUnavailable -> "root_unavailable"
        TproxyCompatibility.Reason.InitNetworkNamespaceUnavailable -> "init_network_namespace_unavailable"
        TproxyCompatibility.Reason.IptablesMangleUnavailable -> "iptables_mangle_unavailable"
        TproxyCompatibility.Reason.ProcessGroupUnavailable -> "process_group_unavailable"
        TproxyCompatibility.Reason.OwnerMatchUnavailable -> "owner_match_unavailable"
        TproxyCompatibility.Reason.MarkTargetUnavailable -> "mark_target_unavailable"
        TproxyCompatibility.Reason.TproxyIpv4Unavailable -> "tproxy_ipv4_unavailable"
        TproxyCompatibility.Reason.Ipv6BlockingUnavailable -> "ipv6_blocking_unavailable"
        TproxyCompatibility.Reason.ListenerInspectionUnavailable -> "listener_inspection_unavailable"
        TproxyCompatibility.Reason.PolicyRoutingUnavailable -> "policy_routing_unavailable"
        TproxyCompatibility.Reason.RouteTableConflict -> "route_table_conflict"
        TproxyCompatibility.Reason.TproxyIpv6Unavailable -> "tproxy_ipv6_unavailable"
        TproxyCompatibility.Reason.MarkNamespaceConflict -> "mark_namespace_conflict"
        TproxyCompatibility.Reason.ProbeCleanupFailed -> "probe_cleanup_failed"
        TproxyCompatibility.Reason.CommandTimedOut -> "command_timed_out"
    }

    private companion object {
        const val ISSUE_REPORT_INTERVAL_MS = 15 * 60 * 1_000L
    }
}
