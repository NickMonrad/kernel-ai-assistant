package com.kernel.ai.assistant

import com.kernel.ai.core.voice.HeldWakeTranscript
import com.kernel.ai.core.voice.WakeWordHandoff
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** #2081: a transcript the overlay does not take is held for a tap, never dropped. */
class WakeTranscriptHandoffTest {

    @BeforeEach
    fun resetHandoff() {
        WakeWordHandoff.pendingTranscript = null
        WakeWordHandoff.clearHeld()
    }

    @AfterEach
    fun clearHandoff() {
        WakeWordHandoff.pendingTranscript = null
        WakeWordHandoff.clearHeld()
    }

    @Test
    fun `overlay that takes the transcript consumes it and holds nothing`() = runTest {
        val result = handOffWakeTranscript(
            transcript = "what time is it",
            wakeToken = 7L,
            startOverlay = { WakeWordHandoff.pendingTranscript = null },
        )

        assertEquals(WakeTranscriptHandoffResult.Consumed, result)
        assertNull(WakeWordHandoff.heldTranscript)
    }

    @Test
    fun `start that returns without the overlay consuming the transcript holds it`() = runTest {
        val result = handOffWakeTranscript(
            transcript = "what time is it",
            wakeToken = 7L,
            startOverlay = {},
        )

        assertEquals(WakeTranscriptHandoffResult.Held("overlay_not_shown"), result)
        assertEquals(HeldWakeTranscript(7L, "what time is it"), WakeWordHandoff.heldTranscript)
        assertNull(WakeWordHandoff.pendingTranscript)
    }

    @Test
    fun `screen off or locked skips the start and holds straight away`() = runTest {
        val result = handOffWakeTranscript(
            transcript = "what time is it",
            wakeToken = 7L,
            startOverlay = null,
        )

        assertEquals(WakeTranscriptHandoffResult.Held("start_skipped"), result)
        assertEquals(HeldWakeTranscript(7L, "what time is it"), WakeWordHandoff.heldTranscript)
        assertNull(WakeWordHandoff.pendingTranscript)
    }

    @Test
    fun `start is attempted only on an interactive unlocked device`() {
        assertTrue(shouldStartOverlayForTranscript(interactive = true, keyguardLocked = false))
        assertFalse(shouldStartOverlayForTranscript(interactive = false, keyguardLocked = false))
        assertFalse(shouldStartOverlayForTranscript(interactive = true, keyguardLocked = true))
        assertFalse(shouldStartOverlayForTranscript(interactive = false, keyguardLocked = true))
    }

    @Test
    fun `start that throws holds the transcript with start_refused`() = runTest {
        val result = handOffWakeTranscript(
            transcript = "what time is it",
            wakeToken = 7L,
            startOverlay = { throw SecurityException("background activity launch blocked") },
        )

        assertEquals(WakeTranscriptHandoffResult.Held("start_refused"), result)
        assertEquals(HeldWakeTranscript(7L, "what time is it"), WakeWordHandoff.heldTranscript)
    }

    @Test
    fun `held transcript is delivered once and only to its own wake token`() = runTest {
        handOffWakeTranscript(
            transcript = "what time is it",
            wakeToken = 7L,
            startOverlay = {},
        )

        assertNull(WakeWordHandoff.takeHeld(8L))
        assertEquals("what time is it", WakeWordHandoff.takeHeld(7L))
        assertNull(WakeWordHandoff.takeHeld(7L))
    }

    @Test
    fun `expiring an undelivered held transcript drops it once`() = runTest {
        handOffWakeTranscript(
            transcript = "what time is it",
            wakeToken = 7L,
            startOverlay = {},
        )

        assertTrue(WakeWordHandoff.expireHeld(7L))
        assertNull(WakeWordHandoff.heldTranscript)
        assertFalse(WakeWordHandoff.expireHeld(7L))
    }
}
