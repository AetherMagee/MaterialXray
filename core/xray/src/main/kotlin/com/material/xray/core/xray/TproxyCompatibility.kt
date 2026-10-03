package com.material.xray.core.xray

sealed interface TproxyCompatibility {
    data object Unknown : TproxyCompatibility
    data object Checking : TproxyCompatibility

    data class Supported(
        val ipv6: Boolean,
    ) : TproxyCompatibility

    data class Unsupported(
        val reason: Reason,
        val details: String? = null,
    ) : TproxyCompatibility

    enum class Reason {
        RootUnavailable,
        InitNetworkNamespaceUnavailable,
        IptablesMangleUnavailable,
        ProcessGroupUnavailable,
        OwnerMatchUnavailable,
        MarkTargetUnavailable,
        TproxyIpv4Unavailable,
        Ipv6BlockingUnavailable,
        ListenerInspectionUnavailable,
        PolicyRoutingUnavailable,
        RouteTableConflict,
        TproxyIpv6Unavailable,
        MarkNamespaceConflict,
        ProbeCleanupFailed,
        CommandTimedOut,
    }
}
