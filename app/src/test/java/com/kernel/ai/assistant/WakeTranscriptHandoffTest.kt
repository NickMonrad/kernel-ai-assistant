package com.kernel.ai.assistant

import com.kernel.ai.core.voice.HeldTake
import com.kernel.ai.core.voice.HeldWakeTranscript
import com.kernel.ai.core.voice.WakeOverlayChannel
import com.kernel.ai.core.voice.WakeWordHandoff
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** #2081: a wake transcript goes to the attached overlay, or is held for a tap, and is never dropped. */
class WakeTranscriptHandoffTest {

    @BeforeEach
    fun resetHandoff() {
        WakeWordHandoff.dropAll()
    }

    @AfterEach
    fun clearHandoff() {
        WakeWordHandoff.dropAll()
    }

    @Test
    fun `assistant session route with an attached overlay that takes the transcript holds nothing`() = runTest {
        // The screen-off, locked case routes through the assistant session (no keyguard decision involved).
        assertEquals(
            WakeOverlayRoute.ASSISTANT_SESSION,
            chooseWakeOverlayRoute(interactive = false, keyguardLocked = true, assistantSessionAvailable = true),
        )
        val token = WakeOverlayChannel.begin()
        WakeOverlayChannel.attachOverlay(token)
        val taken = async { WakeOverlayChannel.awaitTranscript(token) }

        val result = handOffWakeTranscript("what time is it", token, nowMs = 0L, consumeWaitMs = 1_000L, pollMs = 10L)

        assertEquals(WakeTranscriptHandoffResult.Consumed, result)
        assertEquals("what time is it", taken.await())
        assertNull(WakeWordHandoff.heldTranscript)
    }

    @Test
    fun `no attached overlay holds the transcript for a tap with the not-attached reason`() = runTest {
        val token = WakeOverlayChannel.begin()

        val result = handOffWakeTranscript("set a timer", token, nowMs = 100L, consumeWaitMs = 1_000L, pollMs = 10L)

        assertEquals(WakeTranscriptHandoffResult.Held("overlay_not_attached", null), result)
        assertEquals(HeldWakeTranscript(token, "set a timer", 100L), WakeWordHandoff.heldTranscript)
    }

    @Test
    fun `attached overlay that never takes the transcript holds it and never delivers it late`() = runTest {
        val token = WakeOverlayChannel.begin()
        WakeOverlayChannel.attachOverlay(token)

        val result = handOffWakeTranscript("late", token, nowMs = 0L, consumeWaitMs = 30L, pollMs = 10L)

        assertEquals(WakeTranscriptHandoffResult.Held("overlay_not_taken", null), result)
        assertEquals(HeldWakeTranscript(token, "late", 0L), WakeWordHandoff.heldTranscript)
        assertFalse(WakeOverlayChannel.isTranscriptTaken(token))
        // The offer was withdrawn: a late overlay cannot take the transcript that is now held.
        assertNull(withTimeoutOrNull(50L) { WakeOverlayChannel.awaitTranscript(token) })
    }

    @Test
    fun `holding a second transcript returns the displaced one so the service can journal it`() = runTest {
        val first = WakeOverlayChannel.begin()
        handOffWakeTranscript("first", first, nowMs = 1L, consumeWaitMs = 0L, pollMs = 10L)
        val second = WakeOverlayChannel.begin()

        val result = handOffWakeTranscript("second", second, nowMs = 2L, consumeWaitMs = 0L, pollMs = 10L)

        assertEquals(
            WakeTranscriptHandoffResult.Held("overlay_not_attached", HeldWakeTranscript(first, "first", 1L)),
            result,
        )
        assertEquals(HeldWakeTranscript(second, "second", 2L), WakeWordHandoff.heldTranscript)
    }

    @Test
    fun `tap delivers a held transcript inside the window and expires it after`() {
        WakeWordHandoff.hold(HeldWakeTranscript(7L, "what time", heldAtMs = 1_000L))

        assertEquals(HeldTake.Delivered("what time"), WakeWordHandoff.takeHeld(7L, nowMs = 15_999L, maxAgeMs = 15_000L))
        assertEquals(HeldTake.Absent, WakeWordHandoff.takeHeld(7L, nowMs = 15_999L, maxAgeMs = 15_000L))

        WakeWordHandoff.hold(HeldWakeTranscript(8L, "stale", heldAtMs = 1_000L))
        assertEquals(
            HeldTake.Expired(HeldWakeTranscript(8L, "stale", 1_000L)),
            WakeWordHandoff.takeHeld(8L, nowMs = 16_000L, maxAgeMs = 15_000L),
        )
        assertNull(WakeWordHandoff.heldTranscript)
    }
}
