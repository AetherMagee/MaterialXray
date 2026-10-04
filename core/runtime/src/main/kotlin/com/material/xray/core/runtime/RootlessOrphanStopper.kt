package com.material.xray.core.runtime

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.material.xray.core.xray.ACTIVE_CONFIG_FILE
import com.material.xray.core.xray.XRAY_EXECUTABLE_NAME
import java.io.File
import kotlinx.coroutines.delay
import org.koin.core.annotation.Factory

/** Stops only a recorded child that still belongs to this app and uses its Xray config. */
@Factory
class RootlessOrphanStopper(private val context: Context) {
    suspend fun stop(pid: Int): Boolean {
        if (pid <= 0) return true
        when (inspect(pid)) {
            ProcessIdentity.Absent -> return true
            ProcessIdentity.Unknown -> return false
            ProcessIdentity.Owned -> Unit
        }
        if (!signalIfOwned(pid, OsConstants.SIGTERM)) return false
        if (waitUntilGone(pid, TERM_TIMEOUT_MS)) return true
        if (!signalIfOwned(pid, OsConstants.SIGKILL)) return false
        return waitUntilGone(pid, KILL_TIMEOUT_MS)
    }

    private fun signalIfOwned(pid: Int, signal: Int): Boolean = when (inspect(pid)) {
        ProcessIdentity.Absent -> true
        ProcessIdentity.Unknown -> false
        ProcessIdentity.Owned -> runCatching { Os.kill(pid, signal) }.isSuccess || inspect(pid) == ProcessIdentity.Absent
    }

    private suspend fun waitUntilGone(pid: Int, timeoutMs: Long): Boolean {
        var elapsedMs = 0L
        while (elapsedMs <= timeoutMs) {
            if (inspect(pid) == ProcessIdentity.Absent) return true
            delay(POLL_INTERVAL_MS)
            elapsedMs += POLL_INTERVAL_MS
        }
        return false
    }

    private fun inspect(pid: Int): ProcessIdentity {
        val procDir = File("/proc/$pid")
        if (!procDir.exists()) return ProcessIdentity.Absent
        val status = runCatching { File(procDir, "status").readLines() }.getOrNull()
            ?: return ProcessIdentity.Unknown
        if (effectiveUidFromStatus(status) != context.applicationInfo.uid) return ProcessIdentity.Unknown
        if (status.any { it.startsWith("State:") && it.substringAfter(':').trimStart().startsWith('Z') }) {
            return ProcessIdentity.Absent
        }
        val cmdline = runCatching { File(procDir, "cmdline").readBytes().toString(Charsets.UTF_8) }
            .getOrNull() ?: return ProcessIdentity.Unknown
        val configPath = File(context.filesDir, ACTIVE_CONFIG_FILE).absolutePath
        return if (isOwnedXrayCommand(cmdline.split('\u0000'), configPath)) {
            ProcessIdentity.Owned
        } else {
            ProcessIdentity.Unknown
        }
    }

    private enum class ProcessIdentity { Absent, Owned, Unknown }

    private companion object {
        const val TERM_TIMEOUT_MS = 1_000L
        const val KILL_TIMEOUT_MS = 500L
        const val POLL_INTERVAL_MS = 50L
    }
}

// A core the app cannot exec directly runs as an argument of the system linker.
internal fun isOwnedXrayCommand(args: List<String>, configPath: String): Boolean {
    val core = if (args.firstOrNull()?.let(SYSTEM_LINKERS::contains) == true) args.getOrNull(1) else args.firstOrNull()
    return core?.endsWith("/$XRAY_EXECUTABLE_NAME") == true &&
        args.windowed(2).any { (flag, value) -> flag == "-c" && value == configPath }
}

private val SYSTEM_LINKERS = setOf("/system/bin/linker64", "/system/bin/linker")

internal fun effectiveUidFromStatus(lines: List<String>): Int? = lines.firstOrNull { it.startsWith("Uid:") }
    ?.substringAfter(':')
    ?.trim()
    ?.split(Regex("\\s+"))
    ?.getOrNull(1)
    ?.toIntOrNull()
