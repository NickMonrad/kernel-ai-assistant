package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockTimer
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ClockTimerNotificationSyncerTest {
    private val transport = mockk<ClockTimerNotificationTransport>(relaxed = true)
    private val syncer = ClockTimerNotificationSyncer(transport)

    @Test
    fun `sync shows active timers and cancels removed ones`() {
        val tea = timer(id = "tea")
        val pasta = timer(id = "pasta")

        syncer.sync(listOf(tea, pasta))
        syncer.sync(listOf(pasta))

        verify(exactly = 1) { transport.show(tea) }
        verify(exactly = 2) { transport.show(pasta) }
        verify(exactly = 1) { transport.cancel(ClockAlertContract.timerNotificationId("tea")) }
        verify(exactly = 0) { transport.cancel(ClockAlertContract.timerNotificationId("pasta")) }
    }

    @Test
    fun `sync cancels every notification when timers become empty`() {
        val tea = timer(id = "tea")
        val pasta = timer(id = "pasta")

        syncer.sync(listOf(tea, pasta))
        syncer.sync(emptyList())

        verify(exactly = 1) { transport.cancel(ClockAlertContract.timerNotificationId("tea")) }
        verify(exactly = 1) { transport.cancel(ClockAlertContract.timerNotificationId("pasta")) }
    }

    private fun timer(id: String) = ClockTimer(
        id = id,
        triggerAtMillis = 10_000L,
        label = id,
        createdAtMillis = 1_000L,
        durationMs = 5_000L,
        startedAtMillis = 5_000L,
    )
}
