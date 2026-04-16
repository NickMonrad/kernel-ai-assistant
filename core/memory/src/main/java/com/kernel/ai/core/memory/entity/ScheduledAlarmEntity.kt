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
)
