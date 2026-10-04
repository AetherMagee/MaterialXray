package com.material.xray.core.network

import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.common.log.LogBuffer
import com.material.xray.core.common.log.LogSource
import com.material.xray.core.common.platform.MonotonicClock
import com.material.xray.core.common.platform.elapsedMillis
import com.material.xray.core.model.ServerConfig
import com.material.xray.core.model.proxyOutboundCount
import com.material.xray.core.xray.PlatformDns
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.XrayInbound
import com.material.xray.core.xray.buildDns
import com.material.xray.core.xray.buildProxyOutbound
import com.material.xray.core.xray.toJson
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.annotation.Singleton

/** Why the running session does or does not carry IPv6, as Auto decided it. */
enum class Ipv6SessionState {
    Enabled,

    /** Enabled, but the check through the server failed since; the next connection uses IPv4. */
    EnabledUntilReconnect,
    NoIpv6Network,
    CheckFailed,
}

/** Auto's answer for one connection, and the network it was given for. */
data class Ipv6Decision(
    val enabled: Boolean,
    val state: Ipv6SessionState,
    val networkKey: String?,
    /** False when only the direct check vouches for IPv6 and the one through the server is still due. */
    val confirmed: Boolean,
)

/**
 * Decides whether IPv6 works well enough to carry a session on the current network.
 *
 * Three steps, cheapest first. A network without a global IPv6 address and default route is
 * answered at once. A direct check then fetches from Cloudflare, Google and Yandex over IPv6,
 * outside the tunnel, and passes when two answer. That rules out a network whose IPv6 is broken without starting Xray. Last, a throwaway core reaches the
 * server over IPv6 and fetches an IPv6-only address through it, covering the path to the server and
 * the server's own IPv6. Answers are kept per network prefix (and server), so a known network is
 * answered at once.
 */
