package com.material.xray.core.telemetry

import com.material.xray.core.common.telemetry.DiagnosticsConsentMirroring
import java.io.File

/**
 * Synchronous mirror of the diagnostics setting for startup code that runs before Koin and
 * DataStore are available. An absent or unreadable value is always treated as disabled.
 */
class DiagnosticsConsentMirror(
    private val file: File,
) : DiagnosticsConsentMirroring {
    fun isEnabled(): Boolean = runCatching { file.readText().trim() == ENABLED_VALUE }.getOrDefault(false)

    override fun setEnabled(enabled: Boolean) {
        runCatching { file.writeText(if (enabled) ENABLED_VALUE else DISABLED_VALUE) }
    }

    companion object {
        /** The mirror's file name inside the platform's no-backup directory. */
        const val FILE_NAME = "diagnostics-startup-enabled"
        private const val ENABLED_VALUE = "true"
        private const val DISABLED_VALUE = "false"
    }
}
