package com.material.xray.core.android.di

import android.content.Context
import com.material.xray.core.android.telemetry.diagnosticsConsentMirror
import com.material.xray.core.common.telemetry.DiagnosticsConsentMirroring
import com.material.xray.core.telemetry.DiagnosticsConsentMirror
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@ComponentScan("com.material.xray.core.android")
class CoreAndroidModule {
    // The mirror is a plain file in the JVM module; only the platform knows the no-backup directory.
    @Singleton(binds = [DiagnosticsConsentMirroring::class])
    fun provideDiagnosticsConsentMirror(context: Context): DiagnosticsConsentMirror = diagnosticsConsentMirror(context)
}
