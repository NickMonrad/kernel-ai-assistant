package com.kernel.ai.alarm

import android.media.Ringtone
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ClockAlertPlaybackTest {
    @Test
    fun `shouldDuckAlertPlayback returns true only when alerts are active`() {
        assertEquals(true, shouldDuckAlertPlayback(1))
        assertEquals(true, shouldDuckAlertPlayback(2))
        assertEquals(false, shouldDuckAlertPlayback(0))
    }

    @Test
    fun `shouldRestoreDuckedPlayback returns true only for active ducked playback`() {
        assertEquals(true, shouldRestoreDuckedPlayback(true, 1))
        assertEquals(false, shouldRestoreDuckedPlayback(false, 1))
        assertEquals(false, shouldRestoreDuckedPlayback(true, 0))
    }

    @Test
    fun `applyAlertPlaybackVolume forwards the requested volume to ringtone`() {
        val ringtone = mockk<Ringtone>(relaxed = true)

        applyAlertPlaybackVolume(ringtone, 0.2f)

        verify(exactly = 1) { ringtone.setVolume(0.2f) }
    }

    @Test
    fun `alertPendingIntentIdentity distinguishes action owner and occurrence`() {
        val first = TriggeredClockAlert(
            ownerId = "alarm-1",
            type = com.kernel.ai.core.memory.clock.ClockEventType.ALARM,
            title = "Alarm",
            label = "Weekday",
            occurrenceTriggerAtMillis = 1000L,
        )
        val secondOccurrence = first.copy(occurrenceTriggerAtMillis = 2000L)
        val secondOwner = first.copy(ownerId = "alarm-2")

        assertNotEquals(
            alertPendingIntentIdentity(ClockAlertContract.ACTION_STOP_ALERT, first),
            alertPendingIntentIdentity(ClockAlertContract.ACTION_SNOOZE_ALERT, first),
        )
        assertNotEquals(
            alertPendingIntentIdentity(ClockAlertContract.ACTION_STOP_ALERT, first),
            alertPendingIntentIdentity(ClockAlertContract.ACTION_STOP_ALERT, secondOccurrence),
        )
        assertNotEquals(
            alertPendingIntentIdentity(ClockAlertContract.ACTION_STOP_ALERT, first),
            alertPendingIntentIdentity(ClockAlertContract.ACTION_STOP_ALERT, secondOwner),
        )
    }
}
