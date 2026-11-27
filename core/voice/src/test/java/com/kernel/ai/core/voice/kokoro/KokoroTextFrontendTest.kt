package com.kernel.ai.core.voice.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KokoroTextFrontendTest {
    @Test
    fun en_gb_expands_contractions_the_british_lexicon_reads_wrongly() {
        // The en-gb lexicon reads "I'll" as "ill" (#2116 comment: "I remind you"), so it must be expanded.
        assertEquals(
            "I will remind you",
            KokoroTextFrontend.phonemizerInput("I'll remind you", NativeKokoroPhonemizer.LOCALE_EN_GB),
        )
    }

    @Test
    fun en_us_keeps_the_contraction_because_its_lexicon_reads_it_correctly() {
        assertEquals(
            "I'll remind you",
            KokoroTextFrontend.phonemizerInput("I'll remind you", NativeKokoroPhonemizer.LOCALE_EN_US),
        )
    }

    @Test
    fun card_cleaning_and_number_normalisation_run_before_the_phonemiser() {
        assertEquals(
            "It happened in nineteen ninety nine. " +
                "Monday the twenty third of November: twenty seven degrees, ninety two percent rain.",
            KokoroTextFrontend.phonemizerInput(
                "It happened in 1999. Mon 23 Nov: 27°C, 92% rain.",
                NativeKokoroPhonemizer.LOCALE_EN_US,
            ),
        )
    }
}
