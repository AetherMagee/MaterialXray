package com.material.xray.di

import android.content.Context
import android.os.Build
import com.material.xray.R
import com.material.xray.core.android.di.CoreAndroidModule
import com.material.xray.core.common.connection.RoutingChangeNotifier
import com.material.xray.core.common.di.ApplicationScope
import com.material.xray.core.common.di.CoreCommonModule
import com.material.xray.core.connection.di.CoreConnectionModule
import com.material.xray.core.data.di.CoreDataModule
import com.material.xray.core.database.di.CoreDatabaseModule
import com.material.xray.core.model.di.CoreModelModule
import com.material.xray.core.navigation.di.CoreNavigationModule
import com.material.xray.core.network.addBundledCaFallback
import com.material.xray.core.network.di.CoreNetworkModule
import com.material.xray.core.root.di.CoreRootModule
import com.material.xray.core.runtime.di.CoreRuntimeModule
import com.material.xray.core.telemetry.di.CoreTelemetryModule
import com.material.xray.core.ui.di.CoreUiModule
import com.material.xray.core.xray.di.CoreXrayModule
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.data.repository.SubscriptionRoutingRepository
import com.material.xray.feature.configviewer.di.FeatureConfigViewerModule
import com.material.xray.feature.home.di.FeatureHomeModule
import com.material.xray.feature.logs.di.FeatureLogsModule
import com.material.xray.feature.routing.di.FeatureRoutingModule
import com.material.xray.feature.settings.di.FeatureSettingsModule
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module(
    includes = [
        DatabaseModule::class,
        CoreModelModule::class,
        CoreCommonModule::class,
        CoreXrayModule::class,
        CoreRootModule::class,
        CoreNetworkModule::class,
        CoreConnectionModule::class,
        CoreAndroidModule::class,
        CoreDatabaseModule::class,
        CoreDataModule::class,
        CoreTelemetryModule::class,
        CoreRuntimeModule::class,
        CoreUiModule::class,
        CoreNavigationModule::class,
        FeatureHomeModule::class,
        FeatureRoutingModule::class,
        FeatureLogsModule::class,
        FeatureSettingsModule::class,
        FeatureConfigViewerModule::class,
    ],
)
@Configuration
@ComponentScan("com.material.xray")
class AppModule {

    @Singleton
    @ApplicationScope
    fun applicationScope(
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    @Singleton
    fun okHttpClient(context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        // Android 7 lacks root CAs that current certificate chains depend on.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.N_MR1) {
            context.resources.openRawResource(R.raw.mozilla_ca_bundle).use(builder::addBundledCaFallback)
        }
        return builder.build()
    }

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
