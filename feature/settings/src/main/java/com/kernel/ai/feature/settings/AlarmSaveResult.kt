package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.clock.SchedulingResult
import com.kernel.ai.core.memory.clock.SchedulingWarning

/**
 * Result of saving an alarm from the UI layer.
 * [STORED] is the success case; all other values indicate a specific blocker.
 * [warnings] carries non-blocking platform limitations (e.g. full-screen unavailable).
 */
sealed class AlarmSaveResult {
    /** Alarm was successfully stored and scheduled. [warnings] lists any degraded-delivery conditions. */
    data class STORED(val warnings: List<SchedulingWarning> = emptyList()) : AlarmSaveResult()

    /** Exact alarm scheduling is blocked on this device. */
    data object EXACT_ALARM_BLOCKED : AlarmSaveResult()

    /** Notifications are disabled, so the alarm alert cannot be shown. */
    data object NOTIFICATION_BLOCKED : AlarmSaveResult()

    /** An internal scheduling error occurred. */
    data class FAILED(val message: String? = null) : AlarmSaveResult()
}

/** Convert a [SchedulingResult] from the repository layer into an [AlarmSaveResult]. */
internal fun SchedulingResult<*>.toAlarmSaveResult(): AlarmSaveResult = when (this) {
    is SchedulingResult.Success -> AlarmSaveResult.STORED(warnings = this.warnings)
    is SchedulingResult.ExactAlarmBlocked -> AlarmSaveResult.EXACT_ALARM_BLOCKED
    is SchedulingResult.NotificationBlocked -> AlarmSaveResult.NOTIFICATION_BLOCKED
    is SchedulingResult.SchedulingFailed -> AlarmSaveResult.FAILED(this.message)
}

/** Build a user-facing warning message from scheduling warnings, or null if there are none. */
internal fun List<SchedulingWarning>.toWarningMessage(isTimer: Boolean): String? {
    if (isEmpty()) return null
    val messages = mutableListOf<String>()
    
    val savedPrefix = if (isTimer) "Timer set." else "Alarm saved."
    
    if (contains(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE)) {
        messages.add("$savedPrefix It may appear as a notification instead of opening full-screen.")
    }
    
    if (contains(SchedulingWarning.BOOT_RESTORE_LIMITED)) {
        if (!contains(SchedulingWarning.FULL_SCREEN_INTENT_UNAVAILABLE)) {
            messages.add(savedPrefix)
        }
        messages.add("Scheduled events may need to be recreated after a device restart.")
    }
    
    return messages.joinToString(" ")
}
