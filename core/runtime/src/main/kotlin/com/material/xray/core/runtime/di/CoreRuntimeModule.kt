package com.material.xray.core.runtime.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("com.material.xray.service", "com.material.xray.core.xray")
class CoreRuntimeModule
