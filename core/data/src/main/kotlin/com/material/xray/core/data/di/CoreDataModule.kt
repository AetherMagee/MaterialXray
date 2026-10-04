package com.material.xray.core.data.di

import com.material.xray.core.common.connection.RoutingChangeNotifier
import com.material.xray.core.data.repository.ProviderRoutingCoordinator
import com.material.xray.core.data.repository.ServerRepository
import com.material.xray.core.data.repository.SettingsRepository
import com.material.xray.core.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.core.data.repository.SubscriptionRoutingRepository
import com.material.xray.core.database.dao.SubscriptionDao
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@ComponentScan("com.material.xray.core.data")
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
