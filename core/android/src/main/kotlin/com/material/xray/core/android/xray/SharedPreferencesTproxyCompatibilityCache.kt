package com.material.xray.core.android.xray

import android.content.Context
import com.material.xray.core.xray.TproxyCompatibilityCache
import org.koin.core.annotation.Singleton

/** [TproxyCompatibilityCache] in a private preferences file. */
@Singleton(binds = [TproxyCompatibilityCache::class])
class SharedPreferencesTproxyCompatibilityCache(context: Context) : TproxyCompatibilityCache {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(buildFingerprint: String): String? {
        if (preferences.getString(FINGERPRINT_KEY, null) != buildFingerprint) return null
        return preferences.getString(RESULT_KEY, null)
    }

    override fun write(buildFingerprint: String, encoded: String) {
        preferences.edit()
            .putString(FINGERPRINT_KEY, buildFingerprint)
            .putString(RESULT_KEY, encoded)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "tproxy-compatibility"
        const val FINGERPRINT_KEY = "build-fingerprint"
        const val RESULT_KEY = "result"
    }
}
