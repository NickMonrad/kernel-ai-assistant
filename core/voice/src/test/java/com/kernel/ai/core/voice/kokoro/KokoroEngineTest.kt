package com.kernel.ai.core.voice.kokoro

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KokoroEngineTest {
    private class RecordingPhonemizer : KokoroPhonemizer {
        val inputs = mutableListOf<String>()

        /** One located word spanning the whole input, with a single vowel phoneme. */
        override fun phonemize(text: String): PhonemizedText {
            inputs += text
            return PhonemizedText("a", intArrayOf(0, text.length, 0, 1))
        }

        override fun close() = Unit
    }

    @Test
    fun split_text_passes_already_normalised_text_to_the_phonemiser_unchanged() {
        val phonemizer = RecordingPhonemizer()
        // Model files are only read by initialize(); splitText() needs no model.
        val engine = KokoroEngine(File("unused"), File("unused"), phonemizer)

        engine.splitText("Tomorrow it will be minus four degrees, the coldest since nineteen ninety nine.")

        assertEquals(
            listOf("Tomorrow it will be minus four degrees, the coldest since nineteen ninety nine."),
            phonemizer.inputs,
        )
    }

    @Test
    fun blank_input_is_rejected_before_the_phonemiser_runs() {
        val phonemizer = RecordingPhonemizer()
        val engine = KokoroEngine(File("unused"), File("unused"), phonemizer)

        assertThrows(IllegalArgumentException::class.java) { engine.splitText("   ") }
        assertEquals(emptyList<String>(), phonemizer.inputs)
    }
}
