package com.material.xray.core.android.telemetry

import android.content.Context
import com.material.xray.core.telemetry.DiagnosticsConsentMirror

/** The [DiagnosticsConsentMirror] in the app's no-backup directory, readable before Koin starts. */
fun diagnosticsConsentMirror(context: Context): DiagnosticsConsentMirror = DiagnosticsConsentMirror(context.noBackupFilesDir.resolve(DiagnosticsConsentMirror.FILE_NAME))
