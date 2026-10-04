package com.material.xray.feature.home

import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.data.repository.ServerRepository
import com.material.xray.core.data.repository.SettingsRepository
import com.material.xray.core.database.entity.ServerEntity
import com.material.xray.core.model.Ipv6Mode
import com.material.xray.core.model.PingMethod
import com.material.xray.core.network.Ipv6Detector
import com.material.xray.core.network.ServerLatencyTester
import com.material.xray.core.network.describeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Singleton

/** Measures a server's latency with the user's probe settings, logging every probe's outcome. */
@Singleton
class ServerLatencyMeasurer(
    private val settingsRepo: SettingsRepository,
    private val serverRepo: ServerRepository,
    private val serverLatencyTester: ServerLatencyTester,
    private val ipv6Detector: Ipv6Detector,
    private val logBuffer: LogBuffer,
) {
    /** A probe that cannot run is reported as -1 for each of its methods rather than thrown. */
    suspend fun measure(
        server: ServerEntity,
        primaryMethod: PingMethod,
        methods: List<PingMethod>,
    ): ServerLatencyState = try {
        measureOrThrow(server, primaryMethod, methods)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logFailure(server, methods, describeFailure(error))
        latencyState(primaryMethod, methods.associateWith { -1 })
    }

    private suspend fun measureOrThrow(
        server: ServerEntity,
        primaryMethod: PingMethod,
        methods: List<PingMethod>,
    ): ServerLatencyState {
        val config = runCatching { serverRepo.parseConfig(server) }.getOrElse { error ->
            logFailure(server, methods, "Could not parse config: ${describeFailure(error)}")
            return latencyState(primaryMethod, methods.associateWith { -1 })
        }
        val probeUrl = settingsRepo.latencyCheckUrl.first()
        val dnsServers = settingsRepo.dnsServers.first()
        val domesticDnsServers = settingsRepo.domesticDnsServers.first()
        val allowIpv6 = when (settingsRepo.ipv6Mode.first()) {
            Ipv6Mode.Off -> false
            Ipv6Mode.On -> true
            // Auto pings the way a session would connect: IPv6 only once it has been seen to work.
            Ipv6Mode.Auto -> ipv6Detector.knownVerdict(config)
        }
        val latencyByMethod = buildMap {
            methods.forEach { method ->
                val result = serverLatencyTester.measure(
                    server = config,
                    method = method,
                    probeUrl = probeUrl,
                    dnsServers = dnsServers,
                    domesticDnsServers = domesticDnsServers,
                    allowIpv6 = allowIpv6,
                )
                logBuffer.append(
                    LogSource.APP,
                    logLine(server, method, result.failure?.let { "failed: $it" } ?: "${result.latencyMs} ms"),
                )
                put(method, result.latencyMs)
            }
        }
        return latencyState(primaryMethod, latencyByMethod)
    }

    private fun logFailure(server: ServerEntity, methods: List<PingMethod>, reason: String) {
        logBuffer.appendAll(LogSource.APP, methods.map { logLine(server, it, "failed: $reason") })
    }

    private fun logLine(server: ServerEntity, method: PingMethod, outcome: String) = "Ping ${server.name} (${server.address}:${server.port}) ${method.name}: $outcome"
}
