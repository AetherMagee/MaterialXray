package com.material.xray.core.xray

import android.net.LocalServerSocket
import android.net.LocalSocketAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocalSocketFactoryTest {
    @Test
    fun httpsProxyConnectUsesLocalSocket() {
        val socketName = "mxray-test-${UUID.randomUUID()}"
        LocalServerSocket(socketName).use { server ->
            var requestLine = ""
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.inputStream.bufferedReader()
                    requestLine = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    socket.outputStream.write(
                        "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    socket.outputStream.flush()
                }
            }
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1)))
                .socketFactory(AndroidLocalSocketFactory(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            try {
                val failure = runCatching {
                    client.newCall(Request.Builder().url("https://example.com/test").build()).execute().close()
                }.exceptionOrNull()
                assertTrue(failure is IOException)
                worker.join(1_000)
                assertEquals("Proxy failure: ${failure?.message}", "CONNECT example.com:443 HTTP/1.1", requestLine)
            } finally {
                server.close()
                client.connectionPool.evictAll()
            }
        }
    }
}
