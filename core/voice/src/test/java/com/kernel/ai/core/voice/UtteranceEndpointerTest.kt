package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UtteranceEndpointerTest {
    private val chunk = 1_600 // 100 ms at 16 kHz

    @Test
    fun `trailing silence after armed speech ends the utterance on the VAD reason`() {
        val e = UtteranceEndpointer()
        repeat(3) { assertNull(e.onChunk(chunk, speech = true)) }
        assertTrue(e.armed)
        // 700 ms of silence is seven 100 ms chunks; the endpoint fires on the seventh, not before.
        repeat(6) { assertNull(e.onChunk(chunk, speech = false)) }
        assertEquals(EndpointReason.VAD, e.onChunk(chunk, speech = false))
    }

    @Test
    fun `a lone 100 ms cue does not arm the endpoint, so silence after it does not end the capture`() {
        val e = UtteranceEndpointer()
        assertNull(e.onChunk(chunk, speech = true))
        assertTrue(e.heardSpeech)
        assertFalse(e.armed)
        repeat(98) { assertNull(e.onChunk(chunk, speech = false)) }
        assertEquals(EndpointReason.NO_SPEECH, e.onChunk(chunk, speech = false))
    }

    @Test
    fun `a 500 ms hesitation inside an armed utterance does not end it`() {
        val e = UtteranceEndpointer()
        repeat(3) { e.onChunk(chunk, speech = true) }
        // "set a timer for … ten minutes": a 500 ms pause is five chunks, below the 700 ms endpoint.
        repeat(5) { assertNull(e.onChunk(chunk, speech = false)) }
        repeat(3) { assertNull(e.onChunk(chunk, speech = true)) }
        repeat(6) { assertNull(e.onChunk(chunk, speech = false)) }
        assertEquals(EndpointReason.VAD, e.onChunk(chunk, speech = false))
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

    @Test
    fun `speech inside the cue guard neither arms the endpoint nor sets the onset flag`() {
        val e = UtteranceEndpointer(cueGuardSamples = 5 * chunk)
        repeat(5) { assertNull(e.onChunk(chunk, speech = true)) }
        assertFalse(e.heardSpeech)
        assertFalse(e.armed)
        // The guard hid the cue, so the capture still runs to the no-speech window.
        repeat(94) { assertNull(e.onChunk(chunk, speech = false)) }
        assertEquals(EndpointReason.NO_SPEECH, e.onChunk(chunk, speech = false))
    }

    @Test
    fun `speech that starts at the cue guard boundary arms the endpoint`() {
        val e = UtteranceEndpointer(cueGuardSamples = 5 * chunk)
        repeat(5) { e.onChunk(chunk, speech = true) }
        repeat(3) { e.onChunk(chunk, speech = true) }
        assertTrue(e.armed)
    }

    @Test
    fun `a decoder hint counts as speech only before the utterance is armed`() {
        val e = UtteranceEndpointer()
        repeat(3) { e.onChunk(chunk, speech = false, hint = true) }
        assertTrue(e.armed)
        // Armed now: hints no longer keep it open, so 700 ms of VAD silence ends it.
        repeat(6) { assertNull(e.onChunk(chunk, speech = false, hint = true)) }
        assertEquals(EndpointReason.VAD, e.onChunk(chunk, speech = false, hint = true))
    }
}
