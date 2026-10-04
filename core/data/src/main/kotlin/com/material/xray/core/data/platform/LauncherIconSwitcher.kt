package com.material.xray.core.data.platform

import com.material.xray.core.model.LauncherIcon

/** Shows [LauncherIcon] as the app's launcher entry; restoring a backup re-applies the stored choice. */
fun interface LauncherIconSwitcher {
    fun apply(icon: LauncherIcon)
}
