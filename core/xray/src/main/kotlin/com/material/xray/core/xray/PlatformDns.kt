package com.material.xray.core.xray

/**
 * The platform's own resolver, which [ServerAddressResolver] prefers to the blocking JVM lookup
 * because it can be abandoned when it stalls. On Android, `:core:android` binds `AndroidPlatformDns`
 * (`DnsResolver` on the active network).
 */
interface PlatformDns {
    /** Names the network lookups currently go out on, so an answer is not reused on another; 0 when unknown. */
    fun activeNetworkHandle(): Long

    /**
     * Resolves [host] to addresses, or returns null when the platform has no such resolver so the
     * blocking lookup runs instead. An empty list means the lookup failed.
     */
    suspend fun query(host: String): List<String>?

    /** No platform resolver: every lookup takes the blocking JVM path. */
    object None : PlatformDns {
        override fun activeNetworkHandle(): Long = 0

        override suspend fun query(host: String): List<String>? = null
    }
}
