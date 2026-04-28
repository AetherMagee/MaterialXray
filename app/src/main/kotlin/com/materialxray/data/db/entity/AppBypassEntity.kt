package com.materialxray.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_bypass")
data class AppBypassEntity(
    @PrimaryKey val packageName: String,
    val uid: Int,
    val excluded: Boolean = true,
    val serverId: Long? = null,
)
