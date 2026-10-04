package com.material.xray.core.runtime

import android.content.Context
import com.material.xray.core.android.platform.LogcatAppLogger
import com.material.xray.core.android.xray.AndroidXrayPaths
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.XrayBinary

/** The runtime state file in [context]'s files directory, logging to logcat. */
internal fun appStateFile(context: Context): StateFile = StateFile(AndroidXrayPaths(context), LogcatAppLogger())

/** The core binary from [context]'s native library directory. */
internal fun appXrayBinary(context: Context): XrayBinary = XrayBinary(AndroidXrayPaths(context))
