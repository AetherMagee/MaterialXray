package com.material.xray.core.root.di

import com.material.xray.core.common.log.AppLogger
import com.material.xray.core.common.platform.PlatformInfo
import com.material.xray.core.root.RootShell
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
class CoreRootModule {
    @Singleton
    fun rootShell(platformInfo: PlatformInfo, logger: AppLogger): RootShell = RootShell(
        appProcessId = platformInfo.processId,
        logger = logger,
    )
}