@Singleton
class Ipv6Detector(
    private val linkProbe: NetworkLinkProbe,
    private val ephemeralCore: EphemeralXrayCore,
    private val baseClient: OkHttpClient,
    private val logBuffer: LogBuffer,
    platformDns: PlatformDns,
    private val clock: MonotonicClock,
    @ApplicationScope scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val serverAddressResolver = ServerAddressResolver(platformDns)
    private val verdicts = Ipv6VerdictCache(scope) { clock.elapsedMillis() }
    private val mutableSessionState = MutableStateFlow<Ipv6SessionState?>(null)

    /** What the running Auto session does with IPv6; the service keeps it current. */
    val sessionState: StateFlow<Ipv6SessionState?> = mutableSessionState.asStateFlow()

    fun publishSessionState(state: Ipv6SessionState?) {
        mutableSessionState.value = state
    }

    /** Names the current network by its IPv6 prefixes; null when it has no usable IPv6. */
    fun networkKey(): String? = currentNetwork()?.second

    fun hasPhysicalNetwork(): Boolean = physicalNetwork() != null

    /**
     * Answers for a connection about to start, waiting at most for the direct check. A remembered
     * answer for the server wins; otherwise the direct check decides and [Ipv6Decision.confirmed]
     * says the check through the server is still due.
     */
    suspend fun decide(server: ServerConfig): Ipv6Decision {
        val (network, key) = currentNetwork() ?: return Ipv6Decision(false, Ipv6SessionState.NoIpv6Network, null, true)
        if (server.proxyOutboundCount() == null) {
            verdicts.cached(serverKey(key, server))?.let { works -> return decision(works, key, confirmed = true) }
        }
        val direct = directVerdict(network, key)
        return decision(direct, key, confirmed = !direct || server.proxyOutboundCount() != null)
    }

    /**
     * The full answer for [server] on the current network. Several outbounds would each need their
     * own check, so for those the direct check alone decides; each dial still races its addresses.
     */
    suspend fun check(server: ServerConfig): Boolean {
        val (network, key) = currentNetwork() ?: return false
        if (!directVerdict(network, key)) return false
        if (server.proxyOutboundCount() != null) return true
        return verdicts.verdict(serverKey(key, server)) { probe("IPv6 check through ${server.name}") { probeFailure(server) } }
    }

    /** The best answer already known for [server] here, without probing; IPv4 when nothing is. */
    fun knownVerdict(server: ServerConfig): Boolean {
        val key = networkKey() ?: return false
        return verdicts.cached(serverKey(key, server)) ?: verdicts.cached("$key|direct") ?: false
    }

    private fun decision(works: Boolean, key: String, confirmed: Boolean) = Ipv6Decision(
        enabled = works,
        state = if (works) Ipv6SessionState.Enabled else Ipv6SessionState.CheckFailed,
        networkKey = key,
        confirmed = confirmed,
    )

    private suspend fun directVerdict(network: NetworkLink, key: String): Boolean = verdicts.verdict("$key|direct") { probe("Direct IPv6 check") { directProbeFailure(network) } }

    private fun serverKey(network: String, server: ServerConfig): String = "$network|${server.protocol}|${server.address}|${server.port}|${server.rawConfigJson.hashCode()}"

    private fun physicalNetwork(): NetworkLink? = linkProbe.links().physicalLink()

    private fun currentNetwork(): Pair<NetworkLink, String>? {
        val network = physicalNetwork() ?: return null
        val key = ipv6NetworkKey(network.addresses, network.hasIpv6DefaultRoute) ?: return null
        return network to key
    }

    private suspend fun probe(label: String, failureOf: suspend () -> String): Boolean {
        val startedAt = clock.elapsedMillis()
        val failure = withTimeoutOrNull(PROBE_TIMEOUT_MS) { failureOf() } ?: "timed out"
        val elapsedMs = clock.elapsedMillis() - startedAt
        val works = failure.isEmpty()
        logBuffer.append(
            LogSource.APP,
            if (works) "$label passed in $elapsedMs ms" else "$label failed after $elapsedMs ms: $failure",
        )
        return works
    }

    /** Fetches over the physical network itself, so neither the tunnel nor Xray is involved. */
    private suspend fun directProbeFailure(network: NetworkLink): String {
        val client = baseClient.newBuilder()
            .socketFactory(network.socketFactory)
            .proxy(Proxy.NO_PROXY)
            // Its own pool, so clearing it afterwards leaves the shared client's connections alone.
            .connectionPool(ConnectionPool())
            .connectTimeout(DIRECT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DIRECT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(DIRECT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
        return try {
            fetchQuorum(client, DIRECT_PROBE_TARGETS, DIRECT_PROBE_QUORUM)
        } finally {
            client.connectionPool.evictAll()
        }
    }

    /** Fetches [targets] in parallel; an empty string once [needed] of them answered, else why not. */
    private suspend fun fetchQuorum(client: OkHttpClient, targets: List<Pair<String, String>>, needed: Int): String {
        val checks = targets.map { (name, url) ->
            name to suspend {
                val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
                // Any answer proves the round trip; a redirect is not followed.
                withContext(ioDispatcher) { executeTimedHttpProbe(client, request, clock::elapsedNanos, successCodes = ANY_HTTP_STATUS) }
                    .failure.orEmpty()
            }
        }
        return quorumFailures(needed, checks).joinToString("; ")
    }

    /** Why IPv6 failed, or an empty string when it works. */
    private suspend fun probeFailure(server: ServerConfig): String {
        val resolution = serverAddressResolver.resolve(server, allowIpv6 = true)
        // Point every hostname at its IPv6 addresses only, so the dial cannot quietly use IPv4.
        val ipv6Hosts = resolution.server.bootstrapDnsHosts
            .mapValues { (_, addresses) -> addresses.filter { ':' in it } }
            .filterValues { it.isNotEmpty() }
        val probeServer = when {
            ipv6Hosts.isNotEmpty() -> resolution.server
            // Pinned servers had one address swapped in; prefer an IPv6 one.
            else -> resolution.candidates.firstOrNull { ':' in it }
                ?.let { resolution.server.copy(address = it) }
                ?: resolution.server
        }
        return try {
            ephemeralCore.withHttpProxy(
                inboundTag = PROBE_INBOUND_TAG,
                buildConfig = { inbound -> buildProbeConfig(probeServer, ipv6Hosts, inbound) },
            ) { proxyClient, _ ->
                val client = proxyClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .build()
                // Parallel requests open separate connections, so one lossy dial is enough to fail.
                fetchQuorum(client, List(PROBE_REQUESTS) { CLOUDFLARE }, needed = PROBE_REQUESTS)
            }
        } catch (e: EphemeralXrayCoreException) {
            describeFailure(e)
        }
    }

    private fun buildProbeConfig(
        server: ServerConfig,
        ipv6Hosts: Map<String, List<String>>,
        inbound: XrayInbound.PrivateHttp,
    ): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "warning") })
        put("dns", buildDns(servers = "", bootstrapHosts = ipv6Hosts, allowIpv6 = true))
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
                        allowIpv6 = true,
                        // A server without IPv6 addresses is still dialled, which tests only its own IPv6.
                        domainStrategyOverride = if (ipv6Hosts.isEmpty()) "AsIs" else "ForceIPv6",
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
    }.toString()

    private companion object {
        // Addresses rather than names, so no DNS is involved. Cloudflare's resolver and ya.ru answer
        // plain HTTP; Google's resolver only HTTPS, whose certificate names this address.
        val CLOUDFLARE = "Cloudflare" to "http://[2606:4700:4700::1111]/cdn-cgi/trace"
        val DIRECT_PROBE_TARGETS = listOf(
            CLOUDFLARE,
            "Google" to "https://[2001:4860:4860::8888]/",
            "Yandex" to "http://[2a02:6b8::2:242]/",
        )

        // One provider can be blocked on a network whose IPv6 works.
        const val DIRECT_PROBE_QUORUM = 2
        const val PROBE_REQUESTS = 3
        val ANY_HTTP_STATUS = 100..599

        // A lossy path's dropped SYN is retried after 1 s, so these fail it while allowing a slow server.
        const val DIRECT_REQUEST_TIMEOUT_MS = 1_000L
        const val REQUEST_TIMEOUT_MS = 1_500L
        const val PROBE_TIMEOUT_MS = 6_000L
        const val PROBE_INBOUND_TAG = "ipv6-probe"
    }
}

