package com.material.xray.core.network

import android.content.Context
import android.net.LocalSocketAddress
import com.material.xray.core.xray.ACTIVE_CONFIG_FILE
import com.material.xray.core.xray.AndroidLocalSocketFactory
import com.material.xray.core.xray.XRAY_APP_HTTP_INBOUND_TAG
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import com.material.xray.service.ConnectionStateCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/** Only the app's HTTP downloads use the private data socket; latency probes use their own client. */
@Singleton
class ActiveCoreHttpClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val baseClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val connectionState: ConnectionStateCoordinator,
) : AppHttpClient {
    override suspend fun <T> use(block: suspend (OkHttpClient) -> T): T {
        if (!settingsRepository.routeMxrayTrafficThroughXray.first()) return block(baseClient)
        if (connectionState.state.value !is ConnectionState.Connected) return block(baseClient)

        val privateDir = context.filesDir.resolve("bin")
        val socketPath = withContext(Dispatchers.IO) {
            File(context.filesDir, ACTIVE_CONFIG_FILE)
                .takeIf(File::isFile)
                ?.readText()
                ?.let { privateHttpSocketPath(it, privateDir) }
        } ?: throw IOException("The active Xray private HTTP socket is unavailable")

        val proxyClient = privateUnixHttpProxyClient(baseClient, socketPath)
        return try {
            block(proxyClient)
        } finally {
            proxyClient.connectionPool.evictAll()
        }
    }
}

internal fun privateUnixHttpProxyClient(baseClient: OkHttpClient, socketPath: String): OkHttpClient = baseClient.newBuilder()
    // OkHttp uses this address to select HTTP proxy framing; the socket factory connects to the
    // private Unix path instead, so no TCP listener is created at this address.
    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
    .socketFactory(AndroidLocalSocketFactory(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
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
