package com.material.xray.core.common.platform

/**
 * Facts about the installed build of the app, in place of reading the package info.
 *
 * On Android, `:core:android` binds an implementation on `PackageManager.getPackageInfo`.
 */
interface AppBuildInfo {
    /** The installed version name, blank when it cannot be read. */
    val versionName: String
}
