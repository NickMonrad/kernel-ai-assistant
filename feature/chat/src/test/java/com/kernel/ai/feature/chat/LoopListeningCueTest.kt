package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoopListeningCueTest {

    @Test
    fun `plays cue when loop enters preparing from idle`() {
        assertTrue(
            shouldPlayLoopListeningCue(
                loopActive = true,
                currentState = LoopListeningCueState.Preparing,
                previousState = LoopListeningCueState.Idle,
            ),
        )
    }

    @Test
    fun `does not play cue when loop is inactive`() {
        assertFalse(
            shouldPlayLoopListeningCue(
                loopActive = false,
                currentState = LoopListeningCueState.Preparing,
                previousState = LoopListeningCueState.Idle,
            ),
        )
    }

    @Test
    fun `does not replay cue while listen startup is already in progress`() {
        assertFalse(
            shouldPlayLoopListeningCue(
                loopActive = true,
                currentState = LoopListeningCueState.Preparing,
                previousState = LoopListeningCueState.Preparing,
            ),
        )
        assertFalse(
            shouldPlayLoopListeningCue(
                loopActive = true,
                currentState = LoopListeningCueState.Preparing,
                previousState = LoopListeningCueState.Listening,
            ),
        )
    }
}
