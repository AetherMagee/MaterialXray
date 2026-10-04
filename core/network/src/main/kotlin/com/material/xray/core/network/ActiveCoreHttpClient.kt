package com.material.xray.core.network

import com.material.xray.core.common.connection.ConnectionStateCoordinator
import com.material.xray.core.model.ConnectionState
import com.material.xray.core.xray.ACTIVE_CONFIG_FILE
import com.material.xray.core.xray.LocalSockets
import com.material.xray.core.xray.XRAY_APP_HTTP_INBOUND_TAG
import com.material.xray.core.xray.XrayPaths
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.koin.core.annotation.Singleton

/** Only the app's HTTP downloads use the private data socket; latency probes use their own client. */
@Singleton
class ActiveCoreHttpClient(
    private val xrayPaths: XrayPaths,
    private val baseClient: OkHttpClient,
    private val trafficRoutingSetting: CoreTrafficRoutingSetting,
    private val connectionState: ConnectionStateCoordinator,
    private val localSockets: LocalSockets,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppHttpClient {
    override suspend fun <T> use(block: suspend (OkHttpClient) -> T): T {
        if (!trafficRoutingSetting.routeMxrayTrafficThroughXray.first()) return block(baseClient)
        if (connectionState.state.value !is ConnectionState.Connected) return block(baseClient)

        val privateDir = xrayPaths.filesDir.resolve("bin")
        val socketPath = withContext(ioDispatcher) {
            File(xrayPaths.filesDir, ACTIVE_CONFIG_FILE)
                .takeIf(File::isFile)
                ?.readText()
                ?.let { privateHttpSocketPath(it, privateDir) }
        } ?: throw IOException("The active Xray private HTTP socket is unavailable")

        val proxyClient = privateUnixHttpProxyClient(baseClient, socketPath, localSockets)
        return try {
            block(proxyClient)
        } finally {
            evictProxyConnections(ioDispatcher) { proxyClient.connectionPool.evictAll() }
        }
    }
}

internal suspend fun evictProxyConnections(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    evict: () -> Unit,
) {
    withContext(NonCancellable + ioDispatcher) { evict() }
}

internal fun privateUnixHttpProxyClient(
    baseClient: OkHttpClient,
    socketPath: String,
    localSockets: LocalSockets,
): OkHttpClient = baseClient.newBuilder()
    // OkHttp uses this address to select HTTP proxy framing; the socket factory connects to the
    // private Unix path instead, so no TCP listener is created at this address.
    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
    .socketFactory(localSockets.fileSystemSocketFactory(socketPath))
    .connectionPool(ConnectionPool())
    .build()

internal fun privateHttpSocketPath(config: String, privateDir: File): String? = runCatching {
    val inbounds = (Json.parseToJsonElement(config) as? JsonObject)?.get("inbounds") as? JsonArray
    val listen = inbounds
        ?.asSequence()
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it["tag"]?.jsonPrimitive?.contentOrNull == XRAY_APP_HTTP_INBOUND_TAG }
        ?.get("listen")?.jsonPrimitive?.contentOrNull
        ?: return@runCatching null
    if (!listen.endsWith(",0666")) return@runCatching null
    val path = listen.removeSuffix(",0666")
    val file = File(path)
    path.takeIf { file.isAbsolute && file.parentFile?.canonicalFile == privateDir.canonicalFile }
}.getOrNull()
