package com.material.xray.core.network

import android.content.Context
import com.material.xray.core.process.RedirectedProcess
import com.material.xray.core.xray.XRAY_API_LOOPBACK_ADDRESS
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.XrayInbound
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient

class EphemeralXrayCoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Spawns a short-lived, standalone Xray process that exposes a loopback-only, password-protected
 * HTTP proxy inbound, and hands the caller an [OkHttpClient] wired through it.
 *
 * The process is torn down as soon as [block] returns, so nothing keeps listening after the
 * operation finishes. Because it runs under the app uid it is exempt from the TUN/TPROXY
 * interception and cannot set SO_MARK, so the generated config must not rely on fwmark.
 */
@Singleton
class EphemeralXrayCore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val baseClient: OkHttpClient,
) {
    private val xrayBinary = XrayBinary(context)
    private val random = SecureRandom()

    /**
     * Runs [block] with a client that proxies through a fresh Xray core.
     *
     * Core startup and teardown run on [Dispatchers.IO]; [block] runs in the caller's context.
     *
     * @param inboundTag tag assigned to the HTTP inbound so [buildConfig] can reference it in routing rules.
     * @param startTimeoutMs how long to wait for the core to start listening. Full configs with geo
     * rules take longer to load than a minimal single-outbound config.
     * @param buildConfig produces the full Xray JSON config for the given inbound.
     * @throws EphemeralXrayCoreException when the core cannot be started for any reason; [block] has
     * not run yet in that case, so callers may safely fall back.
     */
    suspend fun <T> withHttpProxy(
        inboundTag: String,
        startTimeoutMs: Long = DEFAULT_START_TIMEOUT_MS,
        buildConfig: (XrayInbound.Http) -> String,
        block: suspend (OkHttpClient) -> T,
    ): T {
        val core = withContext(Dispatchers.IO) { startCore(inboundTag, startTimeoutMs, buildConfig) }
        try {
            return block(buildProxyClient(core.inbound))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { core.close() }
        }
    }

    private inner class RunningCore(
        private val process: RedirectedProcess,
        val inbound: XrayInbound.Http,
        private val files: List<File>,
    ) {
        suspend fun close() {
            stopProcess(process)
            files.forEach { it.delete() }
        }
    }

    private suspend fun startCore(
        inboundTag: String,
        startTimeoutMs: Long,
        buildConfig: (XrayInbound.Http) -> String,
    ): RunningCore {
        currentCoroutineContext().ensureActive()
        val binaryPath = resolveBinaryPath()
        val binDir = context.filesDir.resolve("bin").also { it.mkdirs() }
        val workDir = context.cacheDir.resolve("helper-cores").also { it.mkdirs() }
        val runId = UUID.randomUUID().toString()
        val configFile = workDir.resolve("xray-$runId.json")
        val logFile = workDir.resolve("xray-$runId.log")

        var process: RedirectedProcess? = null
        var started = false
        try {
            val inbound = XrayInbound.Http(
                port = reserveLocalPort(),
                tag = inboundTag,
                username = randomToken(),
                password = randomToken(),
            )
            configFile.writeText(buildConfigOrThrow(buildConfig, inbound))
            val spawned = startProcess(binaryPath, binDir, configFile, logFile)
            process = spawned
            if (!waitForLocalPort(inbound.port, spawned, startTimeoutMs)) {
                spawned.awaitOutput()
                val tail = runCatching { logFile.readText().takeLast(LOG_TAIL_CHARS).trim() }.getOrDefault("")
                throw EphemeralXrayCoreException(
                    if (tail.isBlank()) "Xray core did not start" else "Xray core did not start: $tail",
                )
            }
            started = true
            return RunningCore(spawned, inbound, listOf(configFile, logFile))
        } catch (e: IOException) {
            throw if (e is EphemeralXrayCoreException) e else EphemeralXrayCoreException("Failed to start Xray core", e)
        } finally {
            if (!started) {
                withContext(NonCancellable) {
                    process?.let { stopProcess(it) }
                    configFile.delete()
                    logFile.delete()
                }
            }
        }
    }

    private fun resolveBinaryPath(): String {
        if (!xrayBinary.ensureAndroidBinaryAvailable()) {
            throw EphemeralXrayCoreException("Xray binary is not available")
        }
        return xrayBinary.androidBinaryPath
            ?: throw EphemeralXrayCoreException("Xray binary path is unknown")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buildConfigOrThrow(
        buildConfig: (XrayInbound.Http) -> String,
        inbound: XrayInbound.Http,
    ): String = try {
        buildConfig(inbound)
    } catch (e: Exception) {
        throw EphemeralXrayCoreException("Failed to build Xray config", e)
    }

    private fun startProcess(
        binaryPath: String,
        binDir: File,
        configFile: File,
        logFile: File,
    ): RedirectedProcess {
        val builder = ProcessBuilder(binaryPath, "run", "-c", configFile.absolutePath)
            .directory(binDir)
            .redirectErrorStream(true)
            .apply {
                environment()["xray.location.asset"] = binDir.absolutePath
                environment()["XRAY_LOCATION_ASSET"] = binDir.absolutePath
            }
        return RedirectedProcess.start(builder, logFile, append = true)
    }

    private fun buildProxyClient(inbound: XrayInbound.Http): OkHttpClient {
        val credential = Credentials.basic(inbound.username, inbound.password)
        return baseClient.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(XRAY_API_LOOPBACK_ADDRESS, inbound.port)))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
            .build()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun waitForLocalPort(port: Int, process: RedirectedProcess, timeoutMs: Long): Boolean {
        var elapsedMs = 0L
        while (elapsedMs <= timeoutMs) {
            currentCoroutineContext().ensureActive()
            if (!process.isAlive()) return false
            if (canConnectLocalPort(port)) return true
            delay(POLL_INTERVAL_MS)
            elapsedMs += POLL_INTERVAL_MS
        }
        return false
    }

    private fun canConnectLocalPort(port: Int): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(XRAY_API_LOOPBACK_ADDRESS, port), CONNECT_TIMEOUT_MS)
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun reserveLocalPort(): Int = ServerSocket(0).use { socket ->
        socket.reuseAddress = true
        socket.localPort
    }

    private suspend fun stopProcess(process: RedirectedProcess) {
        if (process.isAlive()) {
            process.destroy()
            var elapsedMs = 0L
            while (process.isAlive() && elapsedMs <= STOP_TIMEOUT_MS) {
                delay(STOP_POLL_INTERVAL_MS)
                elapsedMs += STOP_POLL_INTERVAL_MS
            }
            if (process.isAlive()) process.destroyForcibly()
        }
        runCatching { process.waitFor(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    companion object {
        /** Enough for a minimal single-outbound config such as the latency probe. */
        const val DEFAULT_START_TIMEOUT_MS = 1_500L
        private const val TOKEN_BYTES = 16
        private const val LOG_TAIL_CHARS = 400
        private const val POLL_INTERVAL_MS = 50L
        private const val CONNECT_TIMEOUT_MS = 100
        private const val STOP_TIMEOUT_MS = 500L
        private const val WAIT_TIMEOUT_MS = 500L
        private const val STOP_POLL_INTERVAL_MS = 50L
    }
}
