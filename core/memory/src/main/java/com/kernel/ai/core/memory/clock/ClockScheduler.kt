package com.kernel.ai.core.memory.clock

interface ClockScheduler {
    fun getPlatformState(): ClockPlatformState

    fun schedule(event: ClockScheduledEvent)

    fun cancel(event: ClockScheduledEvent)
}
