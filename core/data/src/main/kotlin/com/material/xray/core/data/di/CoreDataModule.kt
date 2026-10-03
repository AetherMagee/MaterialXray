package com.material.xray.core.data.di

import com.material.xray.core.common.connection.RoutingChangeNotifier
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.data.repository.SubscriptionRoutingRepository
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@ComponentScan("com.material.xray.data.parser", "com.material.xray.data.repository")
class CoreDataModule {

    // The primary constructor takes test seams, so the graph uses the secondary one.
    @Singleton
    fun providerRoutingCoordinator(
        settingsRepository: SettingsRepository,
        serverRepository: ServerRepository,
        subscriptionDao: SubscriptionDao,
        subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
        subscriptionRoutingRepository: SubscriptionRoutingRepository,
        routingChangeNotifier: RoutingChangeNotifier,
    ): ProviderRoutingCoordinator = ProviderRoutingCoordinator(
        settingsRepository,
        serverRepository,
        subscriptionDao,
        subscriptionAppRoutingRepository,
        subscriptionRoutingRepository,
        routingChangeNotifier,
    )
}
