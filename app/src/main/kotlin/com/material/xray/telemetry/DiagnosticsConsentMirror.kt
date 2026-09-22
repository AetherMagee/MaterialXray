package com.material.xray.telemetry

import android.content.Context
import java.io.File

/**
 * Synchronous mirror of the diagnostics setting for startup code that runs before Hilt and
 * DataStore are available. An absent or unreadable value is always treated as disabled.
 */
internal class DiagnosticsConsentMirror(
    private val file: File,
) {
    constructor(context: Context) : this(context.noBackupFilesDir.resolve(FILE_NAME))

    fun isEnabled(): Boolean = runCatching { file.readText().trim() == ENABLED_VALUE }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        runCatching { file.writeText(if (enabled) ENABLED_VALUE else DISABLED_VALUE) }
    }

    private companion object {
        const val FILE_NAME = "diagnostics-startup-enabled"
        const val ENABLED_VALUE = "true"
        const val DISABLED_VALUE = "false"
    }
}
