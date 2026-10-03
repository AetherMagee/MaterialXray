package com.material.xray.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.material.xray.core.common.platform.AppBuildInfo
import com.material.xray.model.AppUpdate
import com.material.xray.model.AppUpdateCheckStatus
import com.material.xray.model.isReleaseNewer
import com.material.xray.model.isUpdateCheckDue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Singleton

private val Context.appUpdateDataStore by preferencesDataStore(name = "app_update")

@Singleton
class AppUpdateRepository(
    private val context: Context,
    private val releaseFetcher: GitHubReleaseFetcher,
    appBuildInfo: AppBuildInfo,
) {
    private val store get() = context.appUpdateDataStore
    private val currentVersionName = appBuildInfo.versionName
    val availableUpdate: Flow<AppUpdate?> = store.data.map { preferences ->
        preferences[AVAILABLE_RELEASE_TAG]
            ?.takeIf { tag -> isReleaseNewer(tag, currentVersionName) }
            ?.let { tag -> appUpdate(tag, preferences[AVAILABLE_APK_DOWNLOAD_URL]) }
    }

    suspend fun checkForUpdate(onStatus: suspend (AppUpdateCheckStatus) -> Unit = {}): AppUpdate? {
        val release = releaseFetcher.fetchLatestRelease(currentVersionName, onStatus)
        val update = release.tagName
            .takeIf { tag -> isReleaseNewer(tag, currentVersionName) }
            ?.let { tag -> appUpdate(tag, release.apkDownloadUrl.takeIf(String::isNotEmpty)) }

        store.edit { preferences ->
            if (update == null) {
                preferences.remove(AVAILABLE_RELEASE_TAG)
                preferences.remove(AVAILABLE_APK_DOWNLOAD_URL)
            } else {
                preferences[AVAILABLE_RELEASE_TAG] = update.tagName
                update.apkDownloadUrl?.let { preferences[AVAILABLE_APK_DOWNLOAD_URL] = it }
                    ?: preferences.remove(AVAILABLE_APK_DOWNLOAD_URL)
            }
        }
        return update
    }

    suspend fun wasNotified(tagName: String): Boolean = store.data.first()[LAST_NOTIFIED_RELEASE_TAG] == tagName

    suspend fun markNotified(tagName: String) {
        store.edit { preferences -> preferences[LAST_NOTIFIED_RELEASE_TAG] = tagName }
    }

    suspend fun clearAvailableUpdate() {
        store.edit { preferences ->
            preferences.remove(AVAILABLE_RELEASE_TAG)
            preferences.remove(AVAILABLE_APK_DOWNLOAD_URL)
        }
    }

    suspend fun claimUpdateCheck(nowMillis: Long, minimumIntervalMillis: Long): Boolean {
        var claimed = false
        store.edit { preferences ->
            if (isUpdateCheckDue(preferences[LAST_CHECK_AT_MILLIS] ?: 0L, nowMillis, minimumIntervalMillis)) {
                preferences[LAST_CHECK_AT_MILLIS] = nowMillis
                claimed = true
            }
        }
        return claimed
    }

    private fun appUpdate(tagName: String, apkDownloadUrl: String?) = AppUpdate(
        tagName = tagName,
        apkDownloadUrl = apkDownloadUrl,
    )

    private companion object {
        val AVAILABLE_RELEASE_TAG = stringPreferencesKey("available_release_tag")
        val AVAILABLE_APK_DOWNLOAD_URL = stringPreferencesKey("available_apk_download_url")
        val LAST_NOTIFIED_RELEASE_TAG = stringPreferencesKey("last_notified_release_tag")
        val LAST_CHECK_AT_MILLIS = longPreferencesKey("last_check_at_millis")
    }
}
