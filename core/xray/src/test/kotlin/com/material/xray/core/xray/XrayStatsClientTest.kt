package com.material.xray.core.xray

import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import com.xray.app.stats.command.SysStatsResponse
import io.grpc.InsecureServerCredentials
import io.grpc.okhttp.OkHttpServerBuilder
import io.grpc.stub.StreamObserver
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayStatsClientTest {
    @Test
    fun `reaches an API that starts listening after a failed connect without waiting out the backoff`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        XrayStatsClient(XrayApiEndpoint.LoopbackTcp(port)).use { client ->
            // Nothing listens yet, so this fails; its warning log is unavailable on the JVM.
            runCatching { client.getSysStats() }

            val server = OkHttpServerBuilder.forPort(port, InsecureServerCredentials.create())
                .addService(SysStatsService(uptimeSeconds = 7))
                .build()
                .start()
            try {
                assertEquals(7, client.getSysStats()?.uptimeSeconds)
            } finally {
                server.shutdownNow()
            }
        }
    }

    private class SysStatsService(private val uptimeSeconds: Int) : StatsServiceGrpc.StatsServiceImplBase() {
        override fun getSysStats(request: SysStatsRequest, responseObserver: StreamObserver<SysStatsResponse>) {
            responseObserver.onNext(SysStatsResponse.newBuilder().setUptime(uptimeSeconds).build())
            responseObserver.onCompleted()
        }
    }
}
