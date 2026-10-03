package com.material.xray.core.android.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("com.material.xray.core.app", "com.material.xray.core.launcher", "com.material.xray.core.locale")
class CoreAndroidModule
