package com.material.xray.core.network

import android.content.Context
import android.os.SystemClock
import com.material.xray.core.common.log.xrayTimestampPrefix
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.XrayInbound
import com.material.xray.core.xray.buildDns
import com.material.xray.core.xray.buildProxyOutbound
import com.material.xray.core.xray.toJson
import com.material.xray.model.PingMethod
import com.material.xray.model.ServerConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
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
import org.koin.core.annotation.Singleton

data class LatencyProbeResult(
    val latencyMs: Int,
    val method: PingMethod,
    /** Why the probe failed; null when [latencyMs] is a measurement. */
    val failure: String? = null,
)

/** One probe outcome: a latency, or -1 with the reason it failed. */
internal data class ProbeAttempt(
    val latencyMs: Int,
    val failure: String? = null,
) {
    val succeeded: Boolean
        get() = latencyMs >= 0

    /** Any success beats a failure; a later failure replaces an earlier one so the latest reason is kept. */
    fun isBetterThan(other: ProbeAttempt?): Boolean = when {
        other == null || !other.succeeded -> true
        else -> succeeded && latencyMs < other.latencyMs
    }

    fun toResult(method: PingMethod) = LatencyProbeResult(latencyMs, method, failure)

    companion object {
        fun failed(reason: String) = ProbeAttempt(latencyMs = -1, failure = reason)
    }
}

internal suspend fun measureBestHttpLatency(
    client: OkHttpClient,
    request: Request,
    nanoTime: () -> Long = { SystemClock.elapsedRealtimeNanos() },
): ProbeAttempt = bestAttempt(HTTP_PROBE_ATTEMPTS) { executeTimedHttpProbe(client, request, nanoTime) }

/** Keeps the fastest success, or the last failure when every attempt failed. */
private suspend fun bestAttempt(attempts: Int, probe: suspend () -> ProbeAttempt): ProbeAttempt {
    var best: ProbeAttempt? = null
    repeat(attempts) {
        currentCoroutineContext().ensureActive()
        val attempt = probe()
        currentCoroutineContext().ensureActive()
        if (attempt.isBetterThan(best)) best = attempt
    }
    return checkNotNull(best) { "attempts must be positive" }
}

fun describeFailure(error: Throwable): String = buildString {
    append(error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
    error.cause?.let { cause ->
        append(": ")
        append(cause.message?.takeIf(String::isNotBlank) ?: cause.javaClass.simpleName)
    }
}.toSingleLine()

private fun String.toSingleLine(): String = lines().map(String::trim).filter(String::isNotEmpty).joinToString(" | ")

internal fun mergeDnsServerSettings(
    dnsServers: String,
    domesticDnsServers: String,
): String = sequenceOf(dnsServers, domesticDnsServers)
    .flatMap { it.splitToSequence(',') }
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .joinToString(",")

internal suspend fun executeTimedHttpProbe(
    client: OkHttpClient,
    request: Request,
    nanoTime: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    successCodes: IntRange = HTTP_SUCCESS_CODES,
): ProbeAttempt = suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation {
        call.cancel()
    }

    try {
        val startedAt = nanoTime()
        val attempt = call.execute().use { response ->
            if (response.code !in successCodes) {
                ProbeAttempt.failed("HTTP ${response.code}")
            } else {
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (input.read(buffer) != -1) {
                        // Drain the body so the next attempt can reuse this connection.
                    }
                }
                val elapsedMs = (nanoTime() - startedAt) / NANOS_PER_MILLISECOND
                ProbeAttempt(elapsedMs.toInt().coerceAtLeast(1))
            }
        }
        if (continuation.isActive) {
            continuation.resume(attempt)
        }
    } catch (e: Exception) {
        if (continuation.isActive) {
            continuation.resume(ProbeAttempt.failed(describeFailure(e)))
        }
    }
}

