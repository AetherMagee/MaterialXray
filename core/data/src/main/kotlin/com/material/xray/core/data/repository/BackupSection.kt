package com.material.xray.core.data.repository

import kotlinx.serialization.json.JsonElement

/**
 * State an optional module adds to backups, so the module can be dropped without touching
 * [BackupManager]. Bind implementations with `binds = [BackupSection::class]`.
 */
interface BackupSection {
    /** Unique name the state is stored under in the backup. */
    val key: String

    fun export(): JsonElement

    /** Replaces the module's state; null means the backup has none, so defaults apply. */
    suspend fun restore(value: JsonElement?)
}
