package com.material.xray.core.connection

import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.XraySysStats
import kotlinx.coroutines.sync.Mutex

/**
 * The stats and routing clients of the running core's control API.
 *
 * Every use holds [mutex]. [requestClose] runs from the service's destruction and cannot suspend,
 * so it closes the clients now when nothing holds them, and otherwise leaves the close to whichever
 * use finishes next.
 */
internal class XrayApiClients(private val factory: ConnectionApiClientFactory) {
    @Volatile private var statsClient: ConnectionStatsClient? = null

    @Volatile private var routingClient: ConnectionRoutingClient? = null

    private val mutex = Mutex()

    private val closeGate = Any()

    private var shutdownRequested = false

    suspend fun replace(endpoint: XrayApiEndpoint) = withClients {
        closeLocked()
        factory.create(endpoint).also { clients ->
            statsClient = clients.stats
            routingClient = clients.routing
        }
    }

    suspend fun close() = withClients { closeLocked() }

    fun requestClose() {
        synchronized(closeGate) {
            shutdownRequested = true
            if (!mutex.tryLock()) return
            try {
                closeLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    suspend fun readOutboundTrafficStatsBytes(): Map<String, Long> = withClients {
        statsClient?.queryOutboundTrafficStatsBytes().orEmpty()
    }

    suspend fun readSysStats(): XraySysStats? = withClients { statsClient?.getSysStats() }

    suspend fun readBalancerSelection(balancerTag: String) = withClients {
        routingClient?.queryBalancerSelection(balancerTag)
    }

    private fun closeLocked() {
        statsClient?.close()
        statsClient = null
        routingClient?.close()
        routingClient = null
    }

    private suspend fun <T> withClients(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            synchronized(closeGate) {
                try {
                    if (shutdownRequested) {
                        closeLocked()
                    }
                } finally {
                    mutex.unlock()
                }
            }
        }
    }
}
