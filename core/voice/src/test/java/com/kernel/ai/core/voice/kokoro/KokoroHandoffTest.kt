package com.kernel.ai.core.voice.kokoro

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KokoroHandoffTest {
    @Test
    fun `sentences split after sentence punctuation and drop blanks`() {
        assertEquals(listOf("Hello there.", "Are you ok?", "Yes!"), kokoroSentences("  Hello there. Are you ok?  Yes!  "))
    }

    @Test
    fun `a sentence that cannot be phonemized hands it and the rest over, after the spoken sentences`() {
        val handoff = KokoroHandoff()
        val units = kokoroSentenceUnits("One. Two. Three.", handoff) { sentence ->
            if (sentence == "Two.") throw IllegalStateException("phonemizer") else listOf("<$sentence>")
        }.toList()

        assertEquals(listOf("<One.>"), units.map { it.chunk })
        assertTrue(handoff.active)
        assertEquals("Two. Three.", handoff.remainder())
    }

    @Test
    fun `a chunk that fails to synthesize hands over its own sentence and everything after it`() {
        val handoff = KokoroHandoff()
        val units = kokoroSentenceUnits("One. Two.", handoff) { listOf(it) }.toList()

        val waveform = runBlocking {
            handoff.synthesizeOrHandOff(units[1]) { throw IllegalStateException("boom") }
        }

        assertNull(waveform)
        assertEquals("Two.", handoff.remainder())
        assertTrue(handoff.reason.orEmpty().contains("boom"))
    }

    @Test
    fun `a native Error from a chunk hands over like an Exception, so the reply is not lost`() {
        val handoff = KokoroHandoff()
        val units = kokoroSentenceUnits("One. Two.", handoff) { listOf(it) }.toList()

        val waveform = runBlocking {
            handoff.synthesizeOrHandOff(units[1]) { throw OutOfMemoryError("native allocation") }
        }

        assertNull(waveform)
        assertEquals("Two.", handoff.remainder())
    }

    @Test
    fun `a phonemizer Error hands the whole append over and yields no units`() {
        val handoff = KokoroHandoff()

        val units = kokoroSentenceUnits("One. Two.", handoff) { throw LinkageError("phonemis missing") }.toList()

        assertTrue(units.isEmpty())
        assertEquals("One. Two.", handoff.remainder())
    }

    @Test
    fun `text appended after a hand-off joins the remainder and yields no units`() {
        val handoff = KokoroHandoff()
        handoff.failed("phonemizer failed", "Two. Three.")

        val units = kokoroSentenceUnits("Four.", handoff) { listOf(it) }.toList()

        assertTrue(units.isEmpty())
        assertEquals("Two. Three. Four.", handoff.remainder())
    }
}
