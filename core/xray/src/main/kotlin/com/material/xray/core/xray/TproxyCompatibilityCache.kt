package com.material.xray.core.xray

/**
 * Persists the encoded TPROXY verdict across processes. TPROXY support belongs to the kernel and
 * ROM, so a stored verdict only answers for the OS build it was stored under.
 */
interface TproxyCompatibilityCache {
    /** The verdict stored under [buildFingerprint], or null when none was stored for that build. */
    fun read(buildFingerprint: String): String?

    fun write(buildFingerprint: String, encoded: String)
}
