package com.kernel.ai.core.memory.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "important_dates",
    indices = [Index(value = ["normalized_label"], unique = true)],
)
data class ImportantDateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    @ColumnInfo(name = "normalized_label") val normalizedLabel: String,
    val month: Int,
    val day: Int,
    val year: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "notification_enabled") val notificationEnabled: Boolean = true,
    /** Per-event reminder hour (0–23). Null = use the global default from [ImportantDateNotificationPreferences]. */
    @ColumnInfo(name = "notification_hour") val notificationHour: Int? = null,
    /** Per-event reminder minute (0–59). Null = use the global default from [ImportantDateNotificationPreferences]. */
    @ColumnInfo(name = "notification_minute") val notificationMinute: Int? = null,
)
