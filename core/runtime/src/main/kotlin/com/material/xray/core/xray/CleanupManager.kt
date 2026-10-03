package com.material.xray.core.xray

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.material.xray.core.nftables.nftablesRemovalCommand
import com.material.xray.core.root.RootShell
import com.material.xray.core.root.shellQuote
import com.material.xray.model.RootConnectionBackend
import java.io.File

class CleanupManager(
    context: Context,
    private val shell: RootShell,
    private val onStageResult: (String) -> Unit = {},
) {
    private val stateFile = StateFile(context)
    private val tunManager = TunManager(shell)
    private val apiFirewall = XrayApiFirewall(shell)
    private val appUid = context.applicationInfo.uid
    private val configPath = context.filesDir.resolve("config.json").absolutePath
    private val cleanMarker = File(context.filesDir, CLEAN_MARKER_FILE_NAME)

    fun recordKnownCleanState(): Boolean = runCatching {
        cleanMarker.writeText("")
        true
    }.getOrDefault(false)

    fun consumeKnownCleanState(): Boolean = cleanMarker.exists() && cleanMarker.delete()

    suspend fun ensureCleanState(fallbackTunName: String = "xray0", preserveTproxyGuard: Boolean = false): Boolean = cleanUp(stateFile.read(), fallbackTunName, preserveTproxyGuard)

    suspend fun ensureKnownStateStopped(fallbackTunName: String = "xray0", preserveTproxyGuard: Boolean = false): Boolean {
        val state = stateFile.read() ?: return false
        return cleanUp(state, fallbackTunName, preserveTproxyGuard)
    }

    private suspend fun cleanUp(state: XrayState?, fallbackTunName: String, preserveTproxyGuard: Boolean): Boolean {
        val cleaned = runStages(cleanupStages(state, fallbackTunName, preserveTproxyGuard))
        if (cleaned && !preserveTproxyGuard) stateFile.delete()
        return cleaned
    }

    private fun cleanupStages(state: XrayState?, fallbackTunName: String, preserveTproxyGuard: Boolean): List<CleanupStage> = buildList {
        add(CleanupStage("processes", ownedProcessStopCommand(configPath, state?.xrayPid)))
        add(CleanupStage("API firewall", apiFirewall.removeCommand(appUid)))
        add(CleanupStage("legacy nftables", nftablesRemovalCommand()))
        if (state?.rootConnectionBackend != RootConnectionBackend.Tproxy) {
            val command = tunManager.routingRemovalCommand(
                fwmark = state?.fwmark ?: 255,
                routeMark = state?.routeMark ?: 100,
                routeTable = state?.routeTable ?: 100,
                tunName = state?.tunName ?: fallbackTunName,
                managedAppRouteCount = state?.appProxyServerIds?.size ?: 0,
            )
            add(CleanupStage("TUN routing", command))
        }
        val tproxyCommand = TproxyManager.cleanupCommand(state?.tproxy ?: state?.transitionGuard, appUid, preserveTproxyGuard)
        add(CleanupStage("TPROXY routing", tproxyCommand))
    }

    /**
     * Every root command pays for its own shell, and for nsenter when it runs in another namespace,
     * so the stages run as one command that reports each stage's result and duration. A stage left
     * without a report, because the batch timed out or the shell went away, runs on its own as it did
     * before batching, so one stuck stage cannot keep the later ones from running.
     */
    private suspend fun runStages(stages: List<CleanupStage>): Boolean {
        val result = shell.execute(cleanupBatchCommand(stages.map(CleanupStage::command)), timeoutMs = BATCH_TIMEOUT_MS)
        val reports = parseCleanupStageReports(result.output)
        return stages.withIndex().map { (index, stage) ->
            val report = reports[index] ?: runStage(stage)
            val succeeded = report.exitCode == 0
            val message = "Cleanup ${stage.name} ${if (succeeded) "succeeded" else "failed"} after ${report.durationMs} ms"
            if (succeeded) Log.i(LOG_TAG, message) else Log.w(LOG_TAG, message)
            onStageResult(message)
            succeeded
        }.all { it }
    }

    private suspend fun runStage(stage: CleanupStage): CleanupStageReport {
        val startedAt = SystemClock.elapsedRealtime()
        val exitCode = shell.execute(stage.command).exitCode
        return CleanupStageReport(exitCode, SystemClock.elapsedRealtime() - startedAt)
    }

    private class CleanupStage(val name: String, val command: String)

    private companion object {
        const val CLEAN_MARKER_FILE_NAME = "root-runtime-clean"
        const val LOG_TAG = "MXray.cleanup"
        const val BATCH_TIMEOUT_MS = 30_000L
    }
}

