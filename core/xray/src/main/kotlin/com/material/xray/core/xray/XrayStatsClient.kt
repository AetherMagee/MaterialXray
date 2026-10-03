package com.material.xray.core.xray

import com.material.xray.core.common.log.AppLogger
import com.material.xray.core.common.log.NoOpAppLogger
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class XrayStatsClient(
    private val endpoint: XrayApiEndpoint = XrayApiEndpoint.UnixSocket(XRAY_API_SOCKET_NAME_PREFIX),
    private val localSockets: LocalSockets,
    private val logger: AppLogger = NoOpAppLogger,
    private val timeoutMs: Long = XRAY_API_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val channelDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::buildChannel)
    private val channel by channelDelegate
    private val stub by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StatsServiceGrpc.newBlockingStub(channel)
    }

    suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long> = queryStats(pattern = "outbound")

    suspend fun queryStats(pattern: String, reset: Boolean = false): Map<String, Long> = withContext(ioDispatcher) {
        withBlockingStub { stub ->
            val response = stub.queryStats(
                QueryStatsRequest.newBuilder()
                    .setPattern(pattern)
                    .setReset(reset)
                    .build(),
            )
            response.statList.associate { stat -> stat.name to stat.value }
        }.getOrElse { error ->
            logger.w(TAG, "Xray stats query failed", error)
            emptyMap()
        }
    }

    suspend fun getSysStats(): XraySysStats? = withContext(ioDispatcher) {
        withBlockingStub { stub ->
            val response = stub.getSysStats(SysStatsRequest.getDefaultInstance())
            XraySysStats(
                numGoroutine = response.numGoroutine,
                numGc = response.numGC,
                alloc = response.alloc,
                totalAlloc = response.totalAlloc,
                sys = response.sys,
                mallocs = response.mallocs,
                frees = response.frees,
                liveObjects = response.liveObjects,
                pauseTotalNs = response.pauseTotalNs,
                uptimeSeconds = response.uptime,
            )
        }.getOrElse { error ->
            logger.w(TAG, "Xray sys stats query failed", error)
            null
        }
    }

    private fun <T> withBlockingStub(block: (StatsServiceGrpc.StatsServiceBlockingStub) -> T): Result<T> = try {
        Result.success(block(currentStub().withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)))
    } catch (e: StatusRuntimeException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    } catch (e: IllegalStateException) {
        Result.failure(e)
    } catch (e: SecurityException) {
        Result.failure(e)
    }

    /**
     * A failed connect leaves the channel in TRANSIENT_FAILURE, where calls fail without dialling until
     * its reconnect backoff (1 s at first) runs out, and resetting the backoff does not lift that. The
     * core's API socket only appears once the core has started, so the readiness poll would wait out
     * the backoff rather than its own interval. An idle channel dials on its next call instead.
     */
    private fun currentStub(): StatsServiceGrpc.StatsServiceBlockingStub {
        if (channel.getState(false) == ConnectivityState.TRANSIENT_FAILURE) channel.enterIdle()
        return stub
    }

    override fun close() {
        if (channelDelegate.isInitialized()) channel.shutdownNow()
    }

    private fun buildChannel(): ManagedChannel = buildXrayApiChannel(endpoint, localSockets)
}

private const val TAG = "XrayStatsClient"

data class XraySysStats(
    val numGoroutine: Int,
    val numGc: Int,
    val alloc: Long,
    val totalAlloc: Long,
    val sys: Long,
    val mallocs: Long,
    val frees: Long,
    val liveObjects: Long,
    val pauseTotalNs: Long,
    val uptimeSeconds: Int,
)
