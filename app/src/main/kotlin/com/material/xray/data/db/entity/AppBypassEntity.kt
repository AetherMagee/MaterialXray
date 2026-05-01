package com.material.xray.data.db.entity

import androidx.room.Entity

@Entity(tableName = "app_bypass", primaryKeys = ["profileId", "packageName"])
data class AppBypassEntity(
    val packageName: String,
    val profileId: Int = 0,
    val uid: Int,
    val excluded: Boolean = true,
    val serverId: Long? = null,
    val manual: Boolean = true,
    val routeMode: String? = null,
)
