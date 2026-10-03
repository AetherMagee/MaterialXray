package com.material.xray.core.common.telemetry

/**
 * Records the diagnostics opt-in where startup code reads it before Koin and the settings store
 * exist. An absent or unreadable record means disabled.
 */
fun interface DiagnosticsConsentMirroring {
    fun setEnabled(enabled: Boolean)
}
