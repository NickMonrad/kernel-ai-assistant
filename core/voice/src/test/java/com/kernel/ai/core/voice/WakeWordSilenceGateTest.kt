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
    fun `default silence skip interval stays below one second`() {
        assertEquals(12, secondsToFrames(WAKE_WORD_MAX_SILENCE_SKIP_SECONDS))
    }
}
