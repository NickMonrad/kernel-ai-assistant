package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.SpeechCardText
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
    fun en_gb_expands_every_negative_contraction_to_its_words() {
        // "shan't" once became "sha not": the generic n't rule ran before any shall-not rule.
        assertEquals(
            "I cannot stop. We will not stop. You shall not pass. It does not work.",
            KokoroTextFrontend.phonemizerInput(
                "I can't stop. We won't stop. You shan't pass. It doesn't work.",
                NativeKokoroPhonemizer.LOCALE_EN_GB,
            ),
        )
    }

    @Test
    fun speech_card_line_break_after_closing_quote_adds_no_full_stop() {
        // #2177: `wings."` followed by a line break gained a second full stop before the next sentence.
        assertEquals(
            "He said not wings.\" Next",
            KokoroTextFrontend.phonemizerInput(
                SpeechCardText.speakable("He said not wings.\"\nNext"),
                NativeKokoroPhonemizer.LOCALE_EN_GB,
            ),
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
