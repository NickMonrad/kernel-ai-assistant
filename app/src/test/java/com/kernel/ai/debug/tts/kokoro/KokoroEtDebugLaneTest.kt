package com.kernel.ai.debug.tts.kokoro

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KokoroEtDebugLaneTest {
    private class RecordingPhonemizer : KokoroPhonemizer {
        val inputs = mutableListOf<String>()

        override fun phonemize(text: String): PhonemizedText {
            inputs += text
            return PhonemizedText(phonemes = "a", words = emptyList())
        }

        override fun close() = Unit
    }

    @Test
    fun phonemiser_receives_card_cleaned_and_number_normalised_text() {
        val phonemizer = RecordingPhonemizer()
        // Model files are only read by initialize(); phonemize() needs no model.
        val lane = KokoroEtDebugLane(File("unused"), "af_heart", phonemizer)

        lane.phonemize("It happened in 1999. Mon 23 Nov: 27°C, 92% rain.")

        assertEquals(
            listOf(
                "It happened in nineteen ninety nine. " +
                    "Monday the twenty third of November: twenty seven degrees, ninety two percent rain.",
            ),
            phonemizer.inputs,
        )
    }
}
