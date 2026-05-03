package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.StopwatchStatus
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ClockStopwatchNotificationSyncerTest {
    private val transport = mockk<ClockStopwatchNotificationTransport>(relaxed = true)
    private val syncer = ClockStopwatchNotificationSyncer(transport)

    @Test
    fun `sync shows running stopwatch and cancels when reset to idle`() {
        syncer.sync(stopwatch(status = StopwatchStatus.RUNNING))
        syncer.sync(stopwatch(status = StopwatchStatus.IDLE))

        verify(exactly = 1) { transport.show(match { it.status == StopwatchStatus.RUNNING }) }
        verify(exactly = 1) { transport.cancel(ClockAlertContract.STOPWATCH_NOTIFICATION_ID) }
    }

    @Test
    fun `sync keeps paused stopwatch visible without cancelling`() {
        syncer.sync(stopwatch(status = StopwatchStatus.PAUSED, accumulatedElapsedMs = 12_000L))

        verify(exactly = 1) { transport.show(match { it.status == StopwatchStatus.PAUSED }) }
        verify(exactly = 0) { transport.cancel(any()) }
    }

    private fun stopwatch(
        status: StopwatchStatus,
        accumulatedElapsedMs: Long = 0L,
    ) = ClockStopwatch(
        id = "primary",
        status = status,
        accumulatedElapsedMs = accumulatedElapsedMs,
        updatedAtMillis = 1_000L,
    )
}
