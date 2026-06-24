package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.clock.SchedulingResult

/**
 * Result of saving an alarm from the UI layer.
 * [STORED] is the success case; all other values indicate a specific blocker.
 */
sealed class AlarmSaveResult {
    /** Alarm was successfully stored and scheduled. */
    data object STORED : AlarmSaveResult()

    /** Exact alarm scheduling is blocked on this device. */
    data object EXACT_ALARM_BLOCKED : AlarmSaveResult()

    /** Notifications are disabled, so the alarm alert cannot be shown. */
    data object NOTIFICATION_BLOCKED : AlarmSaveResult()

    /** Full-screen intent (for alarm alerts) is unavailable. */
    data object FULL_SCREEN_INTENT_UNAVAILABLE : AlarmSaveResult()

    /** Boot-restore of scheduled events is limited. */
    data object BOOT_RESTORE_LIMITED : AlarmSaveResult()

    /** An internal scheduling error occurred. */
    data class FAILED(val message: String? = null) : AlarmSaveResult()
}

/** Convert a [SchedulingResult] from the repository layer into an [AlarmSaveResult]. */
internal fun SchedulingResult<*>.toAlarmSaveResult(): AlarmSaveResult = when (this) {
    is SchedulingResult.Success -> AlarmSaveResult.STORED
    is SchedulingResult.ExactAlarmBlocked -> AlarmSaveResult.EXACT_ALARM_BLOCKED
    is SchedulingResult.NotificationBlocked -> AlarmSaveResult.NOTIFICATION_BLOCKED
    is SchedulingResult.FullScreenIntentUnavailable -> AlarmSaveResult.FULL_SCREEN_INTENT_UNAVAILABLE
    is SchedulingResult.BootRestoreLimited -> AlarmSaveResult.BOOT_RESTORE_LIMITED
    is SchedulingResult.SchedulingFailed -> AlarmSaveResult.FAILED(this.message)
}
