package com.material.xray.core.xray

import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import java.io.File
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Dns

class ServerAddressResolver(
    private val platformDns: PlatformDns = PlatformDns.None,
    private val hostLookup: (suspend (String) -> List<String>)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Keeps the last-known addresses across restarts; without it they live only as long as the process. */
    private val lastKnownFile: File? = null,
) {
    data class Result(
        val server: ServerConfig,
        val attempted: Boolean,
        val selectedAddress: String?,
        val candidates: List<String>,
        val unresolvedHosts: List<String> = emptyList(),
    )

    private val successfulLookups = ConcurrentHashMap<String, CachedLookup>()
    private val lastKnownAddresses by lazy { ConcurrentHashMap(readLastKnownAddresses()) }

    /**
     * Resolves [server]'s hostname. The hostname is kept and its addresses handed to Xray's DNS, so
     * Xray can race them; [pinAddress] instead swaps in one address for a core that resolves nothing
     * itself. WireGuard stays pinned: it dials one UDP endpoint per session, so it has nothing to race.
     */
    suspend fun resolve(
        server: ServerConfig,
        allowIpv6: Boolean = false,
        pinAddress: Boolean = false,
    ): Result = withContext(ioDispatcher) {
        if (server.rawConfigJson.isNotBlank()) {
            return@withContext resolveRawConfig(server, allowIpv6)
        }

        val host = server.address.trim()
        if (host.isEmpty() || isNumericAddress(host)) {
            if (!allowIpv6 && isIpv6Address(host)) {
                return@withContext Result(server, attempted = true, selectedAddress = null, candidates = emptyList())
            }
            return@withContext Result(server, attempted = false, selectedAddress = null, candidates = emptyList())
        }

        val candidates = resolveHost(host, allowIpv6)
        if (candidates.isEmpty()) {
            return@withContext Result(server, attempted = true, selectedAddress = null, candidates = emptyList())
        }

        val hostServer = server.withHostDefaults(host)
        // Xray matches hosts entries against the normalised name, so "proxy.example." needs the bare key.
        val hostsKey = endpointHostname(host)
        if (!pinAddress && server.protocol != Protocol.WIREGUARD && hostsKey != null) {
            return@withContext Result(
                server = hostServer.copy(bootstrapDnsHosts = mapOf(hostsKey to candidates)),
                attempted = true,
                selectedAddress = candidates.first(),
                candidates = candidates,
            )
        }

        val selectedAddress = candidates.random(Random(System.nanoTime()))
        Result(
            server = hostServer.copy(address = selectedAddress),
            attempted = true,
            selectedAddress = selectedAddress,
            candidates = candidates,
        )
    }

    /**
     * Resolves [server] for a standalone helper core, or returns null when the address was looked up
     * and nothing usable came back. Raw configs are returned as-is because the core resolves the
     * hosts inside them itself.
     */
    suspend fun resolveOrNull(server: ServerConfig, allowIpv6: Boolean): ServerConfig? {
        if (server.rawConfigJson.isNotBlank()) return server
        val resolved = resolve(server, allowIpv6, pinAddress = true)
        if (resolved.attempted && resolved.selectedAddress == null) return null
        return resolved.server
    }

    private suspend fun resolveRawConfig(server: ServerConfig, allowIpv6: Boolean): Result {
        val endpoints = rawProxyEndpoints(server.rawConfigJson)
        if (!allowIpv6 && endpoints.ipv6Addresses.isNotEmpty()) {
            return Result(
                server = server,
                attempted = true,
                selectedAddress = null,
                candidates = emptyList(),
                unresolvedHosts = endpoints.ipv6Addresses,
            )
        }

        val hosts = endpoints.hosts
        if (hosts.isEmpty()) {
            return Result(server, attempted = false, selectedAddress = null, candidates = emptyList())
        }

        val resolved = coroutineScope {
            hosts.map { host ->
                async { host to resolveHost(host, allowIpv6) }
            }.awaitAll()
        }
        val unresolvedHosts = resolved.filter { (_, candidates) -> candidates.isEmpty() }.map { it.first }
        val candidates = resolved.flatMap { it.second }.distinct()
        if (unresolvedHosts.isNotEmpty()) {
            return Result(
                server = server,
                attempted = true,
                selectedAddress = null,
                candidates = candidates,
                unresolvedHosts = unresolvedHosts,
            )
        }

        return Result(
            server = server.copy(bootstrapDnsHosts = resolved.toMap()),
            attempted = true,
            selectedAddress = candidates.firstOrNull(),
            candidates = candidates,
        )
    }

    private suspend fun resolveHost(host: String, allowIpv6: Boolean): List<String> {
        val now = nanoTime()
        val cacheKey = "${platformDns.activeNetworkHandle()}:$host"
        val cached = successfulLookups[cacheKey]?.takeIf { now - it.createdAtNanos < CACHE_TTL_NANOS }
        val candidates = cached?.addresses ?: (hostLookup?.invoke(host) ?: systemLookup(host)).also { addresses ->
            if (addresses.isNotEmpty()) {
                successfulLookups[cacheKey] = CachedLookup(addresses, now)
                if (lastKnownAddresses.put(host, addresses) != addresses) writeLastKnownAddresses()
            }
        }.ifEmpty {
            // When another app's VPN covers this app, Android sends its lookups to that VPN's
            // resolver, and the TPROXY guard holds the VPN's own traffic while MXray reconnects. A
            // reconnect always follows a successful lookup, so the answer it got is reused. It is kept
            // on disk because a restored connection starts in a new process behind the same rules.
            lastKnownAddresses[host].orEmpty()
        }
        return candidates.distinct().filter { allowIpv6 || !isIpv6Address(it) }
    }

    private fun readLastKnownAddresses(): Map<String, List<String>> = runCatching {
        lastKnownFile?.takeIf(File::exists)?.let { Json.decodeFromString<Map<String, List<String>>>(it.readText()) }
    }.getOrNull().orEmpty()

    @Synchronized
    private fun writeLastKnownAddresses() {
        val file = lastKnownFile ?: return
        // A whole-file swap, so a crash mid-write leaves the previous copy rather than a torn one.
        runCatching {
            val temporary = File(file.path + ".tmp")
            temporary.writeText(Json.encodeToString(lastKnownAddresses.toMap()))
            check(temporary.renameTo(file))
        }
    }

    // The platform resolver and Dns.SYSTEM query the same resolver (netd on Android), so a second
    // concurrent lookup adds no information. The platform one is preferred because it is
    // asynchronous and cancellable, which lets a stalled query be abandoned after RESOLVE_TIMEOUT_MS;
    // the blocking Dns.SYSTEM lookup is only a fallback for that failure case and the primary path
    // where the platform has none (below Android 10, off Android).
    private suspend fun systemLookup(host: String): List<String> = dnsLookupWithFallback(
        primaryLookup = { withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { platformDns.query(host) } },
        fallbackLookup = { resolveWithOkHttpDns(host) },
    )

    /** Names the original host where TLS or the transport would otherwise fall back to a pinned address. */
    private fun ServerConfig.withHostDefaults(originalHost: String): ServerConfig {
        val resolvedSecurity = if (security.sni.isEmpty() && security.type in setOf("tls", "reality")) {
            security.copy(sni = originalHost)
        } else {
            security
        }

        val resolvedTransport = if (transport.host.isEmpty() && transport.type in setOf("ws", "xhttp", "httpupgrade")) {
            transport.copy(host = originalHost)
        } else {
            transport
        }

        return copy(security = resolvedSecurity, transport = resolvedTransport)
    }

    private fun isNumericAddress(host: String): Boolean {
        val value = host.trim('[', ']')
        if (value.contains(':')) {
            return runCatching { InetAddress.getByName(value) }.isSuccess
        }
        return ipv4Pattern.matches(value) && value.split('.').all { it.toIntOrNull() in 0..255 }
    }

    private fun isIpv6Address(host: String): Boolean {
        val value = host.trim('[', ']')
        return value.contains(':') && runCatching { InetAddress.getByName(value) }.isSuccess
    }

    private fun resolveWithOkHttpDns(host: String): List<String> = runCatching {
        Dns.SYSTEM.lookup(host).mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private companion object {
        const val CACHE_TTL_NANOS = 60_000_000_000L
        const val RESOLVE_TIMEOUT_MS = 2000L
        val ipv4Pattern = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    }

    private data class CachedLookup(val addresses: List<String>, val createdAtNanos: Long)
}

internal suspend fun dnsLookupWithFallback(
    primaryLookup: suspend () -> List<String>?,
    fallbackLookup: () -> List<String>,
): List<String> {
    val addresses = try {
        primaryLookup()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
    return addresses?.takeIf(List<String>::isNotEmpty) ?: fallbackLookup()
}

internal fun rawProxyEndpointHosts(rawJson: String): List<String> = rawProxyEndpoints(rawJson).hosts

private data class RawProxyEndpoints(
    val hosts: List<String>,
    val ipv6Addresses: List<String>,
)

private fun rawProxyEndpoints(rawJson: String): RawProxyEndpoints {
    val root = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
        ?: return RawProxyEndpoints(emptyList(), emptyList())
    val outbounds = root["outbounds"] as? JsonArray ?: return RawProxyEndpoints(emptyList(), emptyList())
    val hosts = linkedSetOf<String>()
    val ipv6Addresses = linkedSetOf<String>()
    outbounds.mapNotNull { it as? JsonObject }.forEach { outbound ->
        val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (protocol in NON_PROXY_OUTBOUND_PROTOCOLS) return@forEach
        collectEndpoints(
            element = outbound,
            hosts = hosts,
            ipv6Addresses = ipv6Addresses,
            includeAddressFields = protocol != "wireguard",
        )
    }
    return RawProxyEndpoints(hosts.toList(), ipv6Addresses.toList())
}

private fun collectEndpoints(
    element: JsonElement,
    hosts: MutableSet<String>,
    ipv6Addresses: MutableSet<String>,
    includeAddressFields: Boolean,
    fieldName: String? = null,
) {
    when (element) {
        is JsonObject -> element.forEach { (name, value) ->
            collectEndpoints(value, hosts, ipv6Addresses, includeAddressFields, name)
        }
        is JsonArray -> element.forEach { value ->
            collectEndpoints(value, hosts, ipv6Addresses, includeAddressFields, fieldName)
        }
        is JsonPrimitive -> if (element.isString) {
            val endpoint = endpointAddress(fieldName, element.contentOrNull, includeAddressFields) ?: return
            when {
                isIpv6Literal(endpoint) -> ipv6Addresses += endpoint
                else -> endpointHostname(endpoint)?.let(hosts::add)
            }
        }
    }
}

private fun endpointAddress(fieldName: String?, value: String?, includeAddressFields: Boolean): String? {
    val endpoint = when (fieldName?.lowercase()) {
        "address" -> value?.takeIf { includeAddressFields }
        "endpoint" -> value
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        endpoint.startsWith('[') -> endpoint.substringAfter('[').substringBefore(']')
        endpoint.count { it == ':' } == 1 && endpoint.substringAfterLast(':').toIntOrNull() != null -> endpoint.substringBeforeLast(':')
        else -> endpoint.trim('[', ']')
    }
}

private fun endpointHostname(endpoint: String): String? {
    val candidate = endpoint.trimEnd('.').takeIf { it.isNotEmpty() } ?: return null
    if (candidate.equals("localhost", ignoreCase = true)) return null
    if (IPV4_ADDRESS.matches(candidate) && candidate.split('.').all { it.toIntOrNull() in 0..255 }) return null
    if (candidate.contains(':')) return null

    val ascii = runCatching { IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES) }.getOrNull() ?: return null
    return ascii.lowercase().takeIf { HOSTNAME.matches(it) }
}

private fun isIpv6Literal(value: String): Boolean = value.contains(':') &&
    runCatching { InetAddress.getByName(value.substringBefore('%')) is Inet6Address }.getOrDefault(false)

private val NON_PROXY_OUTBOUND_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")
private val IPV4_ADDRESS = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
private val HOSTNAME = Regex("""(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*""")
