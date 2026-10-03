package com.material.xray.core.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.material.xray.data.platform.LauncherIconSwitcher
import com.material.xray.model.LauncherIcon
import org.koin.core.annotation.Singleton

@Singleton(binds = [LauncherIconSwitcher::class])
class LauncherIconManager(
    private val context: Context,
) : LauncherIconSwitcher {
    override fun apply(icon: LauncherIcon) {
        val packageManager = context.packageManager
        packageManager.setComponentEnabledSetting(
            icon.componentName(),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        LauncherIcon.entries
            .filterNot { it == icon }
            .forEach { disabledIcon ->
                packageManager.setComponentEnabledSetting(
                    disabledIcon.componentName(),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }

    private fun LauncherIcon.componentName(): ComponentName = ComponentName(context, "${context.packageName}.$aliasClassName")
}
