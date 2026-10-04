package com.material.xray.core.connection

import com.material.xray.core.common.log.AppLogger
import com.material.xray.core.connection.routing.TproxyManager
import com.material.xray.core.connection.routing.TproxyTrafficGroup
import com.material.xray.core.connection.routing.TproxyTrafficPlan
import com.material.xray.core.connection.routing.TunManager
import com.material.xray.core.model.ActiveBalancerSelection
import com.material.xray.core.model.ConnectionProgress
import com.material.xray.core.model.OtherVpnMode
import com.material.xray.core.model.RoutingRule
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.root.RootShell
import com.material.xray.core.telemetry.ConnectionTelemetryStep
import com.material.xray.core.telemetry.TelemetrySpan
import com.material.xray.core.xray.GeoDataStatus
import com.material.xray.core.xray.LocalSockets
import com.material.xray.core.xray.OtherVpnDns
import com.material.xray.core.xray.ProviderGeoDataResolution
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.TproxyPortAllocator
import com.material.xray.core.xray.TproxyRuntimeState
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.XrayApiFirewall
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.XrayRoutingClient
import com.material.xray.core.xray.XrayStatsClient
import com.material.xray.core.xray.XraySysStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ConnectionEnvironment {
    val binDir: String
    val appUid: Int
    val processId: Int
    val appInstallTime: Long

    fun elapsedRealtime(): Long
    fun describe(error: ConnectionError): String

    /** The private DNS zone of a VPN another app runs; root mode never owns a VPN itself. */
    fun otherVpnDns(): OtherVpnDns?

    /** The destinations that VPN routes, default routes included, as "address/length". */
    fun otherVpnRoutes(): List<String> = emptyList()
}

interface ConnectionRootRuntime {
    suspend fun open(): Boolean
    fun networkNamespaceName(): String
    suspend fun protectLoopbackApi(port: Int, appUid: Int): Boolean
    suspend fun readActiveConnectionCount(pid: Int): Int?
    suspend fun readProcessMetrics(pid: Int): ProcessMetrics?
}

class RootShellConnectionRuntime(
    private val shell: RootShell,
) : ConnectionRootRuntime {
    private val apiFirewall = XrayApiFirewall(shell)

    override suspend fun open(): Boolean = shell.open(RootShell.NetworkNamespace.INIT)

    override fun networkNamespaceName(): String = shell.defaultNetworkNamespace().name.lowercase()

    override suspend fun protectLoopbackApi(port: Int, appUid: Int): Boolean = apiFirewall.apply(port, appUid)

    override suspend fun readActiveConnectionCount(pid: Int): Int? = shell
        .execute("ls -l /proc/$pid/fd 2>/dev/null | grep -c 'socket:'")
        .output
        .trim()
        .toIntOrNull()

    override suspend fun readProcessMetrics(pid: Int): ProcessMetrics? = shell.execute(
        "rss=\$(awk '/^VmRSS:/ { print \$2 }' /proc/$pid/status 2>/dev/null); " +
            "sockets=\$(ls -l /proc/$pid/fd 2>/dev/null | grep -c 'socket:'); " +
            "printf 'rss_kb=%s\\nsockets=%s\\n' \"\$rss\" \"\$sockets\"",
    ).takeIf { it.isSuccess }?.output?.let(::parseProcessMetrics)
}

data class ProcessMetrics(
    val residentMemoryMb: Long?,
    val activeConnectionCount: Int?,
)

internal fun parseProcessMetrics(output: String): ProcessMetrics? {
    val values = output.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
    }.toMap()
    val rssKb = values["rss_kb"]?.toLongOrNull()
    val sockets = values["sockets"]?.toIntOrNull()
    if (rssKb == null && sockets == null) return null
    return ProcessMetrics(
        residentMemoryMb = rssKb?.let { (it + KILOBYTES_PER_MEBIBYTE - 1) / KILOBYTES_PER_MEBIBYTE },
        activeConnectionCount = sockets,
    )
}

private const val KILOBYTES_PER_MEBIBYTE = 1024L

