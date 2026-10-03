package com.material.xray.core.xray

import javax.net.SocketFactory

/**
 * Opens Unix domain sockets as [java.net.Socket]s, which is the shape gRPC and OkHttp dial
 * through. The JVM's sockets cannot reach them, so each platform supplies its own; on Android,
 * `:core:android` binds `AndroidLocalSockets`.
 */
interface LocalSockets {
    /** Sockets that connect to [name] in Linux's abstract socket namespace. */
    fun abstractSocketFactory(name: String): SocketFactory

    /** Sockets that connect to the socket file at [path]. */
    fun fileSystemSocketFactory(path: String): SocketFactory
}
