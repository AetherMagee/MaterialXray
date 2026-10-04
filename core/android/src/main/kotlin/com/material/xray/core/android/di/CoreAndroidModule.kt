package com.material.xray.core.android.di

import android.content.Context
import com.material.xray.core.android.telemetry.diagnosticsConsentMirror
import com.material.xray.core.common.telemetry.DiagnosticsConsentMirroring
import com.material.xray.telemetry.DiagnosticsConsentMirror
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@ComponentScan(
    "com.material.xray.core.app",
    "com.material.xray.core.launcher",
    "com.material.xray.core.locale",
    "com.material.xray.core.android.data",
    "com.material.xray.core.android.network",
    "com.material.xray.core.android.platform",
    "com.material.xray.core.android.telemetry",
    "com.material.xray.core.android.xray",
)
class CoreAndroidModule {
    // The mirror is a plain file in the JVM module; only the platform knows the no-backup directory.
    @Singleton(binds = [DiagnosticsConsentMirroring::class])
    fun diagnosticsConsentMirror(context: Context): DiagnosticsConsentMirror = diagnosticsConsentMirror(context)
}
