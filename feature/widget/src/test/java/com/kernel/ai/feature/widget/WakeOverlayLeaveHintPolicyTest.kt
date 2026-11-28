package com.kernel.ai.feature.widget

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeOverlayLeaveHintPolicyTest {

    @Test
    fun `leave hint during the grace window of an assistant-launched overlay does not cancel it`() {
        assertFalse(shouldCancelWakeOverlayOnLeaveHint(elapsedSinceStartMs = 0, startedByAssistantOrKeyguard = true))
    }

    @Test
    fun `leave hint during the grace window of a keyguard-launched overlay does not cancel it`() {
        assertFalse(shouldCancelWakeOverlayOnLeaveHint(elapsedSinceStartMs = 900, startedByAssistantOrKeyguard = true))
    }

    @Test
    fun `leave hint one millisecond before the grace window ends still does not cancel`() {
        assertFalse(
            shouldCancelWakeOverlayOnLeaveHint(
                elapsedSinceStartMs = WAKE_LEAVE_HINT_GRACE_MS - 1,
                startedByAssistantOrKeyguard = true,
            ),
        )
    }

    @Test
    fun `leave hint at the end of the grace window cancels, so a real Home press still closes the overlay`() {
        assertTrue(
            shouldCancelWakeOverlayOnLeaveHint(
                elapsedSinceStartMs = WAKE_LEAVE_HINT_GRACE_MS,
                startedByAssistantOrKeyguard = true,
            ),
        )
    }

    @Test
    fun `leave hint inside the window cancels when the overlay was not assistant- or keyguard-launched`() {
        assertTrue(shouldCancelWakeOverlayOnLeaveHint(elapsedSinceStartMs = 0, startedByAssistantOrKeyguard = false))
    }

    @Test
    fun `leave hint after the grace window cancels for an assistant-launched overlay`() {
        assertTrue(shouldCancelWakeOverlayOnLeaveHint(elapsedSinceStartMs = 5_000, startedByAssistantOrKeyguard = true))
    }
}
