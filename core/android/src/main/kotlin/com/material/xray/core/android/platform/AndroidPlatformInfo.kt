package com.material.xray.core.android.platform

import android.os.Build
import android.os.Process
import com.material.xray.core.common.platform.PlatformInfo
import org.koin.core.annotation.Singleton

/** [PlatformInfo] from `android.os.Build` and the current process. */
@Singleton(binds = [PlatformInfo::class])
class AndroidPlatformInfo : PlatformInfo {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val osName: String get() = "Android"
    override val osVersion: String get() = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: sdkInt.toString()
    override val manufacturer: String get() = Build.MANUFACTURER.orEmpty()
    override val model: String get() = Build.MODEL.orEmpty()
    override val device: String get() = Build.DEVICE.orEmpty()
    override val processId: Int get() = Process.myPid()
    override val uid: Int get() = Process.myUid()
    override val buildFingerprint: String get() = Build.FINGERPRINT.orEmpty()
    override val primaryAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
}
