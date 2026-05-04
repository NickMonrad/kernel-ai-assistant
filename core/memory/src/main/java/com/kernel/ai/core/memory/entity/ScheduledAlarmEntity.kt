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
    @ColumnInfo(name = "alarm_hour") val alarmHour: Int? = null,
    @ColumnInfo(name = "alarm_minute") val alarmMinute: Int? = null,
    @ColumnInfo(name = "repeat_type") val repeatType: String? = null,
    @ColumnInfo(name = "repeat_days_mask") val repeatDaysMask: Int? = null,
    @ColumnInfo(name = "one_off_date_epoch_day") val oneOffDateEpochDay: Long? = null,
    @ColumnInfo(name = "time_zone_id") val timeZoneId: String? = null,
    @ColumnInfo(name = "snoozed_until_ms") val snoozedUntilMs: Long? = null,
    @ColumnInfo(name = "completed_at_ms") val completedAtMs: Long? = null,
)