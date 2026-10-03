package com.material.xray.di

import androidx.work.ListenableWorker
import com.material.xray.MaterialXrayApp
import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.service.AppUpdateWorker
import com.material.xray.service.GeoDataUpdateWorker
import com.material.xray.service.SubscriptionUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
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
}
