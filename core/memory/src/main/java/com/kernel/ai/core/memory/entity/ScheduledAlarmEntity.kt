package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey val id: String,
    val triggerAtMillis: Long,
    val label: String?,
    val createdAt: Long,
    val fired: Boolean = false,
    val enabled: Boolean = true,
    @androidx.room.ColumnInfo(name = "entry_type") val entryType: String = "ALARM",
    @androidx.room.ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @androidx.room.ColumnInfo(name = "started_at_ms") val startedAtMs: Long? = null,
)
