package com.material.xray.di

import android.content.Context
import android.os.Build
import com.material.xray.R
import com.material.xray.core.network.addBundledCaFallback
import com.material.xray.core.root.RootShell
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.data.repository.SubscriptionRoutingRepository
import com.material.xray.service.RoutingChangeManager
import com.material.xray.telemetry.TelemetryClient
import com.material.xray.telemetry.TelemetryReporter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module(includes = [DatabaseModule::class])
@Configuration
@ComponentScan("com.material.xray")
class AppModule {

    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    @Singleton
    fun rootShell(): RootShell = RootShell()

    // The primary constructors of these two take test seams, so the graph uses the secondary ones.
    @Singleton
    fun telemetryReporter(client: TelemetryClient): TelemetryReporter = TelemetryReporter(client)

    @Singleton
    fun providerRoutingCoordinator(
        settingsRepository: SettingsRepository,
        serverRepository: ServerRepository,
        subscriptionDao: SubscriptionDao,
        subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
        subscriptionRoutingRepository: SubscriptionRoutingRepository,
        routingChangeManager: RoutingChangeManager,
    ): ProviderRoutingCoordinator = ProviderRoutingCoordinator(
        settingsRepository,
        serverRepository,
        subscriptionDao,
        subscriptionAppRoutingRepository,
        subscriptionRoutingRepository,
        routingChangeManager,
    )
}
