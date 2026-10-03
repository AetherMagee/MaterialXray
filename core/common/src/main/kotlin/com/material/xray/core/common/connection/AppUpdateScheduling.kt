package com.material.xray.core.common.connection

import com.material.xray.model.AppUpdateInterval

/** Schedules, or cancels, the periodic check for a new app release. */
fun interface AppUpdateScheduling {
    fun setEnabled(enabled: Boolean, interval: AppUpdateInterval)
}