@Singleton
class ServerLatencyTester(
    context: Context,
    private val ephemeralCore: EphemeralXrayCore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
    ): LatencyProbeResult = withContext(ioDispatcher) {
        withTimeoutOrNull(TEST_TIMEOUT_MS) {
            when (method) {
                PingMethod.Httping -> measureHttpProbeThroughXray(
                    server = serverAddressResolver.resolveOrNull(server, allowIpv6)
                        ?: return@withTimeoutOrNull LatencyProbeResult(
                            latencyMs = -1,
                            method = PingMethod.Httping,
                            failure = "Could not resolve server address ${server.address}",
                        ),
                    probeUrl = probeUrl.trim().ifBlank { DEFAULT_PROBE_URL },
                    dnsServers = mergeDnsServerSettings(dnsServers, domesticDnsServers),
                    allowIpv6 = allowIpv6,
                ).toResult(PingMethod.Httping)
                PingMethod.Tcping -> measureTcpConnect(server.address, server.port).toResult(PingMethod.Tcping)
            }
        } ?: LatencyProbeResult(latencyMs = -1, method = method, failure = "Timed out after $TEST_TIMEOUT_MS ms")
    }

    private suspend fun measureHttpProbeThroughXray(
        server: ServerConfig,
        probeUrl: String,
        dnsServers: String,
        allowIpv6: Boolean,
    ): ProbeAttempt = try {
        ephemeralCore.withHttpProxy(
            inboundTag = LATENCY_INBOUND_TAG,
            buildConfig = { inbound -> buildLatencyConfig(server, inbound, dnsServers, allowIpv6) },
        ) { client, logFile ->
            val attempt = requestProbeThroughProxy(client, probeUrl)
            val coreErrors = if (attempt.succeeded) null else readCoreErrors(logFile)
            if (coreErrors == null) attempt else attempt.copy(failure = "${attempt.failure}; xray: $coreErrors")
        }
    } catch (e: EphemeralXrayCoreException) {
        ProbeAttempt.failed(describeFailure(e))
    }

    /** The last errors the probe core logged, which usually name the real cause of a failed request. */
    private fun readCoreErrors(logFile: File): String? = runCatching {
        logFile.readLines()
            .filter { line -> CORE_ERROR_MARKERS.any { it in line } }
            .map { it.replaceFirst(xrayTimestampPrefix, "").take(CORE_ERROR_LINE_CHARS) }
            .distinct()
            .takeLast(CORE_ERROR_LINES)
            .joinToString(" | ")
            .ifEmpty { null }
    }.getOrNull()

    private fun buildLatencyConfig(
        server: ServerConfig,
        inbound: XrayInbound.PrivateHttp,
        dnsServers: String,
        allowIpv6: Boolean,
    ): String {
        val config = buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("access", "none")
                    // Xray reports why an outbound failed only at the info level.
                    put("loglevel", "info")
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

    private suspend fun requestProbeThroughProxy(proxyClient: OkHttpClient, probeUrl: String): ProbeAttempt {
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
        }.getOrElse { return ProbeAttempt.failed("Invalid probe URL $probeUrl") }

        return measureBestHttpLatency(client, request)
    }

    private suspend fun measureTcpConnect(address: String, port: Int): ProbeAttempt {
        val host = address.trim().trim('[', ']')
        if (host.isBlank() || port !in 1..65535) return ProbeAttempt.failed("Invalid server address $address:$port")

        return bestAttempt(TCPING_ATTEMPTS) { socketConnectTime(host, port) }
    }

    private suspend fun socketConnectTime(host: String, port: Int): ProbeAttempt = suspendCancellableCoroutine { continuation ->
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
                continuation.resume(ProbeAttempt(latency))
            }
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume(ProbeAttempt.failed(describeFailure(e)))
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
        val CORE_ERROR_MARKERS = listOf("[Warning]", "[Error]", "failed")
        const val CORE_ERROR_LINES = 2
        const val CORE_ERROR_LINE_CHARS = 500
    }
}

private const val HTTP_PROBE_ATTEMPTS = 2
private val HTTP_SUCCESS_CODES = 200..399
private const val NANOS_PER_MILLISECOND = 1_000_000L
