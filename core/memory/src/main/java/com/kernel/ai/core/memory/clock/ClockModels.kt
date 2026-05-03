package com.kernel.ai.core.memory.clock

enum class ClockEventType {
    ALARM,
    TIMER,
    PRE_ALARM,
}

data class ClockAlarm(
    val id: String,
    val triggerAtMillis: Long,
    val label: String?,
    val createdAtMillis: Long,
    val enabled: Boolean,
)

data class ClockTimer(
    val id: String,
    val triggerAtMillis: Long,
    val label: String?,
    val createdAtMillis: Long,
    val durationMs: Long,
    val startedAtMillis: Long,
)

data class ClockScheduledEvent(
    val eventId: String,
    val ownerId: String,
    val type: ClockEventType,
    val triggerAtMillis: Long,
    val label: String?,
    val durationMs: Long? = null,
    val startedAtMillis: Long? = null,
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
