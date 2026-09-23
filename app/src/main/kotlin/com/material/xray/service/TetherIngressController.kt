package com.material.xray.service

import com.material.xray.core.xray.TetherIngressState
import com.material.xray.core.xray.TproxyRuntimeState
import com.material.xray.core.xray.TproxyTrafficGroup
import com.material.xray.core.xray.TproxyTrafficPlan

/** Owns live tether rule transitions; the saved setting only requests this runtime feature. */
internal class TetherIngressController(
    private val gateway: TproxyRoutingGateway,
    private val log: LogBuffer,
) {
    @Volatile var phase: TetherIngressState = TetherIngressState.Disabled
        private set

    suspend fun refreshAddresses(current: TproxyRuntimeState): TproxyRuntimeState? {
        val active = current.tetherIngress as? TetherIngressState.Active ?: return null
        phase = TetherIngressState.Preparing(active.upstream)
        var completed = current
        try {
            val addresses = gateway.readLocalAddresses(current.ipv6Enabled)
            val updated = current.copy(localAddresses = addresses, tetherChainSlot = current.nextTetherChainSlot())
            val plan = TproxyTrafficPlan(
                runtimeState = updated,
                groups = updated.groups.mapIndexed { index, group ->
                    TproxyTrafficGroup(group, emptySet(), isBase = index == 0)
                },
                bypassUids = emptySet(),
                routeProfileIds = emptySet(),
            )
            val result = gateway.updateTetherAddresses(plan)
            if (!result.success) {
                log.append(LogSource.APP, "Tether address update failed: ${result.error}")
                return null
            }
            val verified = gateway.verify(updated)
            if (!verified.success) {
                log.append(LogSource.APP, "Tether address verification failed: ${verified.error}")
                return null
            }
            completed = updated
            return updated
        } finally {
            phase = completed.tetherIngress
        }
    }

    suspend fun retargetUpstream(plan: TproxyTrafficPlan, previousUpstream: String): Boolean {
        val next = plan.runtimeState.tetherIngress as? TetherIngressState.Active ?: return false
        phase = TetherIngressState.Preparing(next.upstream)
        var completed = TetherIngressState.Active(previousUpstream, plan.runtimeState.dynamicLocalAddresses)
        try {
            val result = gateway.updateTetherUpstream(plan, previousUpstream)
            if (!result.success) {
                log.append(LogSource.APP, "Tether upstream update failed: ${result.error}")
                return false
            }
            completed = next
            return true
        } finally {
            phase = completed
        }
    }
}
