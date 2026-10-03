package com.material.xray.core.android.xray

import android.content.Context
import com.material.xray.core.xray.XrayPaths
import java.io.File
import org.koin.core.annotation.Singleton

/** [XrayPaths] in the app's private files and the installer's native library directory. */
@Singleton(binds = [XrayPaths::class])
class AndroidXrayPaths(private val context: Context) : XrayPaths {
    override val filesDir: File
        get() = context.filesDir

    override val cacheDir: File
        get() = context.cacheDir

    override val nativeLibraryDir: File?
        get() = context.applicationInfo.nativeLibraryDir?.let(::File)
}
