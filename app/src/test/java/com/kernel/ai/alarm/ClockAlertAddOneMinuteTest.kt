package com.kernel.ai.alarm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * "+1 min" from the notification and the full-screen alert. Before the fix it scheduled the extra minute but
 * never dismissed the ringing timer, so the sound kept playing with the full-screen alert closed.
 */
class ClockAlertAddOneMinuteTest {

    @Test
    fun `successful add one minute stops the ringing alert once`() = runTest {
        var dismissals = 0

        runAddOneMinuteAction(
            addOneMinute = { true },
            dismiss = { dismissals++ },
        )

        assertEquals(1, dismissals)
    }

    @Test
    fun `failed add one minute keeps the alert ringing`() = runTest {
        var dismissals = 0

        runAddOneMinuteAction(
            addOneMinute = { false },
            dismiss = { dismissals++ },
        )

        assertEquals(0, dismissals)
    }
}
