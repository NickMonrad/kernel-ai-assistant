package com.kernel.ai.core.memory.clock

/**
 * Configurable clock alert lifecycle preferences — durations and auto-snooze policy.
 * Default values match the hardcoded constants from #1277.
 */
data class ClockAlertConfig(
    val timerAutoStopDurationMs: Long = 60_000L,
    val alarmRingDurationMs: Long = 60_000L,
    val snoozeDurationMs: Long = 600_000L,
    val maxAutoSnoozes: Int = 1,
)
