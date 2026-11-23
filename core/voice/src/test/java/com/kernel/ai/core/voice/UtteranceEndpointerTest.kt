package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UtteranceEndpointerTest {
    private val chunk = 1_600 // 100 ms at 16 kHz

    @Test
    fun `trailing silence after speech ends the utterance on the VAD reason`() {
        val e = UtteranceEndpointer()
        assertNull(e.onChunk(chunk, speech = true))
        // 700 ms of silence is seven 100 ms chunks; the endpoint fires on the seventh, not before.
        repeat(6) { assertNull(e.onChunk(chunk, speech = false)) }
        assertEquals(EndpointReason.VAD, e.onChunk(chunk, speech = false))
    }

    @Test
    fun `speech resets the trailing silence so a pause shorter than 700 ms does not end the utterance`() {
        val e = UtteranceEndpointer()
        e.onChunk(chunk, speech = true)
        repeat(6) { e.onChunk(chunk, speech = false) }
        assertNull(e.onChunk(chunk, speech = true))
        repeat(6) { assertNull(e.onChunk(chunk, speech = false)) }
    }

    @Test
    fun `silence before any speech runs to the no-speech window, not the VAD endpoint`() {
        val e = UtteranceEndpointer()
        repeat(99) { assertNull(e.onChunk(chunk, speech = false)) }
        assertEquals(EndpointReason.NO_SPEECH, e.onChunk(chunk, speech = false))
        assertFalse(e.heardSpeech)
    }

    @Test
    fun `steady speech never endpoints and the 20 s cap ends the capture`() {
        val e = UtteranceEndpointer()
        repeat(199) { assertNull(e.onChunk(chunk, speech = true)) }
        assertEquals(EndpointReason.CAP, e.onChunk(chunk, speech = true))
    }

    @Test
    fun `the heard flag is set by speech and not by silence alone`() {
        val e = UtteranceEndpointer()
        e.onChunk(chunk, speech = false)
        assertFalse(e.heardSpeech)
        e.onChunk(chunk, speech = true)
        assertTrue(e.heardSpeech)
    }
}
