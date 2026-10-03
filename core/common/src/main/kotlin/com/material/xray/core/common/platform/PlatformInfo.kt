package com.material.xray.core.common.platform

/**
 * Facts about the host OS and process that platform-free code needs, in place of
 * `android.os.Build` and `android.os.Process`.
 *
 * On Android, `:core:android` provides `AndroidPlatformInfo` (from `Build` and `Process.myPid()`)
 * and binds it as the [PlatformInfo] singleton. Other JVM hosts and tests use [JvmPlatformInfo].
 */
interface PlatformInfo {
    /** Android API level (`Build.VERSION.SDK_INT`); [NOT_ANDROID_SDK_INT] off Android. */
    val sdkInt: Int

    /** OS name for user-facing strings such as a user agent: `"Android"` on Android. */
    val osName: String

    /** OS release (`Build.VERSION.RELEASE`, falling back to [sdkInt]); `os.version` off Android. */
    val osVersion: String

    /** Device manufacturer (`Build.MANUFACTURER`), blank when unknown. */
    val manufacturer: String

    /** Device model (`Build.MODEL`), blank when unknown. */
    val model: String

    /** Device code name (`Build.DEVICE`), blank when unknown. */
    val device: String

    /** Id of the current process (`Process.myPid()`). */
    val processId: Int

    companion object {
        /**
         * [sdkInt] on a host that is not Android. Above every API level, so [atLeast] holds: the
         * desktop JVM has every Java API that the app's `SDK_INT` checks guard.
         */
        const val NOT_ANDROID_SDK_INT = Int.MAX_VALUE
    }
}

/** Replaces `Build.VERSION.SDK_INT >= api`. */
fun PlatformInfo.atLeast(api: Int): Boolean = sdkInt >= api

/** `"<manufacturer> <model>"`, falling back to [PlatformInfo.device], then [PlatformInfo.osName]. */
val PlatformInfo.deviceModel: String
    get() = listOf(manufacturer.trim(), model.trim())
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { device.trim().ifBlank { osName } }

/** The current JVM process, for desktop hosts and tests. */
object JvmPlatformInfo : PlatformInfo {
    override val sdkInt: Int get() = PlatformInfo.NOT_ANDROID_SDK_INT
    override val osName: String get() = System.getProperty("os.name").orEmpty()
    override val osVersion: String get() = System.getProperty("os.version").orEmpty()
    override val manufacturer: String get() = ""
    override val model: String get() = ""
    override val device: String get() = ""

    // ProcessHandle does not exist on Android; the getter defers it until asked.
    override val processId: Int get() = ProcessHandle.current().pid().toInt()
}
