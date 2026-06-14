package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_shortcuts")
data class RecentShortcutEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
)
