package com.material.xray.core.xray

import java.io.File

/**
 * Where the core's files live. On Android, `:core:android` binds `AndroidXrayPaths`, which reads
 * them from the app's `Context`.
 */
interface XrayPaths {
    /** App-private storage for the core's config, the runtime state and its asset directory. */
    val filesDir: File

    /** Where the installer extracted the native executables; null when the platform has none. */
    val nativeLibraryDir: File?
}
