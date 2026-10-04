package com.material.xray.core.android.data

import android.content.Context
import android.provider.Settings
import com.material.xray.core.common.platform.AppBuildInfo
import com.material.xray.core.data.parser.SubscriptionDeviceIdentity
import com.material.xray.core.data.parser.resolveSubscriptionHardwareId
import org.koin.core.annotation.Factory

/**
 * Sends `ANDROID_ID` as the hardware id, or a random token kept in the `subscription_identity`
 * shared preferences when the device has none.
 */
@Factory(binds = [SubscriptionDeviceIdentity::class])
class AndroidSubscriptionDeviceIdentity(
    private val context: Context,
    private val appBuildInfo: AppBuildInfo,
) : SubscriptionDeviceIdentity {
    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override fun appVersion(): String = appBuildInfo.versionName.ifBlank { "dev" }

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
