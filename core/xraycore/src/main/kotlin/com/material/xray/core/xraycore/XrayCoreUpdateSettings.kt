package com.material.xray.core.xraycore

import com.material.xray.core.model.AppUpdateInterval
import com.material.xray.core.xray.XrayPaths
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Singleton

/** What a periodic check does when it finds a newer release. */
@Serializable
enum class XrayCoreUpdateAction { Notify, Install }

@Serializable
data class XrayCoreUpdateSettings(
    val periodicChecks: Boolean = false,
    val action: XrayCoreUpdateAction = XrayCoreUpdateAction.Notify,
    val intervalHours: Int = AppUpdateInterval.OneWeek.hours,
    /** The last release a notification was shown for, so each one is announced once. */
    val notifiedTag: String? = null,
    /** The core the last automatic update installed, removed once a newer one replaces it. */
    val autoInstalledId: String? = null,
) {
    val interval: AppUpdateInterval
        get() = AppUpdateInterval.entries.find { it.hours == intervalHours } ?: AppUpdateInterval.OneWeek
}

/**
 * Keeps [XrayCoreUpdateSettings] in a file of their own rather than in the app's settings, so the
 * core updater stays removable as a whole.
 */
@Singleton
class XrayCoreUpdateSettingsStore(paths: XrayPaths) {
    private val file = File(paths.filesDir, FILE_NAME)
    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<XrayCoreUpdateSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (XrayCoreUpdateSettings) -> XrayCoreUpdateSettings): XrayCoreUpdateSettings {
        val updated = _settings.updateAndGet(transform)
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeText(json.encodeToString(XrayCoreUpdateSettings.serializer(), updated))
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Could not save the Xray core update settings")
        }
        return updated
    }

    private fun read(): XrayCoreUpdateSettings = runCatching {
        json.decodeFromString(XrayCoreUpdateSettings.serializer(), file.readText())
    }.getOrDefault(XrayCoreUpdateSettings())

    private companion object {
        const val FILE_NAME = "xray-core-updates.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
