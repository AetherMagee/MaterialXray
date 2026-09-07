package com.material.xray.core.network

import android.content.Context
import android.os.SystemClock
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.XrayInbound
import com.material.xray.core.xray.buildDns
import com.material.xray.core.xray.buildProxyOutbound
import com.material.xray.core.xray.toJson
import com.material.xray.model.PingMethod
import com.material.xray.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

data class LatencyProbeResult(
    val latencyMs: Int,
    val method: PingMethod,
)

internal suspend fun measureBestHttpLatency(
    client: OkHttpClient,
    request: Request,
    nanoTime: () -> Long = { SystemClock.elapsedRealtimeNanos() },
): Int {
    var best = -1
    repeat(HTTP_PROBE_ATTEMPTS) {
        currentCoroutineContext().ensureActive()
        val latency = executeTimedHttpProbe(client, request, nanoTime)
        if (latency >= 0 && (best == -1 || latency < best)) {
            best = latency
        }
    }
    return best
}

internal fun mergeDnsServerSettings(
    dnsServers: String,
    domesticDnsServers: String,
): String = sequenceOf(dnsServers, domesticDnsServers)
    .flatMap { it.splitToSequence(',') }
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .joinToString(",")

private suspend fun executeTimedHttpProbe(
    client: OkHttpClient,
    request: Request,
    nanoTime: () -> Long,
): Int = suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation {
        call.cancel()
    }

    try {
        val startedAt = nanoTime()
        val latency = call.execute().use { response ->
            if (response.code !in HTTP_SUCCESS_CODES) {
                -1
            } else {
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (input.read(buffer) != -1) {
                        // Drain the body so the next attempt can reuse this connection.
                    }
                }
                val elapsedMs = (nanoTime() - startedAt) / NANOS_PER_MILLISECOND
                elapsedMs.toInt().coerceAtLeast(1)
            }
        }
        if (continuation.isActive) {
            continuation.resume(latency)
        }
    } catch (_: Exception) {
        if (continuation.isActive) {
            continuation.resume(-1)
        }
    }
}

@Singleton
class ServerLatencyTester @Inject constructor(
    @param:ApplicationContext context: Context,
    private val ephemeralCore: EphemeralXrayCore,
) {
    private val json = Json { prettyPrint = true }
    private val serverAddressResolver = ServerAddressResolver(context)

    suspend fun measure(
        server: ServerConfig,
        method: PingMethod,
        probeUrl: String,
        dnsServers: String,
        domesticDnsServers: String,
        allowIpv6: Boolean,
    ): LatencyProbeResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TEST_TIMEOUT_MS) {
            when (method) {
                PingMethod.Httping -> {
                    val e2eLatency = measureHttpProbeThroughXray(
                        server = serverAddressResolver.resolveOrNull(server, allowIpv6)
                            ?: return@withTimeoutOrNull LatencyProbeResult(
                                latencyMs = -1,
                                method = PingMethod.Httping,
                            ),
                        probeUrl = probeUrl.trim().ifBlank { DEFAULT_PROBE_URL },
                        dnsServers = mergeDnsServerSettings(dnsServers, domesticDnsServers),
                        allowIpv6 = allowIpv6,
                    )
                    LatencyProbeResult(
                        latencyMs = e2eLatency,
                        method = PingMethod.Httping,
                    )
                }
                PingMethod.Tcping -> LatencyProbeResult(
                    latencyMs = measureTcpConnect(server.address, server.port),
                    method = PingMethod.Tcping,
                )
            }
        } ?: LatencyProbeResult(latencyMs = -1, method = method)
    }

    private suspend fun measureHttpProbeThroughXray(
        server: ServerConfig,
        probeUrl: String,
        dnsServers: String,
        allowIpv6: Boolean,
    ): Int = try {
        ephemeralCore.withHttpProxy(
            inboundTag = LATENCY_INBOUND_TAG,
            buildConfig = { inbound -> buildLatencyConfig(server, inbound, dnsServers, allowIpv6) },
        ) { client -> requestProbeThroughProxy(client, probeUrl) }
    } catch (_: EphemeralXrayCoreException) {
        -1
    }

    private fun buildLatencyConfig(
        server: ServerConfig,
        inbound: XrayInbound.Http,
        dnsServers: String,
        allowIpv6: Boolean,
    ): String {
        val config = buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("access", "none")
                    put("loglevel", "error")
                },
            )
            put("dns", buildDns(dnsServers, allowIpv6 = allowIpv6))
            put("inbounds", buildJsonArray { add(inbound.toJson()) })
            put(
                "outbounds",
                buildJsonArray {
                    add(
                        buildProxyOutbound(
                            server = server,
                            fwmark = 0,
                            physicalInterface = null,
                            tag = "proxy",
                            allowIpv6 = allowIpv6,
                            // The probe runs as a standalone process outside the TUN, so let the
                            // proxy outbound resolve its server hostname via the system resolver.
                            // Raw JSON configs keep their hostname (they are not pre-resolved), and
                            // the minimal probe config has no DNS egress for Xray-internal lookups.
                            domainStrategyOverride = "AsIs",
                        ),
                    )
                },
            )
            put(
                "routing",
                buildJsonObject {
                    put(
                        "rules",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "field")
                                    put("inboundTag", buildJsonArray { add(inbound.tag) })
                                    put("outboundTag", "proxy")
                                },
                            )
                        },
                    )
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), config)
    }

    private suspend fun requestProbeThroughProxy(proxyClient: OkHttpClient, probeUrl: String): Int {
        val client = proxyClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = runCatching {
            Request.Builder()
                .url(probeUrl)
                .header("Cache-Control", "no-cache")
                .build()
        }.getOrElse { return -1 }

        return measureBestHttpLatency(client, request)
    }

    private suspend fun measureTcpConnect(address: String, port: Int, attempts: Int = TCPING_ATTEMPTS): Int {
        val host = address.trim().trim('[', ']')
        if (host.isBlank() || port !in 1..65535) return -1

        var best = -1
        repeat(attempts) {
            currentCoroutineContext().ensureActive()
            val latency = socketConnectTime(host, port)
            currentCoroutineContext().ensureActive()
            if (latency >= 0 && (best == -1 || latency < best)) {
                best = latency
            }
        }
        return best
    }

    private suspend fun socketConnectTime(host: String, port: Int): Int = suspendCancellableCoroutine { continuation ->
        val socket = Socket()
        continuation.invokeOnCancellation {
            runCatching { socket.close() }
        }

        try {
            socket.tcpNoDelay = true

            val startedAt = SystemClock.elapsedRealtimeNanos()
            socket.connect(InetSocketAddress(host, port), TCP_CONNECT_TIMEOUT_MS)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / NANOS_PER_MILLISECOND
            val latency = elapsedMs.toInt().coerceAtLeast(1)
            if (continuation.isActive) {
                continuation.resume(latency)
            }
        } catch (_: Exception) {
            if (continuation.isActive) {
                continuation.resume(-1)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val DEFAULT_PROBE_URL = "https://gstatic.com/generate_204"
        const val TEST_TIMEOUT_MS = 12_000L
        const val HTTP_TIMEOUT_MS = 8_000L
        const val TCPING_ATTEMPTS = 2
        const val TCP_CONNECT_TIMEOUT_MS = 3_000
        const val LATENCY_INBOUND_TAG = "latency-http"
    }
}

private const val HTTP_PROBE_ATTEMPTS = 2
private val HTTP_SUCCESS_CODES = 200..399
private const val NANOS_PER_MILLISECOND = 1_000_000L
