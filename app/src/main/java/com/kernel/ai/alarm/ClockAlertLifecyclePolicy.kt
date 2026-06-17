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
 * Decides the lifecycle action for an alert type given the number of
 * unattended auto-snoozes already taken for this occurrence and the
 * max auto-snooze policy.
 *
 * - **Timer:** always auto-stops after the ringing duration.
 * - **Alarm:** if [autoSnoozeCount] < [maxAutoSnoozes] → auto-snooze;
 *   if [autoSnoozeCount] >= [maxAutoSnoozes] → auto-stop.
 * - **Pre-alarm:** no auto-timeout (it's a notification only).
 *
 * @param autoSnoozeCount number of automatic snoozes already taken for this occurrence
 *   (0 = first ring, 1 = first snooze re-trigger, etc.).
 * @param maxAutoSnoozes maximum unattended auto-snoozes allowed before auto-stop.
 *   Default 1 preserves the #1277 behaviour.
 */
internal fun resolveAlertLifecycleAction(
    type: ClockEventType,
    autoSnoozeCount: Int = 0,
    maxAutoSnoozes: Int = 1,
): ClockAlertLifecycleAction? = when (type) {
    ClockEventType.TIMER -> ClockAlertLifecycleAction.AUTO_STOP
    ClockEventType.ALARM -> {
        if (autoSnoozeCount < maxAutoSnoozes) ClockAlertLifecycleAction.AUTO_SNOOZE
        else ClockAlertLifecycleAction.AUTO_STOP
    }
    ClockEventType.PRE_ALARM -> null
}

/**
 * Duration the ringtone/vibration plays before the lifecycle action fires.
 *
 * @param timerDurationMs duration for auto-stop actions (timers and snooze re-triggers).
 *   Default: [TIMER_AUTO_STOP_DURATION_MS].
 * @param alarmDurationMs duration for auto-snooze actions (first alarm ring).
 *   Default: [ALARM_AUTO_SNOOZE_DURATION_MS].
 */
internal fun lifecycleTimeoutDurationMs(
    type: ClockEventType,
    autoSnoozeCount: Int = 0,
    maxAutoSnoozes: Int = 1,
    timerDurationMs: Long = TIMER_AUTO_STOP_DURATION_MS,
    alarmDurationMs: Long = ALARM_AUTO_SNOOZE_DURATION_MS,
): Long = when (resolveAlertLifecycleAction(type, autoSnoozeCount, maxAutoSnoozes)) {
    ClockAlertLifecycleAction.AUTO_STOP -> timerDurationMs
    ClockAlertLifecycleAction.AUTO_SNOOZE -> alarmDurationMs
    null -> 0L
}

/** How long a timer rings unattended before auto-stopping. */
internal const val TIMER_AUTO_STOP_DURATION_MS = 60_000L

/** How long an alarm rings unattended before auto-snoozing. */
internal const val ALARM_AUTO_SNOOZE_DURATION_MS = 60_000L
