package com.material.xray.core.connection

interface XrayProcessProbe {
    suspend fun isAlive(pid: Int): Boolean
}

/** A TUN interface the root core owns. The Android build cannot create one, nor size it. */
data class RootTunDevice(val name: String, val mtu: Int)

interface RootXrayProcessController : XrayProcessProbe {
    suspend fun prepareLogFile()

    /**
     * Starts the core as root. A non-null [tun] is created by the TUN launcher and handed to the
     * core as an open descriptor, so the interface lives exactly as long as the core does.
     */
    suspend fun start(binDir: String, primaryGid: Int? = null, tun: RootTunDevice? = null): Int
    suspend fun kill(pid: Int, signal: Int = 15): Boolean
    suspend fun readResidentMemoryMb(pid: Int): Long?
    suspend fun readCrashReason(lines: Int = 80): String
    suspend fun ensureNativeRuntimeExemptions()
}

interface UserXrayProcessController : XrayProcessProbe {
    suspend fun prepareLogFile()

    /**
     * Starts the core against [tunFd], which the caller still owns. Implementations must not
     * suspend before the descriptor has been duplicated into the child.
     */
    fun start(binDir: String, tunFd: Int): Int
    suspend fun kill(pid: Int, signal: Int = 15): Boolean
    suspend fun stop()
    suspend fun stopOrphan(pid: Int)
    fun requestStop()
    suspend fun readResidentMemoryMb(pid: Int): Long?
    suspend fun readCrashReason(lines: Int = 80): String
    fun readActiveConnectionCount(pid: Int): Int?
}

interface XrayProcessBinary {
    val binaryPath: String?
    val tunLauncherPath: String?
    fun configPath(): String
}
