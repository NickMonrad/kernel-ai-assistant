package com.kernel.ai.core.memory.clock

import java.time.DayOfWeek

enum class ClockEventType {
    ALARM,
    TIMER,
    PRE_ALARM,
}

enum class StopwatchStatus { IDLE, RUNNING, PAUSED }

sealed interface AlarmRepeatRule {
    data class OneOff(val dateEpochDay: Long) : AlarmRepeatRule
    data object Daily : AlarmRepeatRule
    data class SelectedWeekdays(val daysMask: Int) : AlarmRepeatRule {
        init {
            require(daysMask != 0) { "SelectedWeekdays requires at least one day" }
        }

        fun includes(dayOfWeek: DayOfWeek): Boolean =
            daysMask and (1 shl (dayOfWeek.value - 1)) != 0
    }
}

data class AlarmDraft(
    val label: String?,
    val hour: Int,
    val minute: Int,
    val repeatRule: AlarmRepeatRule,
    val timeZoneId: String,
)

data class ClockAlarm(
    val id: String,
    val label: String?,
    val createdAtMillis: Long,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val repeatRule: AlarmRepeatRule,
    val timeZoneId: String,
    val triggerAtMillis: Long,
 ) {
    val nextTriggerAtMillis: Long get() = triggerAtMillis
}

data class ClockTimer(
    val id: String,
    val triggerAtMillis: Long,
    val label: String?,
    val createdAtMillis: Long,
    val durationMs: Long,
    val startedAtMillis: Long,
    val completedAtMillis: Long? = null,
 )

data class StopwatchLap(
    val id: Long,
    val lapNumber: Int,
    val elapsedMs: Long,
    val splitMs: Long,
    val createdAtMillis: Long,
)

data class ClockStopwatch(
    val id: String,
    val status: StopwatchStatus,
    val accumulatedElapsedMs: Long,
    val runningSinceElapsedRealtimeMs: Long? = null,
    val runningSinceWallClockMs: Long? = null,
    val updatedAtMillis: Long,
    val laps: List<StopwatchLap> = emptyList(),
) {
    fun elapsedMs(
        nowElapsedRealtimeMs: Long,
        nowWallClockMillis: Long,
    ): Long = when (status) {
        StopwatchStatus.IDLE -> 0L
        StopwatchStatus.PAUSED -> accumulatedElapsedMs
        StopwatchStatus.RUNNING -> accumulatedElapsedMs + runningSegmentElapsedMs(
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            nowWallClockMillis = nowWallClockMillis,
        )
    }

    private fun runningSegmentElapsedMs(
        nowElapsedRealtimeMs: Long,
        nowWallClockMillis: Long,
    ): Long {
        val elapsedRealtimeAnchor = runningSinceElapsedRealtimeMs
        if (elapsedRealtimeAnchor != null && nowElapsedRealtimeMs >= elapsedRealtimeAnchor) {
            return nowElapsedRealtimeMs - elapsedRealtimeAnchor
        }
        val wallClockAnchor = runningSinceWallClockMs ?: return 0L
        return (nowWallClockMillis - wallClockAnchor).coerceAtLeast(0L)
    }
}

data class WorldClock(
    val id: String,
    val zoneId: String,
    val displayName: String,
    val sortOrder: Int,
    val createdAtMillis: Long,
 )

data class ClockScheduledEvent(
    val eventId: String,
    val ownerId: String,
    val type: ClockEventType,
    val triggerAtMillis: Long,
    val label: String?,
    val durationMs: Long? = null,
    val startedAtMillis: Long? = null,
    val occurrenceTriggerAtMillis: Long? = null,
 )

data class ClockPlatformState(
    val canScheduleExactAlarms: Boolean,
    val notificationsEnabled: Boolean,
    val canUseFullScreenIntent: Boolean,
 )

data class ClockRestoreReport(
    val restoredCount: Int,
    val expiredCount: Int,
    val disabledCount: Int,
    val blockedByExactAlarmCapability: Boolean,
 )