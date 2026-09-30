package com.material.xray.data.parser

import android.content.Context
import android.provider.Settings
import java.util.UUID
import org.koin.core.annotation.Factory

interface SubscriptionDeviceIdentity {
    fun appVersion(): String

    fun hardwareId(): String
}

@Factory
internal class AndroidSubscriptionDeviceIdentity(
    private val context: Context,
) : SubscriptionDeviceIdentity {
    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"

    override fun hardwareId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim()
        return synchronized(this) {
            resolveSubscriptionHardwareId(
                androidId = androidId,
                readStored = { preferences.getString(KEY_FALLBACK_ID, null) },
                store = { preferences.edit().putString(KEY_FALLBACK_ID, it).commit() },
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "subscription_identity"
        const val KEY_FALLBACK_ID = "fallback_hardware_id"
    }
}

internal fun resolveSubscriptionHardwareId(
    androidId: String?,
    readStored: () -> String?,
    store: (String) -> Boolean,
    generate: () -> String = { UUID.randomUUID().toString() },
): String {
    val normalizedId = androidId?.trim()
    if (!normalizedId.isNullOrBlank() && !normalizedId.equals("9774d56d682e549c", ignoreCase = true)) {
        return normalizedId
    }
    readStored()?.takeIf { it.isNotBlank() }?.let { return it }
    return generate().also { generated ->
        if (!store(generated)) throw java.io.IOException("Unable to persist subscription hardware ID")
    }
}