internal data class CleanupStageReport(val exitCode: Int, val durationMs: Long)

/**
 * Runs each stage in its own subshell, so a stage that exits ends only itself and the later stages
 * still run, and prints a marker line with its exit code and duration after it. Durations come from
 * /proc/uptime, which a builtin read takes without starting a process, at its 10 ms resolution.
 */
internal fun cleanupBatchCommand(stages: List<String>): String = buildString {
    append("mx_clock() { read mx_t _ < /proc/uptime; mx_t=\${mx_t%.*}\${mx_t#*.}; }\nmx_failed=0\n")
    stages.forEachIndexed { index, stage ->
        append("mx_clock; mx_start=\$mx_t\n(\n$stage\n)\nmx_rc=\$?; mx_clock; [ \$mx_rc -eq 0 ] || mx_failed=1\n")
        append("printf '\\n$CLEANUP_STAGE_MARKER %s %s %s\\n' $index \$mx_rc \$(( (mx_t - mx_start) * 10 ))\n")
    }
    append("[ \$mx_failed -eq 0 ]")
}

internal fun parseCleanupStageReports(output: String): Map<Int, CleanupStageReport> = output.lineSequence()
    .mapNotNull { line -> CLEANUP_STAGE_REPORT.matchEntire(line.trim()) }
    .associate { match ->
        val (index, exitCode, durationMs) = match.destructured
        index.toInt() to CleanupStageReport(exitCode.toInt(), durationMs.toLong())
    }

private const val CLEANUP_STAGE_MARKER = "__MXRAY_CLEANUP_STAGE__"
private val CLEANUP_STAGE_REPORT = Regex("$CLEANUP_STAGE_MARKER (\\d+) (\\d+) (\\d+)")

internal fun ownedProcessStopCommand(configPath: String, persistedPid: Int?): String = buildString {
    append("config=${shellQuote(configPath)}; candidates=${shellQuote(persistedPid?.takeIf { it > 0 }?.toString().orEmpty())}; ")
    append("is_owned() { [ -e \"/proc/\$1\" ] || return 1; [ -r \"/proc/\$1/cmdline\" ] || return 1; ")
    // Unlike Toybox tr, cat terminates if a procfs read races with process exit.
    append("cmdline=\$(cat -v \"/proc/\$1/cmdline\" 2>/dev/null) || return 1; ")
    append("case \"\$cmdline\" in *\"\$config\"*) return 0;; *) return 1;; esac; }; ")
    append("for pid in \$(pidof $XRAY_EXECUTABLE_NAME $LEGACY_ROOT_EXECUTABLE_NAME 2>/dev/null); do case \" \$candidates \" in *\" \$pid \"*) ;; ")
    append("*) candidates=\"\$candidates \$pid\";; esac; done; owned=''; ")
    append("for pid in \$candidates; do case \"\$pid\" in ''|*[!0-9]*) continue;; esac; ")
    append("if is_owned \"\$pid\"; then owned=\"\$owned \$pid\"; fi; done; ")
    append("for pid in \$owned; do if is_owned \"\$pid\"; then kill \"\$pid\" 2>/dev/null || ")
    append("{ if is_owned \"\$pid\"; then exit 1; fi; }; fi; done; ")
    append("attempt=0; while [ \$attempt -lt 20 ]; do alive=''; for pid in \$owned; do ")
    append("if is_owned \"\$pid\"; then alive=\"\$alive \$pid\"; fi; ")
    append("done; [ -z \"\$alive\" ] && break; ")
    append("sleep 0.05; attempt=\$((attempt + 1)); done; ")
    append("for pid in \$alive; do if is_owned \"\$pid\"; then kill -9 \"\$pid\" 2>/dev/null || ")
    append("{ if is_owned \"\$pid\"; then exit 1; fi; }; fi; done; ")
    append("[ -z \"\$alive\" ] || sleep 0.05; for pid in \$alive; do if is_owned \"\$pid\"; then exit 1; ")
    append("fi; done; true")
}

// Releases up to 0.9.2 ran the root core as files/bin/xray. A core one of them left running is
// still found here when the state file that holds its pid is gone; drop this once those
// upgrades have passed.
private const val LEGACY_ROOT_EXECUTABLE_NAME = "xray"
