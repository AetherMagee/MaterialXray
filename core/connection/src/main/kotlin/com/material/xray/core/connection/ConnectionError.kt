package com.material.xray.core.connection

/** A user-facing reason a connection failed; the platform turns it into localized text. */
sealed interface ConnectionError {
    data object Unknown : ConnectionError

    data object VpnPermissionRequired : ConnectionError

    data object MissingProcessId : ConnectionError

    data object CleanupFailed : ConnectionError

    data object TunNameDetection : ConnectionError

    data object RootAccessDenied : ConnectionError

    data object SecureXrayApi : ConnectionError

    data object XrayBinaryNotFound : ConnectionError

    data object PhysicalRouteNotFound : ConnectionError

    data class ServerAddressUnresolved(val host: String) : ConnectionError

    data object TproxyHealthCheck : ConnectionError

    /** [detail] is what the routing tool reported, when it reported anything. */
    data class ApplyIpRouting(val detail: String?) : ConnectionError

    data class XrayCrashed(val reason: String) : ConnectionError

    data class TunTimeout(val tunName: String) : ConnectionError

    data object XrayApiNotReady : ConnectionError
}
