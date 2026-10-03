package com.material.xray.di

import androidx.datastore.core.DataStore
import androidx.work.ListenableWorker
import com.material.xray.MaterialXrayApp
import com.material.xray.core.android.data.AndroidSubscriptionDeviceIdentity
import com.material.xray.core.android.data.ContentResolverBackupStorage
import com.material.xray.core.android.network.AndroidNetworkLinkProbe
import com.material.xray.core.android.platform.AndroidAppBuildInfo
import com.material.xray.core.android.platform.AndroidPlatformInfo
import com.material.xray.core.android.platform.ElapsedRealtimeClock
import com.material.xray.core.android.platform.LogcatAppLogger
import com.material.xray.core.android.xray.AndroidLocalSockets
import com.material.xray.core.android.xray.AndroidPlatformDns
import com.material.xray.core.android.xray.AndroidVpnTransportProbe
import com.material.xray.core.android.xray.AndroidXrayPaths
import com.material.xray.core.app.AppInventory
import com.material.xray.core.app.AppInventorySource
import com.material.xray.core.common.connection.AppUpdateScheduling
import com.material.xray.core.common.connection.ConnectionShutdown
import com.material.xray.core.common.connection.RoutingChangeNotifier
import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.common.log.AppLogger
import com.material.xray.core.common.log.LogEcho
import com.material.xray.core.common.platform.AppBuildInfo
import com.material.xray.core.common.platform.MonotonicClock
import com.material.xray.core.common.platform.PlatformInfo
import com.material.xray.core.common.telemetry.DiagnosticsConsentMirroring
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.network.CoreTrafficRoutingSetting
import com.material.xray.core.network.NetworkLinkProbe
import com.material.xray.core.xray.GeoDataUrlSettings
import com.material.xray.core.xray.LocalSockets
import com.material.xray.core.xray.PlatformDns
import com.material.xray.core.xray.VpnTransportProbe
import com.material.xray.core.xray.XrayPaths
import com.material.xray.data.parser.SubscriptionDeviceIdentity
import com.material.xray.data.platform.BackupStorage
import com.material.xray.data.platform.LauncherIconSwitcher
import com.material.xray.data.repository.AppUpdateDataStore
import com.material.xray.data.repository.SettingsDataStore
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.service.AppUpdateScheduler
import com.material.xray.service.AppUpdateWorker
import com.material.xray.service.ConnectionShutdownManager
import com.material.xray.service.GeoDataUpdateWorker
import com.material.xray.service.LogcatEcho
import com.material.xray.service.RoutingChangeManager
import com.material.xray.service.SubscriptionUpdateWorker
import com.material.xray.telemetry.DiagnosticsConsentMirror
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
            DiagnosticsConsentMirroring::class to DiagnosticsConsentMirror::class,
            AppLogger::class to LogcatAppLogger::class,
            PlatformInfo::class to AndroidPlatformInfo::class,
            MonotonicClock::class to ElapsedRealtimeClock::class,
            XrayPaths::class to AndroidXrayPaths::class,
            LocalSockets::class to AndroidLocalSockets::class,
            PlatformDns::class to AndroidPlatformDns::class,
            VpnTransportProbe::class to AndroidVpnTransportProbe::class,
            NetworkLinkProbe::class to AndroidNetworkLinkProbe::class,
            AppBuildInfo::class to AndroidAppBuildInfo::class,
            SubscriptionDeviceIdentity::class to AndroidSubscriptionDeviceIdentity::class,
            BackupStorage::class to ContentResolverBackupStorage::class,
            LauncherIconSwitcher::class to LauncherIconManager::class,
            AppInventorySource::class to AppInventory::class,
        )
        val definitions = koin.instanceRegistry.instances.values.map { it.beanDefinition }

        bindings.forEach { (contract, implementation) ->
            val providers = definitions.filter { contract in it.secondaryTypes }.map { it.primaryType }.toSet()
            assertEquals("${contract.simpleName} providers", setOf(implementation), providers)
        }
    }

    // Opening a store needs an Android Context, so only the definitions are checked here.
    @OptIn(KoinInternalApi::class)
    @Test
    fun `each preference store has one definition under its own qualifier`() {
        val qualifiers = koin.instanceRegistry.instances.values
            .map { it.beanDefinition }
            .distinct()
            .filter { it.primaryType == DataStore::class }
            .map { it.qualifier }

        assertEquals(listOf(named<AppUpdateDataStore>(), named<SettingsDataStore>()).sortedBy { it.value }, qualifiers.sortedBy { it?.value })
    }

    // Building the database needs an Android Context, so only the definitions are checked here.
    @OptIn(KoinInternalApi::class)
    @Test
    fun `the database and its DAOs each have exactly one definition`() {
        val primaryTypes = koin.instanceRegistry.instances.values.map { it.beanDefinition }.distinct().map { it.primaryType }

        listOf(
            com.material.xray.data.db.AppDatabase::class,
            com.material.xray.data.db.dao.ServerDao::class,
            com.material.xray.data.db.dao.SubscriptionDao::class,
            com.material.xray.data.db.dao.AppBypassDao::class,
        ).forEach { type ->
            assertEquals("${type.simpleName} definitions", 1, primaryTypes.count { it == type })
        }
    }
}
