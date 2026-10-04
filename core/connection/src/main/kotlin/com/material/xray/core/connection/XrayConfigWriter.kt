package com.material.xray.core.connection

import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.connection.routing.TproxyTrafficPlan
import com.material.xray.core.connection.routing.TunManager
import com.material.xray.core.model.ConnectionProgress
import com.material.xray.core.model.RootConnectionBackend
import com.material.xray.core.model.RoutingRule
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.model.XrayOutbound
import com.material.xray.core.model.XrayRuntimeSettings
import com.material.xray.core.telemetry.ConnectionTelemetryStep
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.OtherVpnDns
import com.material.xray.core.xray.PROTECTED_FROM_VPN_MARK
import com.material.xray.core.xray.TUN_INBOUND_TAG
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.XrayInbound
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Writes the core's config for a connect, and keeps the inputs of the running one in [active] so
 * routing changes can be swapped into the live core without a restart.
 */
internal class XrayConfigWriter(
    private val configGenerator: ConfigGenerator,
    private val environment: ConnectionEnvironment,
    private val xrayBinary: ConnectionXrayBinary,
    private val routingData: ConnectionRoutingData,
    private val routingUpdater: ConnectionXrayRoutingUpdater,
    private val log: LogBuffer,
    private val stepExecutor: ConnectionStepExecutor,
    private val defaultDispatcher: CoroutineDispatcher,
) {
    /** Inputs of the config the running core was started with; null with no core or a hand-edited config. */
    var active: GeneratedXrayConfig? = null

    /** Points provider rules at the provider's geodata, leaving out what no available file defines. */
    suspend fun resolveProviderRoutingRules(rules: List<RoutingRule>): List<RoutingRule> {
        val resolution = routingData.resolveProviderRules(rules)
        if (resolution.usedUrls.isNotEmpty()) {
            log.append(LogSource.APP, "Using provider routing data: ${resolution.usedUrls.joinToString()}")
        }
        if (resolution.unavailableUrls.isNotEmpty()) {
            log.append(
                LogSource.APP,
                "Provider routing data not downloaded yet, using compatibility mode: " +
                    resolution.unavailableUrls.joinToString(),
            )
        }
        if (resolution.droppedEntries.isNotEmpty()) {
            log.append(
                LogSource.APP,
                "Left out provider routing entries missing from routing data: " +
                    resolution.droppedEntries.joinToString(),
            )
        }
        return resolution.rules
    }

    /** Writes the config for a connect. Returns null when the hand-edited config was written instead. */
    suspend fun write(
        xrayServer: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        managesSystemRouting: Boolean,
        rootBackend: RootConnectionBackend,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        xrayApiEndpoint: XrayApiEndpoint,
        tproxyPlan: TproxyTrafficPlan?,
        syntheticDnsAddress: String?,
    ): GeneratedXrayConfig? {
        val tproxyInbounds = tproxyPlan?.runtimeState?.groups?.map { group ->
            XrayInbound.Tproxy(
                port = group.port,
                tag = group.inboundTag,
                allowIpv6 = runtimeSettings.allowIpv6,
                acceptNonLoopback = tproxyPlan.runtimeState.tetherUpstreamInterface != null,
            )
        }
        val effectiveInbounds = if (runtimeSettings.routeMxrayTrafficThroughXray) {
            val trafficInbounds: List<XrayInbound> = tproxyInbounds
                ?: listOf(XrayInbound.Tun(runtimeSettings.tunName, TUN_INBOUND_TAG, runtimeSettings.tunMtu))
            trafficInbounds + XrayInbound.PrivateHttp(
                path = "${environment.binDir}/mxray-http-${java.util.UUID.randomUUID().toString().take(12)}.sock",
            )
        } else {
            tproxyInbounds
        }
        // Xray's GID-exempt sockets follow Android's current default route in both local and
        // tethered TPROXY. The tether firewall still tracks the upstream separately.
        val unboundTproxy = tproxyPlan != null
        // Root sits inside other VPNs' uid ranges, so without protection a full-tunnel VPN would
        // capture the core's own connections and loop them back into it.
        val outboundMark = when {
            tproxyPlan != null -> PROTECTED_FROM_VPN_MARK
            managesSystemRouting -> runtimeSettings.fwmark
            else -> 0
        }

        // A hand-edited config replaces generation wholesale, but not the identifiers this connect
        // just allocated: the API endpoint and the inbounds have to be the current ones or the
        // stats clients talk to nothing and the firewall rule guards the wrong port.
        if (
            writeOverride(
                runtimeSettings,
                xrayApiEndpoint,
                effectiveInbounds,
                outboundMark = PROTECTED_FROM_VPN_MARK.takeIf { tproxyPlan != null },
                clearOutboundInterfaces = unboundTproxy,
            )
        ) {
            return null
        }

        val followsOtherVpnDns = tproxyPlan != null &&
            !configGenerator.usesProfileDns(xrayServer, runtimeSettings.preferProfileDns)
        val generatedConfig = GeneratedXrayConfig(
            server = xrayServer,
            runtimeSettings = runtimeSettings,
            managesSystemRouting = managesSystemRouting,
            rootBackend = rootBackend,
            fwmark = outboundMark,
            followsOtherVpnDns = followsOtherVpnDns,
            otherVpnDns = if (followsOtherVpnDns) environment.otherVpnDns()?.forIpv6(runtimeSettings.allowIpv6) else null,
            appRoutingPlan = appRoutingPlan,
            physicalRoute = physicalRoute.takeUnless { unboundTproxy },
            xrayApiEndpoint = xrayApiEndpoint,
            syntheticDnsAddress = syntheticDnsAddress,
            inbounds = effectiveInbounds,
        )

        val configJson = stepExecutor.execute(
            ConnectionStep(
                "Config generation",
                ConnectionProgress.GeneratingConfiguration,
                telemetryStep = ConnectionTelemetryStep.GenerateConfig,
                action = { generate(generatedConfig) },
            ),
        )
        stepExecutor.execute(
            ConnectionStep(
                "Config write",
                ConnectionProgress.GeneratingConfiguration,
                telemetryStep = ConnectionTelemetryStep.WriteConfig,
                action = { xrayBinary.writeConfig(configJson) },
            ),
        )
        log.append(LogSource.APP, "Config written to ${xrayBinary.configPath()} (${configJson.length} chars)")
        generatedConfig.otherVpnDns?.let { log.append(LogSource.APP, "Other VPN DNS: ${it.describe()}") }
        return generatedConfig
    }

    /**
     * Whether another app's VPN changed the private DNS zone the running config routes to it. Xray
     * cannot reload DNS settings, so the caller has to reconnect to pick the change up.
     */
    fun otherVpnDnsChanged(): Boolean {
        val config = active?.takeIf { it.followsOtherVpnDns } ?: return false
        val current = environment.otherVpnDns()?.forIpv6(config.runtimeSettings.allowIpv6)
        if (current == config.otherVpnDns) return false
        log.append(LogSource.APP, "Other VPN DNS changed: ${config.otherVpnDns.describe()} -> ${current.describe()}")
        return true
    }

    /**
     * Regenerates the [active] config with [runtimeSettings]' routing and swaps its routing section
     * into the core at [apiEndpoint]. Returns false when the change reaches outside Xray's routing,
     * which only a restart can apply.
     */
    suspend fun applyRoutingChanges(apiEndpoint: XrayApiEndpoint, runtimeSettings: XrayRuntimeSettings): Boolean {
        val currentInputs = active ?: return false
        val currentConfig = xrayBinary.readConfig()?.toJsonObjectOrNull() ?: return false
        if (currentInputs.runtimeSettings.routingDomainStrategy != runtimeSettings.routingDomainStrategy) {
            log.append(LogSource.APP, "Live routing update requires a restart to change domain strategy")
            return false
        }
        val nextRuntimeSettings = currentInputs.runtimeSettings.copy(
            routingRules = resolveProviderRoutingRules(runtimeSettings.routingRules),
            routingDomainMatcher = runtimeSettings.routingDomainMatcher,
            routingFallbackOutbound = runtimeSettings.routingFallbackOutbound,
        )
        if (
            currentInputs.rootBackend == RootConnectionBackend.Tproxy &&
            currentInputs.runtimeSettings.usesProxyAsRoutingDefault() != nextRuntimeSettings.usesProxyAsRoutingDefault()
        ) {
            log.append(LogSource.APP, "Live routing update requires a restart to change the TPROXY default route")
            return false
        }
        val nextInputs = currentInputs.copy(runtimeSettings = nextRuntimeSettings)
        val nextConfigJson = try {
            generate(nextInputs)
        } catch (error: IllegalArgumentException) {
            log.append(LogSource.APP, "Live routing update skipped: ${error.message}")
            return false
        } catch (error: IllegalStateException) {
            log.append(LogSource.APP, "Live routing update skipped: ${error.message}")
            return false
        }
        val nextConfig = nextConfigJson.toJsonObjectOrNull() ?: return false
        if (currentConfig.withoutRouting() != nextConfig.withoutRouting()) {
            log.append(LogSource.APP, "Live routing update requires changes outside Xray routing")
            return false
        }
        val nextRouting = nextConfig["routing"] as? JsonObject ?: return false

        return when (val result = routingUpdater.replace(apiEndpoint, nextRouting)) {
            XrayRoutingUpdateResult.Applied -> {
                try {
                    xrayBinary.writeConfig(nextConfigJson)
                } catch (error: IOException) {
                    log.append(LogSource.APP, "Could not persist live routing update: ${error.message}")
                    return false
                } catch (error: SecurityException) {
                    log.append(LogSource.APP, "Could not persist live routing update: ${error.message}")
                    return false
                }
                active = nextInputs
                log.append(LogSource.APP, "Xray routing updated without restarting the core")
                true
            }
            is XrayRoutingUpdateResult.Failed -> {
                log.append(LogSource.APP, "Live Xray routing update failed: ${result.reason}")
                false
            }
        }
    }

    private suspend fun generate(config: GeneratedXrayConfig): String = withContext(defaultDispatcher) {
        val settings = config.runtimeSettings
        configGenerator.generate(
            server = config.server,
            tunName = settings.tunName,
            fwmark = config.fwmark,
            dnsServers = settings.dnsServers,
            domesticDnsServers = settings.domesticDnsServers,
            preferProfileDns = settings.preferProfileDns,
            syntheticDnsAddress = config.syntheticDnsAddress,
            logLevel = settings.logLevel,
            defaultOutbound = settings.defaultOutbound,
            bypassLan = settings.bypassLan,
            allowIpv6 = settings.allowIpv6,
            routingRules = settings.routingRules,
            routingDomainStrategy = settings.routingDomainStrategy,
            routingDomainMatcher = settings.routingDomainMatcher,
            routingFallbackOutbound = settings.routingFallbackOutbound,
            appProxyRoutes = config.appRoutingPlan.proxyRoutes,
            physicalInterface = config.physicalRoute?.dev,
            xrayApiEndpoint = config.xrayApiEndpoint,
            xrayBufferSizeKiB = settings.xrayBufferSizeKiB,
            tunMtu = settings.tunMtu,
            inbounds = config.inbounds,
            otherVpnDns = config.otherVpnDns,
        )
    }

    /**
     * Writes the hand-edited config, with this connect's runtime identity patched back into it.
     * Returns false when there is no override, or when it is too broken to patch, so the caller
     * generates a config as usual rather than starting the core against something unusable.
     */
    private suspend fun writeOverride(
        runtimeSettings: XrayRuntimeSettings,
        xrayApiEndpoint: XrayApiEndpoint,
        inbounds: List<XrayInbound>?,
        outboundMark: Int?,
        clearOutboundInterfaces: Boolean,
    ): Boolean {
        val override = xrayBinary.readOverrideConfig() ?: return false
        val patched = withContext(defaultDispatcher) {
            configGenerator.applyRuntimeIdentity(
                configJson = override,
                tunName = runtimeSettings.tunName,
                xrayApiEndpoint = xrayApiEndpoint,
                tunMtu = runtimeSettings.tunMtu,
                inbounds = inbounds,
                outboundMark = outboundMark,
                clearOutboundInterfaces = clearOutboundInterfaces,
            )
        }
        if (patched == null) {
            log.append(LogSource.APP, "Edited active config is not a JSON object; generating a config instead")
            return false
        }

        stepExecutor.execute(
            ConnectionStep(
                "Config write",
                ConnectionProgress.GeneratingConfiguration,
                telemetryStep = ConnectionTelemetryStep.WriteConfig,
                action = { xrayBinary.writeConfig(patched) },
            ),
        )
        log.append(LogSource.APP, "Using edited active config (${patched.length} chars)")
        return true
    }
}

internal data class GeneratedXrayConfig(
    val server: ServerConfig,
    val runtimeSettings: XrayRuntimeSettings,
    val managesSystemRouting: Boolean,
    val rootBackend: RootConnectionBackend,
    val fwmark: Int,
    val followsOtherVpnDns: Boolean,
    val otherVpnDns: OtherVpnDns?,
    val appRoutingPlan: AppRoutingPlan,
    val physicalRoute: TunManager.PhysicalRoute?,
    val xrayApiEndpoint: XrayApiEndpoint,
    val syntheticDnsAddress: String?,
    val inbounds: List<XrayInbound>?,
)

internal fun XrayRuntimeSettings.usesProxyAsRoutingDefault(): Boolean = (routingFallbackOutbound ?: defaultOutbound) == XrayOutbound.Proxy

private fun OtherVpnDns?.describe(): String = this?.let { "${it.domains.joinToString()} via ${it.servers.joinToString()} (net ${it.netId})" } ?: "none"

private fun String.toJsonObjectOrNull(): JsonObject? = runCatching {
    Json.parseToJsonElement(this) as? JsonObject
}.getOrNull()

private fun JsonObject.withoutRouting() = filterKeys { it != "routing" }
