package com.material.xray.core.network

import com.material.xray.core.root.process.RedirectedProcess
import com.material.xray.core.xray.LocalSockets
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.XrayInbound
import com.material.xray.core.xray.XrayPaths
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.core.annotation.Singleton

class EphemeralXrayCoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Spawns a short-lived, standalone Xray process that exposes an app-private Unix
 * HTTP proxy inbound, and hands the caller an [OkHttpClient] wired through it.
 *
 * The process is torn down as soon as [block] returns, so nothing keeps listening after the
 * operation finishes. Because it runs under the app uid it is exempt from the TUN/TPROXY
 * interception and cannot set SO_MARK, so the generated config must not rely on fwmark.
 */
@Singleton
class EphemeralXrayCore(
    private val baseClient: OkHttpClient,
    private val xrayPaths: XrayPaths,
    private val localSockets: LocalSockets,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val xrayBinary = XrayBinary(xrayPaths)

    /**
     * Runs [block] with a client that proxies through a fresh Xray core.
     *
     * Core startup and teardown run on [Dispatchers.IO]; [block] runs in the caller's context.
     *
     * @param inboundTag tag assigned to the HTTP inbound so [buildConfig] can reference it in routing rules.
     * @param startTimeoutMs how long to wait for the core to start listening. Full configs with geo
     * rules take longer to load than a minimal single-outbound config.
     * @param buildConfig produces the full Xray JSON config for the given inbound.
     * @param block receives the proxied client and the core's log file, which is deleted once it returns.
     * @throws EphemeralXrayCoreException when the core cannot be started for any reason; [block] has
     * not run yet in that case, so callers may safely fall back.
     */
    suspend fun <T> withHttpProxy(
        inboundTag: String,
        startTimeoutMs: Long = DEFAULT_START_TIMEOUT_MS,
        buildConfig: (XrayInbound.PrivateHttp) -> String,
        block: suspend (client: OkHttpClient, logFile: File) -> T,
    ): T {
        val core = withContext(ioDispatcher) { startCore(inboundTag, startTimeoutMs, buildConfig) }
        try {
            val client = privateUnixHttpProxyClient(baseClient, core.inbound.path, localSockets)
            try {
                return block(client, core.logFile)
            } finally {
                evictProxyConnections(ioDispatcher) { client.connectionPool.evictAll() }
            }
        } finally {
            withContext(NonCancellable + ioDispatcher) { core.close() }
        }
    }

    private inner class RunningCore(
        private val process: RedirectedProcess,
        val inbound: XrayInbound.PrivateHttp,
        val logFile: File,
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
        buildConfig: (XrayInbound.PrivateHttp) -> String,
    ): RunningCore {
        currentCoroutineContext().ensureActive()
        val command = resolveCommand()
        val binDir = xrayPaths.filesDir.resolve("bin").also { it.mkdirs() }
        val workDir = xrayPaths.cacheDir.resolve("helper-cores").also { it.mkdirs() }
        val runId = UUID.randomUUID().toString()
        val configFile = workDir.resolve("xray-$runId.json")
        val logFile = workDir.resolve("xray-$runId.log")

        var process: RedirectedProcess? = null
        var started = false
        try {
            val inbound = XrayInbound.PrivateHttp(workDir.resolve("xray-$runId.sock").absolutePath, inboundTag)
            configFile.writeText(buildConfigOrThrow(buildConfig, inbound))
            val spawned = startProcess(command, binDir, configFile, logFile)
            process = spawned
            awaitSocketOrThrow(inbound, spawned, logFile, startTimeoutMs)
            started = true
            return RunningCore(spawned, inbound, logFile, listOf(configFile, logFile, File(inbound.path)))
        } catch (e: EphemeralXrayCoreException) {
            throw e
        } catch (e: IOException) {
            throw EphemeralXrayCoreException("Failed to start Xray core", e)
        } finally {
            if (!started) {
                withContext(NonCancellable) {
                    process?.let { stopProcess(it) }
                    configFile.delete()
                    logFile.delete()
                    File(workDir, "xray-$runId.sock").delete()
                }
            }
        }
    }

    private suspend fun awaitSocketOrThrow(
        inbound: XrayInbound.PrivateHttp,
        process: RedirectedProcess,
        logFile: File,
        startTimeoutMs: Long,
    ) {
        if (waitForSocket(inbound.path, process, startTimeoutMs)) return
        process.awaitOutput()
        val tail = runCatching { logFile.readText().takeLast(LOG_TAIL_CHARS).trim() }.getOrDefault("")
        throw EphemeralXrayCoreException(
            if (tail.isBlank()) "Xray core did not start" else "Xray core did not start: $tail",
        )
    }

    private fun resolveCommand(): List<String> = xrayBinary.userCommand
        ?: throw EphemeralXrayCoreException("Xray binary is not available")

    private fun buildConfigOrThrow(
        buildConfig: (XrayInbound.PrivateHttp) -> String,
        inbound: XrayInbound.PrivateHttp,
    ): String = try {
        buildConfig(inbound)
    } catch (e: Exception) {
        throw EphemeralXrayCoreException("Failed to build Xray config", e)
    }

    private fun startProcess(
        command: List<String>,
        binDir: File,
        configFile: File,
        logFile: File,
    ): RedirectedProcess {
        val builder = ProcessBuilder(command + listOf("run", "-c", configFile.absolutePath))
            .directory(binDir)
            .redirectErrorStream(true)
            .apply {
                environment()["xray.location.asset"] = binDir.absolutePath
                environment()["XRAY_LOCATION_ASSET"] = binDir.absolutePath
            }
        return RedirectedProcess.start(builder, logFile, append = true)
    }

    private suspend fun waitForSocket(path: String, process: RedirectedProcess, timeoutMs: Long): Boolean {
        var elapsedMs = 0L
        while (elapsedMs <= timeoutMs) {
            currentCoroutineContext().ensureActive()
            if (!process.isAlive()) return false
            if (canConnectSocket(path)) return true
            delay(POLL_INTERVAL_MS)
            elapsedMs += POLL_INTERVAL_MS
        }
        return false
    }

    private fun canConnectSocket(path: String): Boolean {
        val socket = localSockets.fileSystemSocketFactory(path).createSocket()
        return try {
            // The factory's sockets ignore the address and dial [path].
            socket.connect(InetSocketAddress(0))
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } finally {
            runCatching { socket.close() }
        }
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
        private const val LOG_TAIL_CHARS = 400
        private const val POLL_INTERVAL_MS = 50L
        private const val STOP_TIMEOUT_MS = 500L
        private const val WAIT_TIMEOUT_MS = 500L
        private const val STOP_POLL_INTERVAL_MS = 50L
    }
}
