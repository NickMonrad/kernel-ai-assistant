package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.clock.SchedulingResult

/**
 * Result of saving an alarm from the UI layer.
 * [STORED] is the success case; all other values indicate a specific blocker.
 * Warnings (full-screen, boot-restore) are embedded in [SchedulingResult.Success.warnings]
 * and the event is genuinely saved — they are not separate [AlarmSaveResult] variants.
 */
sealed class AlarmSaveResult {
    /** Alarm was successfully stored and scheduled. */
    data object STORED : AlarmSaveResult()

    /** Exact alarm scheduling is blocked on this device. */
    data object EXACT_ALARM_BLOCKED : AlarmSaveResult()

    /** Notifications are disabled, so the alarm alert cannot be shown. */
    data object NOTIFICATION_BLOCKED : AlarmSaveResult()

    /** An internal scheduling error occurred. */
    data class FAILED(val message: String? = null) : AlarmSaveResult()
}

/** Convert a [SchedulingResult] from the repository layer into an [AlarmSaveResult]. */
internal fun SchedulingResult<*>.toAlarmSaveResult(): AlarmSaveResult = when (this) {
    is SchedulingResult.Success -> AlarmSaveResult.STORED
    is SchedulingResult.ExactAlarmBlocked -> AlarmSaveResult.EXACT_ALARM_BLOCKED
    is SchedulingResult.NotificationBlocked -> AlarmSaveResult.NOTIFICATION_BLOCKED
    is SchedulingResult.SchedulingFailed -> AlarmSaveResult.FAILED(this.message)
}
