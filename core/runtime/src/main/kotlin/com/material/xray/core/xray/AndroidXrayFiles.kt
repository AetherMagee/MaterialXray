package com.material.xray.core.xray

import android.content.Context
import com.material.xray.core.android.platform.LogcatAppLogger
import com.material.xray.core.android.xray.AndroidXrayPaths

/** The runtime state file in [context]'s files directory, logging to logcat. */
internal fun StateFile(context: Context): StateFile = StateFile(AndroidXrayPaths(context), LogcatAppLogger())

/** The core binary from [context]'s native library directory. */
internal fun XrayBinary(context: Context): XrayBinary = XrayBinary(AndroidXrayPaths(context))
