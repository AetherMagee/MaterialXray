package com.material.xray.core.xray

import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder

sealed interface XrayApiEndpoint {
    data class UnixSocket(val name: String) : XrayApiEndpoint

    data class FileSystemUnixSocket(val path: String) : XrayApiEndpoint {
        init {
            require(path.startsWith('/')) { "Xray API socket path must be absolute: $path" }
        }
    }

    data class LoopbackTcp(val port: Int) : XrayApiEndpoint {
        init {
            require(port in 1..65_535) { "Invalid Xray API port: $port" }
        }
    }
}

internal fun buildXrayApiChannel(endpoint: XrayApiEndpoint, localSockets: LocalSockets): ManagedChannel {
    val credentials = InsecureChannelCredentials.create()
    val builder = when (endpoint) {
        is XrayApiEndpoint.UnixSocket ->
            OkHttpChannelBuilder
                .forTarget(UNUSED_XRAY_API_GRPC_TARGET, credentials)
                .socketFactory(localSockets.abstractSocketFactory(endpoint.name))
        is XrayApiEndpoint.FileSystemUnixSocket ->
            OkHttpChannelBuilder
                .forTarget(UNUSED_XRAY_API_GRPC_TARGET, credentials)
                .socketFactory(localSockets.fileSystemSocketFactory(endpoint.path))
        is XrayApiEndpoint.LoopbackTcp ->
            OkHttpChannelBuilder
                .forAddress(XRAY_API_LOOPBACK_ADDRESS, endpoint.port, credentials)
    }
    return builder.proxyDetector { null }.build()
}

const val XRAY_API_LOOPBACK_ADDRESS = "127.0.0.1"

fun XrayApiEndpoint.cliServerAddress(): String? = when (this) {
    is XrayApiEndpoint.FileSystemUnixSocket -> "unix://$path"
    is XrayApiEndpoint.LoopbackTcp -> "$XRAY_API_LOOPBACK_ADDRESS:$port"
    is XrayApiEndpoint.UnixSocket -> null
}

private const val UNUSED_XRAY_API_GRPC_TARGET = "dns:///127.0.0.1"
