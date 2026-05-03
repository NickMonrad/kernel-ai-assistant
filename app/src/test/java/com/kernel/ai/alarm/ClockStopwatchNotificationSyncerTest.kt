package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.StopwatchLap
import com.kernel.ai.core.memory.clock.StopwatchStatus
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `running stopwatch notification keeps chronometer prominent and surfaces latest lap in title`() {
        val stopwatch = stopwatch(
            status = StopwatchStatus.RUNNING,
            laps = listOf(
                StopwatchLap(id = 2L, lapNumber = 2, elapsedMs = 8_000L, splitMs = 3_000L, createdAtMillis = 12_000L),
            ),
        )

        assertEquals("Stopwatch · Lap 2", stopwatchNotificationTitle(stopwatch))
        assertNull(stopwatchNotificationText(stopwatch, elapsedMs = 8_000L))
    }

    @Test
    fun `paused stopwatch notification summarizes elapsed time and lap count`() {
        val stopwatch = stopwatch(
            status = StopwatchStatus.PAUSED,
            accumulatedElapsedMs = 12_000L,
            laps = listOf(
                StopwatchLap(id = 1L, lapNumber = 1, elapsedMs = 12_000L, splitMs = 12_000L, createdAtMillis = 12_000L),
            ),
        )

        assertEquals("Stopwatch paused", stopwatchNotificationTitle(stopwatch))
        assertEquals("Paused at 00:12 · 1 lap", stopwatchNotificationText(stopwatch, elapsedMs = 12_000L))
    }


    private fun stopwatch(
        status: StopwatchStatus,
        accumulatedElapsedMs: Long = 0L,
        laps: List<StopwatchLap> = emptyList(),
    ) = ClockStopwatch(
        id = "primary",
        status = status,
        accumulatedElapsedMs = accumulatedElapsedMs,
        updatedAtMillis = 1_000L,
        laps = laps,
    )
}
