package com.material.xray.core.network

import android.content.Context
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.XRAY_API_SOCKET_NAME_PREFIX
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.service.ConnectionStateCoordinator
import com.material.xray.service.LogBuffer
import com.material.xray.service.LogSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * Hands out an [OkHttpClient] for the app's own HTTP traffic (updates, subscriptions, geodata).
 *
 * The app's own process is exempt from the tunnel in both root and rootless modes, so while a
 * server is connected these requests would otherwise always go out directly. When connected, this
 * spins up a short-lived Xray core built from the current settings and the selected server, which
 * approximates the active tunnel's routing (hand-edited active configs and per-app routes are not
 * mirrored), and proxies the operation through it; the core is torn down as soon as [use] returns.
 * In any other state, or if the helper core cannot be started, the plain client is used.
 */
@Singleton
class TunnelAwareHttpClient @Inject constructor(
    @param:ApplicationContext context: Context,
    private val baseClient: OkHttpClient,
    private val ephemeralCore: EphemeralXrayCore,
    private val connectionState: ConnectionStateCoordinator,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
    private val logBuffer: LogBuffer,
) : AppHttpClient {
    private val configGenerator = ConfigGenerator()
    private val serverAddressResolver = ServerAddressResolver(context)

    override suspend fun <T> use(block: suspend (OkHttpClient) -> T): T {
        if (connectionState.state.value !is ConnectionState.Connected) return block(baseClient)

        val server = loadActiveServer() ?: return block(baseClient)
        val settings = settingsRepository.runtimeSettingsSnapshot()
        val resolvedServer = serverAddressResolver.resolveOrNull(server, settings.allowIpv6) ?: return block(baseClient)

        return try {
            ephemeralCore.withHttpProxy(
                inboundTag = INBOUND_TAG,
                startTimeoutMs = START_TIMEOUT_MS,
                buildConfig = { inbound ->
                    configGenerator.generate(
                        server = resolvedServer,
                        tunName = settings.tunName,
                        // The helper core runs under the app uid: it cannot set SO_MARK and is
                        // already exempt from the tunnel, so plain sockets are what we want here.
                        fwmark = 0,
                        dnsServers = settings.dnsServers,
                        domesticDnsServers = settings.domesticDnsServers,
                        preferProfileDns = settings.preferProfileDns,
                        logLevel = settings.logLevel,
                        defaultOutbound = settings.defaultOutbound,
                        bypassLan = settings.bypassLan,
                        allowIpv6 = settings.allowIpv6,
                        routingRules = settings.routingRules,
                        routingDomainStrategy = settings.routingDomainStrategy,
                        routingDomainMatcher = settings.routingDomainMatcher,
                        routingFallbackOutbound = settings.routingFallbackOutbound,
                        appProxyRoutes = emptyList(),
                        physicalInterface = null,
                        xrayApiEndpoint = XrayApiEndpoint.UnixSocket("$XRAY_API_SOCKET_NAME_PREFIX-helper-${UUID.randomUUID()}"),
                        xrayBufferSizeKiB = settings.xrayBufferSizeKiB,
                        tunMtu = settings.tunMtu,
                        inbounds = listOf(inbound),
                    )
                },
                block = block,
            )
        } catch (e: EphemeralXrayCoreException) {
            logBuffer.append(LogSource.APP, "Helper core unavailable, sending app request directly: ${e.message}")
            block(baseClient)
        }
    }

    private suspend fun loadActiveServer(): ServerConfig? {
        val serverId = settingsRepository.lastServerId.first()
        if (serverId < 0) return null
        val entity = serverRepository.getById(serverId) ?: return null
        return runCatching { serverRepository.parseConfig(entity) }.getOrNull()
    }

    private companion object {
        const val INBOUND_TAG = "app-http-in"

        /** Full configs load geosite/geoip data before listening, so allow more than the probe default. */
        const val START_TIMEOUT_MS = 5_000L
    }
}