interface ConnectionXrayBinary : XrayProcessBinary {
    suspend fun ensureAvailable(): Boolean
    suspend fun readConfig(): String?
    suspend fun readOverrideConfig(): String?
    suspend fun writeConfig(configJson: String)
}

// Checking the bundled core, and reading or writing config.json, are file operations that must not
// run on whichever thread the caller happens to be on; the service issues connection commands on
// the main thread.
class XrayBinaryConnectionAdapter(
    private val binary: XrayBinary,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionXrayBinary {
    override val binaryPath: String?
        get() = binary.binaryPath
    override val userCommand: List<String>?
        get() = binary.userCommand
    override val rootLauncherPath: String?
        get() = binary.rootLauncherPath

    override fun configPath(): String = binary.configPath()

    override suspend fun ensureAvailable(): Boolean = withContext(ioDispatcher) { binary.ensureAvailable() }

    override suspend fun readConfig(): String? = withContext(ioDispatcher) { binary.readConfig() }

    override suspend fun readOverrideConfig(): String? = withContext(ioDispatcher) { binary.readOverrideConfig() }

    override suspend fun writeConfig(configJson: String) {
        withContext(ioDispatcher) { binary.writeConfig(configJson) }
    }
}

interface ConnectionCleanup {
    suspend fun ensureCleanState(fallbackTunName: String = "xray0", preserveTproxyGuard: Boolean = false): Boolean
    suspend fun ensureKnownStateStopped(fallbackTunName: String = "xray0", preserveTproxyGuard: Boolean = false): Boolean
    fun recordKnownCleanState(): Boolean
    fun consumeKnownCleanState(): Boolean
}

data class ServerResolution(
    val server: ServerConfig,
    val attempted: Boolean,
    val selectedAddress: String?,
    val candidates: List<String>,
    val unresolvedHosts: List<String> = emptyList(),
)

interface ConnectionServerResolver {
    suspend fun resolve(server: ServerConfig, allowIpv6: Boolean): ServerResolution
}

class ServerAddressConnectionResolver(
    private val resolver: ServerAddressResolver,
) : ConnectionServerResolver {
    override suspend fun resolve(server: ServerConfig, allowIpv6: Boolean): ServerResolution = resolver
        .resolve(server, allowIpv6)
        .let { result ->
            ServerResolution(
                server = result.server,
                attempted = result.attempted,
                selectedAddress = result.selectedAddress,
                candidates = result.candidates,
                unresolvedHosts = result.unresolvedHosts,
            )
        }
}

interface ConnectionRoutingData {
    suspend fun needsRefresh(): Boolean
    suspend fun ensureReady(): GeoDataStatus
    suspend fun resolveProviderRules(rules: List<RoutingRule>): ProviderGeoDataResolution
}

@Suppress("TooManyFunctions") // A routing surface; each root operation is one method.
interface TproxyRoutingGateway {
    suspend fun createPlan(
        appRoutingPlan: AppRoutingPlan,
        routeTable: Int,
        allowIpv6: Boolean,
        existingState: TproxyRuntimeState? = null,
        tetherUpstreamInterface: String? = null,
        bypassLan: Boolean = true,
        otherVpnMode: OtherVpnMode = OtherVpnMode.default,
        otherVpnRoutes: List<String> = emptyList(),
    ): TproxyTrafficPlan

    suspend fun localAddressesChanged(): Boolean = false
    suspend fun readLocalAddresses(includeIpv6: Boolean): List<String>
    suspend fun installGuard(plan: TproxyTrafficPlan): TunManager.RoutingResult
    suspend fun activate(plan: TproxyTrafficPlan): TunManager.RoutingResult
    suspend fun update(plan: TproxyTrafficPlan, currentSlot: String): TunManager.RoutingResult
    suspend fun updateTetherAddresses(plan: TproxyTrafficPlan): TunManager.RoutingResult
    suspend fun updateTetherUpstream(plan: TproxyTrafficPlan, previousUpstream: String): TunManager.RoutingResult
    suspend fun verify(state: TproxyRuntimeState): TunManager.RoutingResult
    suspend fun verifyActivation(state: TproxyRuntimeState): TunManager.RoutingResult = verify(state)
    suspend fun checkHealth(state: TproxyRuntimeState): Boolean
    suspend fun removeGuard(): Boolean
    suspend fun syncOtherVpnRules(state: TproxyRuntimeState): TunManager.RoutingResult = TunManager.RoutingResult(success = true)
}

class TproxyManagerRoutingGateway(
    private val manager: TproxyManager,
    private val portAllocator: TproxyPortAllocator,
    private val appUid: Int,
) : TproxyRoutingGateway {
    override suspend fun createPlan(
        appRoutingPlan: AppRoutingPlan,
        routeTable: Int,
        allowIpv6: Boolean,
        existingState: TproxyRuntimeState?,
        tetherUpstreamInterface: String?,
        bypassLan: Boolean,
        otherVpnMode: OtherVpnMode,
        otherVpnRoutes: List<String>,
    ): TproxyTrafficPlan {
        val inboundTags = if (appRoutingPlan.proxyRoutes.isEmpty()) {
            appRoutingPlan.proxyServerIds.mapIndexed { index, routeKey ->
                existingState?.groups?.getOrNull(index + 1)?.inboundTag
                    ?: when (routeKey) {
                        Long.MIN_VALUE -> "app-in-default-selected"
                        Long.MIN_VALUE + 1 -> "app-in-always-proxied"
                        in Long.MIN_VALUE + 2..-1L -> "app-in-forced-${-routeKey}"
                        else -> "app-in-$routeKey"
                    }
            }
        } else {
            appRoutingPlan.proxyRoutes.map { it.inboundTag }
        }
        val routeIdentities = listOf(BASE_TPROXY_ROUTE_KEY to BASE_TPROXY_INBOUND_TAG) +
            appRoutingPlan.proxyServerIds.zip(inboundTags)
        val dynamicLocalAddresses = existingState?.dynamicLocalAddresses ?: if (tetherUpstreamInterface != null) {
            manager.supportsDynamicLocalAddresses(allowIpv6)
        } else {
            false
        }
        val state = existingState ?: TproxyManager.createRuntimeState(
            routeTable = routeTable + TPROXY_ROUTE_TABLE_OFFSET,
            groups = routeIdentities,
            ports = portAllocator.allocate(routeIdentities.size, allowIpv6),
            allowIpv6 = allowIpv6,
            tetherUpstreamInterface = tetherUpstreamInterface,
            bypassLan = bypassLan,
            localAddresses = if (tetherUpstreamInterface != null && !dynamicLocalAddresses) manager.readLocalAddresses(allowIpv6) else emptyList(),
            dynamicLocalAddresses = dynamicLocalAddresses,
            otherVpnMode = otherVpnMode,
            otherVpnRoutes = otherVpnRoutes,
        )
        require(state.groups.map { it.routeKey } == routeIdentities.map { it.first }) {
            "TPROXY traffic group topology changed"
        }
        val groups = buildList {
            add(TproxyTrafficGroup(state.groups.first(), emptySet(), isBase = true))
            state.groups.drop(1).zip(appRoutingPlan.tunRoutes).forEach { (group, route) ->
                add(TproxyTrafficGroup(group, route.uids))
            }
        }
        return TproxyTrafficPlan(
            runtimeState = state,
            groups = groups,
            bypassUids = appRoutingPlan.directUids + appUid,
            routeProfileIds = appRoutingPlan.routeProfileIds,
        )
    }

    override suspend fun localAddressesChanged(): Boolean = manager.localAddressesChanged()
    override suspend fun readLocalAddresses(includeIpv6: Boolean): List<String> = manager.readLocalAddresses(includeIpv6)

    override suspend fun installGuard(plan: TproxyTrafficPlan): TunManager.RoutingResult = manager.installGuard(plan)
    override suspend fun activate(plan: TproxyTrafficPlan): TunManager.RoutingResult = manager.activate(plan)
    override suspend fun update(plan: TproxyTrafficPlan, currentSlot: String): TunManager.RoutingResult = manager.update(plan, currentSlot)
    override suspend fun updateTetherAddresses(plan: TproxyTrafficPlan): TunManager.RoutingResult = manager.updateTetherAddresses(plan)
    override suspend fun updateTetherUpstream(plan: TproxyTrafficPlan, previousUpstream: String): TunManager.RoutingResult = manager.updateTetherUpstream(plan, previousUpstream)
    override suspend fun verify(state: TproxyRuntimeState): TunManager.RoutingResult = manager.verify(state)
    override suspend fun verifyActivation(state: TproxyRuntimeState): TunManager.RoutingResult = manager.verifyActivation(state)
    override suspend fun checkHealth(state: TproxyRuntimeState): Boolean = manager.checkHealth(state)
    override suspend fun removeGuard(): Boolean = manager.removeGuard()
    override suspend fun syncOtherVpnRules(state: TproxyRuntimeState): TunManager.RoutingResult = manager.syncOtherVpnRules(state)

    private companion object {
        const val TPROXY_ROUTE_TABLE_OFFSET = 200
        const val BASE_TPROXY_ROUTE_KEY = Long.MAX_VALUE
        const val BASE_TPROXY_INBOUND_TAG = "tproxy-in-default"
    }
}

interface ConnectionStatsClient : AutoCloseable {
    suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long>
    suspend fun getSysStats(): XraySysStats?
}

interface ConnectionRoutingClient : AutoCloseable {
    suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection?
}

data class ConnectionApiClients(
    val stats: ConnectionStatsClient,
    val routing: ConnectionRoutingClient,
)

fun interface ConnectionApiClientFactory {
    fun create(endpoint: XrayApiEndpoint): ConnectionApiClients
}

class XrayConnectionApiClientFactory(
    private val localSockets: LocalSockets,
    private val logger: AppLogger,
) : ConnectionApiClientFactory {
    override fun create(endpoint: XrayApiEndpoint): ConnectionApiClients = ConnectionApiClients(
        stats = XrayStatsClientAdapter(XrayStatsClient(endpoint, localSockets, logger)),
        routing = XrayRoutingClientAdapter(XrayRoutingClient(endpoint, localSockets, logger)),
    )
}

private class XrayStatsClientAdapter(
    private val client: XrayStatsClient,
) : ConnectionStatsClient {
    override suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long> = client.queryOutboundTrafficStatsBytes()
    override suspend fun getSysStats(): XraySysStats? = client.getSysStats()
    override fun close() = client.close()
}

private class XrayRoutingClientAdapter(
    private val client: XrayRoutingClient,
) : ConnectionRoutingClient {
    override suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection? = client.queryBalancerSelection(balancerTag)
    override fun close() = client.close()
}

data class ConnectionManagerDependencies(
    val environment: ConnectionEnvironment,
    val rootRuntime: ConnectionRootRuntime,
    val xrayBinary: ConnectionXrayBinary,
    val routingData: ConnectionRoutingData,
    val serverResolver: ConnectionServerResolver,
    val tunGateway: TunRoutingGateway,
    val tproxyGateway: TproxyRoutingGateway,
    val cleanup: ConnectionCleanup,
    val stateStore: ConnectionStateStore,
    val rootProcess: RootXrayProcessController,
    val userProcess: UserXrayProcessController,
    val diagnostics: ConnectionDiagnosticReporter,
    val routingPlanBuilder: RoutingPlanBuilder,
    val activeRouting: ActiveRoutingController,
    val apiClientFactory: ConnectionApiClientFactory,
    val xrayRoutingUpdater: ConnectionXrayRoutingUpdater,
    val prepareCertificateBundle: suspend () -> Unit,
    val startTelemetrySpan: (ConnectionProgress, ConnectionTelemetryStep?) -> TelemetrySpan? = { _, _ -> null },
    val recordTelemetryStepFailure: (ConnectionTelemetryStep) -> Unit = {},
)
