package com.material.xray.di

import androidx.work.ListenableWorker
import com.material.xray.MaterialXrayApp
import com.material.xray.core.common.connection.AppUpdateScheduling
import com.material.xray.core.common.connection.ConnectionShutdown
import com.material.xray.core.common.connection.RoutingChangeNotifier
import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.common.log.LogEcho
import com.material.xray.core.network.CoreTrafficRoutingSetting
import com.material.xray.core.xray.GeoDataUrlSettings
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.service.AppUpdateScheduler
import com.material.xray.service.AppUpdateWorker
import com.material.xray.service.ConnectionShutdownManager
import com.material.xray.service.GeoDataUpdateWorker
import com.material.xray.service.LogcatEcho
import com.material.xray.service.RoutingChangeManager
import com.material.xray.service.SubscriptionUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.qualifier.named
import org.koin.plugin.module.dsl.koinApplication

/**
 * Boots the graph the Koin compiler plugin assembles for [MaterialXrayApp]. The plugin already
 * rejects missing bindings at compile time; this covers what only resolves at runtime: the
 * qualifier lookups done by hand, singleton sharing, and the keys the WorkManager factory uses.
 * Only definitions that do not need an Android [android.content.Context] are resolved here.
 */
class KoinGraphTest {

    private val koin = koinApplication<MaterialXrayApp>().koin

    @After
    fun tearDown() {
        koin.getOrNull<CoroutineScope>(named<ApplicationScope>())?.cancel()
        koin.close()
    }

    @Test
    fun `application scope resolves through its qualifier as a singleton`() {
        val scope = koin.get<CoroutineScope>(named<ApplicationScope>())

        assertSame(scope, koin.get<CoroutineScope>(named<ApplicationScope>()))
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun `workers are registered under the names the Koin worker factory resolves`() {
        val workerQualifiers = koin.instanceRegistry.instances.values
            .map { it.beanDefinition }
            .filter { ListenableWorker::class in it.secondaryTypes }
            .map { it.qualifier }
            .toSet()

        listOf(AppUpdateWorker::class, GeoDataUpdateWorker::class, SubscriptionUpdateWorker::class).forEach { worker ->
            assertTrue(
                "${worker.simpleName} is not registered for KoinWorkerFactory",
                named(worker.java.name) in workerQualifiers,
            )
        }
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun `interfaces the lower layers declare resolve to the existing singletons`() {
        val bindings = mapOf(
            LogEcho::class to LogcatEcho::class,
            ConnectionShutdown::class to ConnectionShutdownManager::class,
            RoutingChangeNotifier::class to RoutingChangeManager::class,
            AppUpdateScheduling::class to AppUpdateScheduler::class,
            CoreTrafficRoutingSetting::class to SettingsRepository::class,
            GeoDataUrlSettings::class to SettingsRepository::class,
        )
        val definitions = koin.instanceRegistry.instances.values.map { it.beanDefinition }

        bindings.forEach { (contract, implementation) ->
            val providers = definitions.filter { contract in it.secondaryTypes }.map { it.primaryType }.toSet()
            assertEquals("${contract.simpleName} providers", setOf(implementation), providers)
        }
    }
}
