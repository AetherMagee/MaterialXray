package com.material.xray.di

import android.content.Context
import android.os.Build
import com.material.xray.R
import com.material.xray.core.network.AppHttpClient
import com.material.xray.core.network.TunnelAwareHttpClient
import com.material.xray.core.network.addBundledCaFallback
import com.material.xray.core.network.shouldUseBundledCaFallback
import com.material.xray.core.root.RootShell
import com.material.xray.data.parser.SubscriptionFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        if (shouldUseBundledCaFallback(Build.VERSION.SDK_INT)) {
            context.resources.openRawResource(R.raw.mozilla_ca_bundle).use(builder::addBundledCaFallback)
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRootShell(): RootShell = RootShell()

    @Provides
    @Singleton
    fun provideAppHttpClient(impl: TunnelAwareHttpClient): AppHttpClient = impl

    @Provides
    @Singleton
    fun provideSubscriptionFetcher(client: AppHttpClient): SubscriptionFetcher = SubscriptionFetcher(client)
}
