package com.material.xray.core.connection

import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.connection.routing.TunManager
import com.material.xray.core.connection.routing.followingOtherVpn
import com.material.xray.core.model.ConnectionProgress
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.model.OtherVpnMode
import com.material.xray.core.model.RootConnectionBackend
import com.material.xray.core.model.XrayRuntimeSettings
import com.material.xray.core.xray.TetherIngressState
import com.material.xray.core.xray.TproxyRuntimeState
import com.material.xray.core.xray.XrayState

/**
 * Keeps a running TPROXY firewall in step with app routing, other VPNs, tethering and the upstream
 * network, rewriting it in place so the core keeps running.
 */
internal class ActiveTproxyRouting(
    private val gateway: TproxyRoutingGateway,
    private val stateStore: ConnectionStateStore,
    private val appRoutingPlanner: RoutingPlanBuilder,
    private val environment: ConnectionEnvironment,
    private val log: LogBuffer,
    private val stepExecutor: ConnectionStepExecutor,
    private val isProcessAlive: suspend (pid: Int) -> Boolean,
    private val isRootProcessAlive: suspend (pid: Int) -> Boolean,
) {
    private val tetherIngress = TetherIngressController(gateway, log)

    private var lastAuditAt = 0L

    /** Records that the firewall just passed a full verification. */
    fun markAudited() {
        lastAuditAt = environment.elapsedRealtime()
    }

    /** Runs the full verification, which also counts as the periodic audit when it passes. */
    suspend fun verify(state: TproxyRuntimeState): Boolean = gateway.verify(state).success.also { healthy ->
        if (healthy) markAudited()
    }

    /** The quick health check, with a full verification at most every [TPROXY_FULL_AUDIT_INTERVAL_MS]. */
    suspend fun isHealthy(state: TproxyRuntimeState): Boolean {
        val now = environment.elapsedRealtime()
        val auditDue = now - lastAuditAt >= TPROXY_FULL_AUDIT_INTERVAL_MS
        return if (!auditDue && gateway.checkHealth(state)) {
            true
        } else {
            gateway.verify(state).success.also { healthy ->
                if (healthy) lastAuditAt = now
            }
        }
    }

    suspend fun refreshTetherAddresses(): Boolean {
        val state = stateStore.read() ?: return false
        val current = state.tproxy?.takeIf { it.tetherIngress is TetherIngressState.Active } ?: return false
        if (!isProcessAlive(state.xrayPid)) return false
        val updated = tetherIngress.refreshAddresses(current) ?: return false
        stateStore.write(state.copy(tproxy = updated))
        log.append(LogSource.APP, "Tether addresses updated without restarting Xray")
        return true
    }

    suspend fun isTetherIngressActive(): Boolean = stateStore.read()?.tproxy?.tetherIngress is TetherIngressState.Active

    suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean {
        val persistedState = stateStore.read() ?: return false
        val tproxyState = persistedState.tproxy ?: return false
        return updateOutput(connectedState, runtimeSettings, persistedState, tproxyState)
    }

    /**
     * Keeps the running TPROXY firewall in step with another app's VPN as it comes, goes or
     * changes its routes. No mode needs the core restarted for that. The mode is the one the
     * firewall was built with; a changed setting arrives with the reconnect it triggers.
     */
    suspend fun followOtherVpnRouting(connectedState: ConnectionState.Connected, runtimeSettings: XrayRuntimeSettings) {
        val persistedState = stateStore.read() ?: return
        if (persistedState.rootConnectionBackend != RootConnectionBackend.Tproxy) return
        val tproxyState = persistedState.tproxy ?: return
        if (tproxyState.otherVpnMode == OtherVpnMode.TunnelInTunnel) {
            syncOtherVpnRules(tproxyState)
            return
        }
        val next = tproxyState.followingOtherVpn(environment.otherVpnRoutes())
        if (next == tproxyState) return
        if (!updateOutput(connectedState, runtimeSettings, persistedState, next)) {
            log.append(LogSource.APP, "Could not follow the other VPN's routes")
            return
        }
        if (next.otherVpnRoutes != tproxyState.otherVpnRoutes) {
            log.append(LogSource.APP, "Other VPN routes changed: ${next.otherVpnRoutes.joinToString().ifEmpty { "none" }}")
        }
        if (next.standDownRoutes != tproxyState.standDownRoutes) {
            val routes = next.standDownRoutes.joinToString()
            log.append(LogSource.APP, if (routes.isEmpty()) "No longer standing down for the other VPN" else "Standing down for the other VPN: $routes")
        }
    }

    suspend fun syncOtherVpnRules(state: TproxyRuntimeState) {
        val result = gateway.syncOtherVpnRules(state)
        if (!result.success) log.append(LogSource.APP, "Could not mirror other VPN rules: ${result.error ?: "unknown error"}")
    }

    /** Moves the persisted TPROXY runtime onto [physicalRoute], retargeting tether ingress when the upstream changed. */
    suspend fun updatePhysicalRoute(
        connectedState: ConnectionState.Connected,
        physicalRoute: TunManager.PhysicalRoute,
        runtimeSettings: XrayRuntimeSettings,
        persistedState: XrayState,
    ): PhysicalRouteUpdateResult {
        val tproxyState = persistedState.tproxy ?: return PhysicalRouteUpdateResult.RequiresReconnect
        if (!isRootProcessAlive(connectedState.corePid)) return PhysicalRouteUpdateResult.RequiresReconnect
        val previousUpstream = tproxyState.tetherUpstreamInterface
        val updatedTproxy = if (previousUpstream != null && previousUpstream != physicalRoute.dev) {
            val addresses = if (tproxyState.dynamicLocalAddresses) {
                emptyList()
            } else {
                gateway.readLocalAddresses(tproxyState.ipv6Enabled)
            }
            val updated = tproxyState.copy(
                tetherUpstreamInterface = physicalRoute.dev,
                localAddresses = addresses,
                tetherChainSlot = tproxyState.nextTetherChainSlot(),
            )
            val appPlan = appRoutingPlanner.build(
                baseRouteTable = runtimeSettings.routeTable,
                includeProxyRoutes = false,
                includeTunRoutes = true,
                includeDefaultSelectedRoute = !runtimeSettings.usesProxyAsRoutingDefault(),
                allowIpv6 = runtimeSettings.allowIpv6,
            )
            val plan = gateway.createPlan(
                appRoutingPlan = appPlan,
                routeTable = runtimeSettings.routeTable,
                allowIpv6 = runtimeSettings.allowIpv6,
                existingState = updated,
                tetherUpstreamInterface = physicalRoute.dev,
                bypassLan = runtimeSettings.bypassLan,
            )
            if (!tetherIngress.retargetUpstream(plan, previousUpstream)) {
                return PhysicalRouteUpdateResult.RequiresReconnect
            }
            log.append(LogSource.APP, "Tether upstream changed to ${physicalRoute.dev} without restarting Xray")
            updated
        } else {
            tproxyState
        }
        stateStore.write(
            persistedState.copy(
                physicalInterface = physicalRoute.dev,
                physicalGateway = physicalRoute.gateway,
                physicalTable = physicalRoute.table,
                tproxy = updatedTproxy,
            ),
        )
        return PhysicalRouteUpdateResult.Applied(physicalRoute)
    }

    /** Rebuilds the inactive output chain from [tproxyState] and swaps it in. */
    private suspend fun updateOutput(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
        persistedState: XrayState,
        tproxyState: TproxyRuntimeState,
    ): Boolean {
        if (persistedState.appProxyServerIds.isEmpty() && tproxyState.groups.size > 1) return false
        if (!isProcessAlive(connectedState.corePid)) return false
        val appRoutingPlan = appRoutingPlanner.build(
            baseRouteTable = runtimeSettings.routeTable,
            includeProxyRoutes = false,
            includeTunRoutes = true,
            includeDefaultSelectedRoute = !runtimeSettings.usesProxyAsRoutingDefault(),
            allowIpv6 = runtimeSettings.allowIpv6,
        )
        if (appRoutingPlan.proxyServerIds != persistedState.appProxyServerIds) return false
        val plan = gateway.createPlan(
            appRoutingPlan = appRoutingPlan,
            routeTable = runtimeSettings.routeTable,
            allowIpv6 = runtimeSettings.allowIpv6,
            existingState = tproxyState,
            tetherUpstreamInterface = tproxyState.tetherUpstreamInterface,
            bypassLan = tproxyState.bypassLan,
        )
        val result = stepExecutor.execute(
            ConnectionStep(
                "TPROXY app routing update",
                ConnectionProgress.UpdatingAppRouting,
                isSuccessful = { it.success },
                action = { gateway.update(plan, tproxyState.outputChainSlot) },
            ),
        )
        if (!result.success) {
            log.append(LogSource.APP, "Fast TPROXY app routing update skipped: ${result.error ?: "unknown error"}")
            return false
        }
        val nextSlot = if (tproxyState.outputChainSlot == "a") "b" else "a"
        stateStore.write(
            persistedState.copy(
                tproxy = tproxyState.copy(outputChainSlot = nextSlot),
                ipRulesApplied = true,
            ),
        )
        return true
    }
}

private const val TPROXY_FULL_AUDIT_INTERVAL_MS = 10 * 60_000L
