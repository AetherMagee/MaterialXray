package com.material.xray.core.runtime.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("com.material.xray.core.runtime", "com.material.xray.service")
class CoreRuntimeModule
