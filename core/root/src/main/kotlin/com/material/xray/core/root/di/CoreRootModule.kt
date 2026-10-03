package com.material.xray.core.root.di

import com.material.xray.core.root.RootShell
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
class CoreRootModule {
    @Singleton
    fun rootShell(): RootShell = RootShell()
}
