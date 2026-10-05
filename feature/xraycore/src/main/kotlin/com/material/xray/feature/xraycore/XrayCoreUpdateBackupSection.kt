package com.material.xray.feature.xraycore

import com.material.xray.core.data.repository.BackupSection
import com.material.xray.core.xraycore.XrayCoreUpdateAction
import com.material.xray.core.xraycore.XrayCoreUpdateInterval
import com.material.xray.core.xraycore.XrayCoreUpdateSettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.koin.core.annotation.Singleton

/** Backs up the user's update choices; the notified tag and auto-installed core describe this device only. */
@Singleton(binds = [BackupSection::class])
class XrayCoreUpdateBackupSection(
    private val settingsStore: XrayCoreUpdateSettingsStore,
    private val scheduler: XrayCoreUpdateScheduler,
) : BackupSection {
    override val key = "xray_core_updates"

    override fun export(): JsonElement {
        val settings = settingsStore.settings.value
        return json.encodeToJsonElement(Backup.serializer(), Backup(settings.periodicChecks, settings.action, settings.interval.hours))
    }

    override suspend fun restore(value: JsonElement?) {
        val backup = value?.let { json.decodeFromJsonElement(Backup.serializer(), it) } ?: Backup()
        val updated = settingsStore.update {
            it.copy(periodicChecks = backup.periodicChecks, action = backup.action, intervalHours = backup.intervalHours)
        }
        scheduler.setEnabled(updated.periodicChecks, updated.interval)
    }

    @Serializable
    private data class Backup(
        val periodicChecks: Boolean = false,
        val action: XrayCoreUpdateAction = XrayCoreUpdateAction.Notify,
        val intervalHours: Int = XrayCoreUpdateInterval.default.hours,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
