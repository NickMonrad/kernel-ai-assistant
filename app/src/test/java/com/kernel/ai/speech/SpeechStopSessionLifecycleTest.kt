package com.kernel.ai.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeechStopSessionLifecycleTest {

    private var opens = 0
    private var closes = 0
    private val lifecycle = SpeechStopSessionLifecycle(
        open = { opens++ },
        close = { closes++ },
    )

    @Test
    fun `speaking starts open the session once and speaking stops close it once`() {
        lifecycle.onSpeakingChanged(true)
        lifecycle.onSpeakingChanged(false)

        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    @Test
    fun `repeated speaking-started events do not reopen the session`() {
        lifecycle.onSpeakingChanged(true)
        lifecycle.onSpeakingChanged(true)

        assertEquals(1, opens)
        assertEquals(0, closes)
    }

    @Test
    fun `stop without a prior start does not close anything`() {
        lifecycle.onSpeakingChanged(false)

        assertEquals(0, opens)
        assertEquals(0, closes)
    }

    @Test
    fun `a new utterance after a stop opens a fresh session`() {
        lifecycle.onSpeakingChanged(true)
        lifecycle.onSpeakingChanged(false)
        lifecycle.onSpeakingChanged(true)

        assertEquals(2, opens)
        assertEquals(1, closes)
    }
}
