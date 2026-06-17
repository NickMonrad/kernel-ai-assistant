package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType

/**
 * Describes what happens when an alert's ringing phase times out
 * without user interaction.
 */
internal enum class ClockAlertLifecycleAction {
    /** Stop playback, clean up, and mark the alert as completed. */
    AUTO_STOP,

    /** Snooze the alarm for a standard duration. Applies to alarms only. */
    AUTO_SNOOZE,
}

/**
 * Decides the lifecycle action for an alert type given whether this is a
 * snooze re-trigger.
 *
 * - **Timer:** always auto-stops after the ringing duration.
 * - **Alarm (first ring):** auto-snoozes after the ringing duration.
 * - **Alarm (snooze re-trigger):** auto-stops after the ringing duration
 *   (no second snooze for the same occurrence).
 * - **Pre-alarm:** no auto-timeout (it's a notification only).
 */
internal fun resolveAlertLifecycleAction(
    type: ClockEventType,
    isSnoozeRetrigger: Boolean,
): ClockAlertLifecycleAction? = when (type) {
    ClockEventType.TIMER -> ClockAlertLifecycleAction.AUTO_STOP
    ClockEventType.ALARM -> {
        if (isSnoozeRetrigger) ClockAlertLifecycleAction.AUTO_STOP
        else ClockAlertLifecycleAction.AUTO_SNOOZE
    }
    ClockEventType.PRE_ALARM -> null
}

/**
 * Duration the ringtone/vibration plays before the lifecycle action fires.
 *
 * - Timer: [TIMER_AUTO_STOP_DURATION_MS] (default 60 s)
 * - Alarm first ring: [ALARM_AUTO_SNOOZE_DURATION_MS] (default 60 s)
 * - Alarm snooze re-trigger: [TIMER_AUTO_STOP_DURATION_MS] (default 60 s)
 */
internal fun lifecycleTimeoutDurationMs(
    type: ClockEventType,
    isSnoozeRetrigger: Boolean,
): Long = when (resolveAlertLifecycleAction(type, isSnoozeRetrigger)) {
    ClockAlertLifecycleAction.AUTO_STOP -> TIMER_AUTO_STOP_DURATION_MS
    ClockAlertLifecycleAction.AUTO_SNOOZE -> ALARM_AUTO_SNOOZE_DURATION_MS
    null -> 0L
}

/** How long a timer rings unattended before auto-stopping. */
internal const val TIMER_AUTO_STOP_DURATION_MS = 60_000L

/** How long an alarm rings unattended before auto-snoozing. */
internal const val ALARM_AUTO_SNOOZE_DURATION_MS = 60_000L
