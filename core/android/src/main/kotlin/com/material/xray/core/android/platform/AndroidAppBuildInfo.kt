package com.material.xray.core.android.platform

import android.content.Context
import com.material.xray.core.common.platform.AppBuildInfo
import org.koin.core.annotation.Singleton

/** [AppBuildInfo] from this package's `PackageInfo`. */
@Singleton(binds = [AppBuildInfo::class])
class AndroidAppBuildInfo(private val context: Context) : AppBuildInfo {
    override val versionName: String by lazy {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
}
