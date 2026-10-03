package com.material.xray.core.android.xray

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import com.material.xray.core.xray.PlatformDns
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.core.annotation.Singleton

/**
 * [PlatformDns] on `DnsResolver` (Android 10+), which queries netd like `Dns.SYSTEM` but can be
 * cancelled. Below Android 10 it has no resolver, so lookups take the blocking path.
 */
@Singleton(binds = [PlatformDns::class])
class AndroidPlatformDns(private val context: Context) : PlatformDns {
    private val directExecutor = Executor { it.run() }

    override fun activeNetworkHandle(): Long = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle ?: 0

    override suspend fun query(host: String): List<String>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        resolveWithAndroidDns(host)
    } else {
        null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun resolveWithAndroidDns(host: String): List<String> = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }

        dnsResolver().query(
            null,
            host,
            DnsResolver.FLAG_EMPTY,
            directExecutor,
            cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (continuation.isActive) {
                        continuation.resume(answer.mapNotNull { it.hostAddress })
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) {
                        continuation.resume(emptyList())
                    }
                }
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun dnsResolver(): DnsResolver = if (Build.VERSION.SDK_INT >= 37) {
        DnsResolver(context, Looper.getMainLooper())
    } else {
        @Suppress("DEPRECATION")
        DnsResolver.getInstance()
    }
}
