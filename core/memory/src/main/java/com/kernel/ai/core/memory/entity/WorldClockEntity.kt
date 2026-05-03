package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "world_clocks",
    indices = [Index(value = ["zone_id"], unique = true)],
)
data class WorldClockEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