/** Keeps probe verdicts for a while and lets concurrent callers share one probe per key. */
internal class Ipv6VerdictCache(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
) {
    private class Verdict(val works: Boolean, val checkedAtMs: Long)

    private val verdicts = ConcurrentHashMap<String, Verdict>()
    private val running = HashMap<String, Deferred<Boolean>>()

    fun cached(key: String): Boolean? {
        val verdict = verdicts[key] ?: return null
        // A failure may have been a passing blip, so it is retried sooner than a success.
        val ttlMs = if (verdict.works) WORKING_TTL_MS else BROKEN_TTL_MS
        return verdict.works.takeIf { nowMs() - verdict.checkedAtMs < ttlMs }
    }

    suspend fun verdict(key: String, probe: suspend () -> Boolean): Boolean {
        cached(key)?.let { return it }
        val job = synchronized(running) {
            running.getOrPut(key) {
                // Owned by the scope, so a caller giving up does not cancel the probe for the others.
                scope.async(start = CoroutineStart.LAZY) {
                    val works = runCatching { probe() }.getOrDefault(false)
                    verdicts[key] = Verdict(works, nowMs())
                    synchronized(running) { running.remove(key) }
                    works
                }
            }
        }
        job.start()
        return job.await()
    }

    private companion object {
        const val WORKING_TTL_MS = 30 * 60_000L
        const val BROKEN_TTL_MS = 5 * 60_000L
    }
}

/**
 * Names the network by its global IPv6 /64 prefixes, which survive reconnects and privacy-address
 * rotation but change with the network. Null when the network cannot carry IPv6 at all.
 */
internal fun ipv6NetworkKey(addresses: List<InetAddress>, hasIpv6DefaultRoute: Boolean): String? {
    if (!hasIpv6DefaultRoute) return null
    return addresses
        .filterIsInstance<Inet6Address>()
        // Global unicast is 2000::/3; link-local, ULA and the rest cannot reach the internet.
        .filter { it.address[0].toInt() and GLOBAL_UNICAST_MASK == GLOBAL_UNICAST_PREFIX }
        .map { address -> address.address.take(PREFIX_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it) } }
        .distinct()
        .sorted()
        .joinToString(",")
        .ifEmpty { null }
}

private const val GLOBAL_UNICAST_MASK = 0xE0
private const val GLOBAL_UNICAST_PREFIX = 0x20
private const val PREFIX_BYTES = 8

/**
 * Runs [checks] in parallel until [needed] of them pass (return an empty string) or too many fail
 * for that, cancelling the rest. Returns the failures as "name: reason", or nothing when enough passed.
 */
internal suspend fun quorumFailures(needed: Int, checks: List<Pair<String, suspend () -> String>>): List<String> = coroutineScope {
    val results = Channel<Pair<String, String>>(checks.size)
    checks.forEach { (name, check) -> launch { results.send(name to check()) } }
    val failures = mutableListOf<String>()
    var passed = 0
    while (passed < needed && checks.size - failures.size >= needed) {
        val (name, failure) = results.receive()
        if (failure.isEmpty()) passed++ else failures += "$name: $failure"
    }
    coroutineContext.cancelChildren()
    if (passed >= needed) emptyList() else failures
}
