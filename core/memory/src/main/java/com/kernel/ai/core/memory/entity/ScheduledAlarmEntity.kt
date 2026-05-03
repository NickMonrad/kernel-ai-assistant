package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_id") val ownerId: String? = null,
    val triggerAtMillis: Long,
    val label: String?,
    val createdAt: Long,
    val fired: Boolean = false,
    val enabled: Boolean = true,
    @ColumnInfo(name = "entry_type") val entryType: String = "ALARM",
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long? = null,
    @ColumnInfo(name = "completed_at_ms") val completedAtMs: Long? = null,
)