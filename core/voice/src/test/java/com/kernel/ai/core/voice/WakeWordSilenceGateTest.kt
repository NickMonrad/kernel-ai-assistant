package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeWordSilenceGateTest {

    @Test
    fun `secondsToFrames rounds up to the next frame`() {
        assertEquals(1, secondsToFrames(WAKE_WORD_FRAME_DURATION_SECONDS))
        assertEquals(2, secondsToFrames(WAKE_WORD_FRAME_DURATION_SECONDS + 0.001f))
        assertEquals(32, secondsToFrames(2.5f))
    }

    @Test
    fun `default replay window covers at least classifier receptive field`() {
        val replayFrames = secondsToFrames(WAKE_WORD_DEFAULT_SILENCE_REARM_SECONDS)
        val melFrames = kotlin.math.ceil((76f / 5f)).toInt()
        val receptiveFrames = melFrames + (16 - 1)

        assertTrue(replayFrames >= receptiveFrames)
    }

    @Test
    fun `default silence skip interval is one second`() {
        assertEquals(13, secondsToFrames(WAKE_WORD_MAX_SILENCE_SKIP_SECONDS))
    }

    @Test
    fun `prolonged silence enters once and periodic inference does not resume`() {
        val state = SilenceGateTransitionState()

        assertTrue(state.enter())
        assertTrue(state.isGated)
        assertEquals(false, state.enter())
        assertEquals(false, state.onStage2Execution())
        assertTrue(state.isGated)
        assertEquals(false, state.enter())
    }

    @Test
    fun `voiced exit resumes stage2 once then permits a new gate entry`() {
        val state = SilenceGateTransitionState()
        state.enter()

        assertTrue(state.onVoicedFrame())
        assertEquals(false, state.isGated)
        assertEquals(false, state.onVoicedFrame())
        assertTrue(state.onStage2Execution())
        assertEquals(false, state.onStage2Execution())
        assertTrue(state.enter())
        assertTrue(state.isGated)
        assertEquals(false, state.enter())
    }
}
