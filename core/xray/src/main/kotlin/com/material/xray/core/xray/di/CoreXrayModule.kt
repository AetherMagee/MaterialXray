package com.material.xray.core.xray.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("com.material.xray.core.xray", "com.material.xray.core.nftables")
class CoreXrayModule
