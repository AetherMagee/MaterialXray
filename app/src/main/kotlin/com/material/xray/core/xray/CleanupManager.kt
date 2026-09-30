package com.material.xray.core.xray

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.material.xray.core.nftables.NftablesManager
import com.material.xray.core.root.RootShell
import com.material.xray.model.RootConnectionBackend
import java.io.File

class CleanupManager(
    context: Context,
    private val shell: RootShell,
    private val onStageResult: (String) -> Unit = {},
) {
    private val stateFile = StateFile(context)
    private val nftables = NftablesManager(shell)
    private val tunManager = TunManager(shell)
    private val tproxyManager = TproxyManager(shell, appUid = context.applicationInfo.uid)
    private val apiFirewall = XrayApiFirewall(shell)
    private val appUid = context.applicationInfo.uid
    private val configPath = context.filesDir.resolve("config.json").absolutePath
    private val cleanMarker = File(context.filesDir, CLEAN_MARKER_FILE_NAME)

    fun recordKnownCleanState(): Boolean = runCatching {
        cleanMarker.writeText("")
        true
    }.getOrDefault(false)

    fun consumeKnownCleanState(): Boolean = cleanMarker.exists() && cleanMarker.delete()

    suspend fun ensureCleanState(fallbackTunName: String = "xray0", preserveTproxyGuard: Boolean = false): Boolean {
        val state = stateFile.read()
        val processesStopped = cleanupStage("processes") { stopOwnedProcesses(state?.xrayPid) }
        val runtimeRemoved = removeRuntimeState(state, fallbackTunName, preserveTproxyGuard)
        if (processesStopped && runtimeRemoved && !preserveTproxyGuard) stateFile.delete()
        return processesStopped && runtimeRemoved
    }

    suspend fun ensureKnownStateStopped(fallbackTunName: String = "xray0", preserveTproxyGuard: Boolean = false): Boolean {
        val state = stateFile.read() ?: return false
        val processesStopped = cleanupStage("processes") { stopOwnedProcesses(state.xrayPid) }
        val runtimeRemoved = removeRuntimeState(state, fallbackTunName, preserveTproxyGuard)
        if (processesStopped && runtimeRemoved && !preserveTproxyGuard) stateFile.delete()
        return processesStopped && runtimeRemoved
    }

    private suspend fun removeRuntimeState(
        state: XrayState?,
        fallbackTunName: String,
        preserveTproxyGuard: Boolean,
    ): Boolean {
        val firewallRemoved = cleanupStage("API firewall") { apiFirewall.remove(appUid) }
        val nftablesRemoved = cleanupStage("legacy nftables") { nftables.remove() }

        val routingRemoved = if (state?.rootConnectionBackend == RootConnectionBackend.Tproxy) {
            true
        } else {
            val tunName = state?.tunName ?: fallbackTunName
            val fwmark = state?.fwmark ?: 255
            val routeMark = state?.routeMark ?: 100
            val routeTable = state?.routeTable ?: 100
            val appRouteCount = state?.appProxyServerIds?.size?.takeIf { it > 0 } ?: 0
            cleanupStage("TUN routing") {
                tunManager.removeRouting(fwmark, routeMark, routeTable, tunName, appRouteCount)
            }
        }
        val tproxyRemoved = cleanupStage("TPROXY routing") {
            tproxyManager.remove(state?.tproxy ?: state?.transitionGuard, preserveTproxyGuard)
        }
        return firewallRemoved && nftablesRemoved && routingRemoved && tproxyRemoved
    }

    private suspend fun cleanupStage(name: String, action: suspend () -> Boolean): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        var succeeded = false
        try {
            succeeded = action()
            return succeeded
        } finally {
            val durationMs = SystemClock.elapsedRealtime() - startedAt
            val message = "Cleanup $name ${if (succeeded) "succeeded" else "failed"} after $durationMs ms"
            if (succeeded) Log.i(LOG_TAG, message) else Log.w(LOG_TAG, message)
            onStageResult(message)
        }
    }

    private suspend fun stopOwnedProcesses(persistedPid: Int?): Boolean = shell.execute(
        ownedProcessStopCommand(configPath, persistedPid),
    ).isSuccess

    private companion object {
        const val CLEAN_MARKER_FILE_NAME = "root-runtime-clean"
        const val LOG_TAG = "MXray.cleanup"
    }
}

internal fun ownedProcessStopCommand(configPath: String, persistedPid: Int?): String = buildString {
    append("config=${shellQuote(configPath)}; candidates=${shellQuote(persistedPid?.takeIf { it > 0 }?.toString().orEmpty())}; ")
    append("is_owned() { [ -e \"/proc/\$1\" ] || return 1; [ -r \"/proc/\$1/cmdline\" ] || return 1; ")
    // Unlike Toybox tr, cat terminates if a procfs read races with process exit.
    append("cmdline=\$(cat -v \"/proc/\$1/cmdline\" 2>/dev/null) || return 1; ")
    append("case \"\$cmdline\" in *\"\$config\"*) return 0;; *) return 1;; esac; }; ")
    append("for pid in \$(pidof xray 2>/dev/null); do case \" \$candidates \" in *\" \$pid \"*) ;; ")
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
