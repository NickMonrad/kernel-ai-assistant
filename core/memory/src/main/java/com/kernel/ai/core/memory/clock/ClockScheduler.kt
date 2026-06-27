package com.kernel.ai.core.memory.clock

interface ClockScheduler {
    fun getPlatformState(): ClockPlatformState

    /** Schedule an event. Returns [SchedulingResult.Success] on success,
     *  or a specific [SchedulingResult] subtype describing the blocker. */
    fun schedule(event: ClockScheduledEvent): SchedulingResult<Unit>

    fun cancel(event: ClockScheduledEvent)
}
