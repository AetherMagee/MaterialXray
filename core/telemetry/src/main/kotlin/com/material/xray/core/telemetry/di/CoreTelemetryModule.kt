package com.material.xray.core.telemetry.di

import android.content.Context
import com.material.xray.core.common.telemetry.DiagnosticsConsentMirroring
import com.material.xray.telemetry.DiagnosticsConsentMirror
import com.material.xray.telemetry.TelemetryClient
import com.material.xray.telemetry.TelemetryReporter
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@ComponentScan("com.material.xray.telemetry")
class CoreTelemetryModule {
    // The primary constructor takes test seams, so the graph uses the secondary one.
    @Singleton
    fun telemetryReporter(client: TelemetryClient): TelemetryReporter = TelemetryReporter(client)

    // The primary constructor takes the file as a test seam; the graph resolves it from Context.
    @Singleton(binds = [DiagnosticsConsentMirroring::class])
    fun diagnosticsConsentMirror(context: Context): DiagnosticsConsentMirror = DiagnosticsConsentMirror(context)
}
