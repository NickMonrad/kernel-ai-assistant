package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InflectMicroTextFrontendTest {
    @Test
    fun normalize_matches_runtime_expansion_contract() {
        val normalized = InflectMicroTextFrontend.normalize(
            "At 7:05 p.m., send \$1.50 to Dr. Qwen.",
        )

        assertEquals(
            "At seven oh five p m. , send one dollar and fifty cents to doctor Qwen.",
            normalized,
        )
    }

    @Test
    fun normalize_expands_bare_hour_identifiers_and_street_numbers() {
        val normalized = InflectMicroTextFrontend.normalize(
            "Meet me at 8 pm in Apt 4B, on 101 North.",
        )

        assertTrue(normalized.contains("eight p m"))
        assertTrue(normalized.contains("Apt four bee"))
        assertTrue(normalized.contains("one oh one North"))
    }

    @Test
    fun normalize_preserves_non_ascii_letters_and_translates_smart_punctuation() {
        assertEquals("It's, café, ready. . .", InflectMicroTextFrontend.normalize("It’s (café) ready…"))
    }

    @Test
    fun applyPhonemeOverrides_keeps_reference_pronunciation_corrections() {
        val phonemes = "sˈæskɐtʃˌuːən flʊɹɹˈɛsənt"

        assertEquals(
            "sɐskˈætʃəwən flʊˈɹɛsənt",
            InflectMicroTextFrontend.applyPhonemeOverrides(phonemes),
        )
    }

    @Test
    fun normalize_matches_pinned_num2words_parity_vectors() {
        val vectors = mapOf(
            "105" to "one hundred and five",
            "1/21/2026" to "the twenty first of January, twenty twenty six",
            "21st" to "twenty first",
            "105th" to "one hundred and fifth",
            "\$105.05" to "one hundred and five dollars and five cents",
            "1,234" to "one thousand two hundred and thirty four",
        )

        vectors.forEach { (input, expected) ->
            assertEquals(expected, InflectMicroTextFrontend.normalize(input), input)
        }
    }

    /**
     * Before/after for the #2115 Inflect output changes. "before" is HEAD 5a4e2dd1 output for the same input
     * after [SpeechCardText.speakable]; "after" is what the shared English normaliser now produces.
     */
    @Test
    fun changed_outputs_before_and_after_table() {
        val cases = listOf(
            Triple("Low of -3°C overnight.", "Low of -three degrees overnight.", "Low of minus three degrees overnight."),
            Triple("-5°C", "-five degrees", "minus five degrees"),
            Triple("−5 degrees", "−five degrees", "minus five degrees"),
            Triple("-\$5", "-five dollars", "minus five dollars"),
            Triple(
                "Founded in 1999, renamed in 2005, updated in 2026, a band from the 1990s.",
                "Founded in one thousand nine hundred and ninety nine, renamed in two thousand and five, updated in two thousand and twenty six, a band from the 1990s.",
                "Founded in nineteen ninety nine, renamed in two thousand and five, updated in twenty twenty six, a band from the nineteen nineties.",
            ),
            Triple("It happened in 1999.", "It happened in one thousand nine hundred and ninety nine.", "It happened in nineteen ninety nine."),
            Triple("Founded in 2026.", "Founded in two thousand and twenty six.", "Founded in twenty twenty six."),
            Triple(
                "The stadium holds 15,000 people.",
                "The stadium holds one five oh oh oh people.",
                "The stadium holds fifteen thousand people.",
            ),
            Triple("1,000,000 people", "one oh oh oh oh oh oh people", "one million people"),
            Triple("1,000.50 litres", "onezero point five oh litres", "one thousand point five litres"),
            Triple("\$12.345", "twelve dollars and thirty four cents5", "twelve point three four five dollars"),
            Triple("NZ\$20", "NZtwenty dollars", "twenty New Zealand dollars"),
            Triple(
                "It's 5km to gate 5A for flight NZ123, and the 5G signal is fine.",
                "It's 5km to gate five ay for flight NZ123, and the 5G signal is fine.",
                "It's five kilometres to gate five ay for flight en zee one two three, and the five gee signal is fine.",
            ),
            Triple(
                "Booked for 2026-11-26, about 3-5 days away.",
                "Booked for two thousand and twenty six-eleven-twenty six, about three-five days away.",
                "Booked for the twenty sixth of November, twenty twenty six, about three to five days away.",
            ),
            Triple("3-5 days", "three-five days", "three to five days"),
            Triple("5–10 km", "five-ten km", "five to ten km"),
            Triple("1/21/2026", "January twenty first two thousand and twenty six", "the twenty first of January, twenty twenty six"),
            Triple("Qwen3 runs on 1080p.", "Qwen three runs on 1080p.", "Qwen three runs on ten eighty p."),
            Triple("mp3 files, 4K and 3D", "mp3 files, 4K and 3D", "em pee three files, four kay and three dee"),
            Triple("H2O and B12 and 4B", "H2O and B12 and 4B", "aitch two oh and bee twelve and four bee"),
            Triple(
                "1.50 and 1.0 and 3.10",
                "one point five oh and one point zero and three point one oh",
                "one point five and one point oh and three point one",
            ),
            // Unchanged, kept as the boundary of the change.
            Triple("1300 people", "one thousand three hundred people", "one thousand three hundred people"),
            Triple("2025", "two thousand and twenty five", "two thousand and twenty five"),
            Triple("203-0678", "two oh three, zero six seven eight", "two oh three, oh six seven eight"),
            Triple("Use 2 500 ml bottles", "Use two five hundred ml bottles", "Use two five hundred ml bottles"),
            Triple("\$49.95", "forty nine dollars and ninety five cents", "forty nine dollars and ninety five cents"),
        )
        cases.forEach { (input, _, after) ->
            assertEquals(after, InflectMicroTextFrontend.normalize(SpeechCardText.speakable(input)), input)
        }
    }

    @Test
    fun normalize_preserves_token_boundary_after_expanded_time() {
        assertEquals(
            "three thirty this afternoon",
            InflectMicroTextFrontend.normalize("3:30 this afternoon"),
        )
        assertEquals(
            "nine ten the next morning",
            InflectMicroTextFrontend.normalize("9:10 the next morning"),
        )
        assertEquals(
            "eight o clock your meeting",
            InflectMicroTextFrontend.normalize("8:00 your meeting"),
        )
    }

    @Test
    fun normalize_preserves_time_boundary_before_apostrophe() {
        val normalized = InflectMicroTextFrontend.normalize("11:15 there's ...")
        assertTrue(normalized.contains("eleven fifteen there's"))
        assertFalse(normalized.contains("fifteenthere's"))
        assertFalse(normalized.contains("thirtythis"))
    }

    @Test
    fun normalize_keeps_punctuation_following_time() {
        assertEquals("four o clock,", InflectMicroTextFrontend.normalize("4:00,"))
        assertEquals("seven o clock.", InflectMicroTextFrontend.normalize("7:00."))
    }

    @Test
    fun normalize_expands_grouped_digit_sequence_digit_by_digit() {
        val normalized = InflectMicroTextFrontend.normalize("1300 555 019")
        assertEquals("one three oh oh, five five five, oh one nine", normalized)
        assertFalse(normalized.contains("one thousand"))
        assertFalse(normalized.contains("nineteen"))
        assertTrue(normalized.contains("oh one nine"))
    }

    @Test
    fun normalize_keeps_ordinary_cardinal_for_single_quantity() {
        assertEquals(
            "one thousand three hundred people",
            InflectMicroTextFrontend.normalize("1300 people"),
        )
    }

    @Test
    fun normalize_does_not_group_two_adjacent_quantities() {
        // "3 20-minute" and "2 500" are ordinary quantities, not grouped identifiers.
        assertEquals(
            "I found three twenty-minute slots",
            InflectMicroTextFrontend.normalize("I found 3 20-minute slots"),
        )
        assertEquals(
            "Use two five hundred ml bottles",
            InflectMicroTextFrontend.normalize("Use 2 500 ml bottles"),
        )
    }

    @Test
    fun normalize_keeps_two_group_numeric_pair_cardinal() {
        assertEquals(
            "use four six volt batteries",
            InflectMicroTextFrontend.normalize("use 4 6 volt batteries"),
        )
    }

    @Test
    fun normalize_preserves_currency_expansion_regression() {
        assertEquals(
            "forty nine dollars and ninety five cents",
            InflectMicroTextFrontend.normalize("$49.95"),
        )
    }


    @Test
    fun normalize_preserves_boundary_for_am_pm_time_suffix_variants() {
        assertEquals(
            "six forty five p m tomorrow",
            InflectMicroTextFrontend.normalize("6:45 pm tomorrow"),
        )
        // A "p.m." (with periods) between words keeps its final period attached to the am/pm
        // token (pre-existing behaviour, unchanged by the #1486 boundary fix). An end-of-input
        // "p.m." stays clean.
        assertEquals(
            "six forty five p m. tomorrow",
            InflectMicroTextFrontend.normalize("6:45 p.m. tomorrow"),
        )
        assertEquals(
            "six forty five p m.",
            InflectMicroTextFrontend.normalize("6:45 p.m."),
        )
    }

    @Test
    fun normalize_leaves_non_target_number_paths_unchanged() {
        assertEquals(
            "two thousand and twenty five",
            InflectMicroTextFrontend.normalize("2025"),
        )
        assertEquals(
            "two oh three, oh six seven eight",
            InflectMicroTextFrontend.normalize("203-0678"),
        )
        assertEquals(
            "two hundred and fifty millilitres",
            InflectMicroTextFrontend.normalize("250 millilitres"),
        )
        assertEquals(
            "three point seven five kilograms",
            InflectMicroTextFrontend.normalize("3.75 kilograms"),
        )
    }


    @Test
    fun phonemizeChunks_keeps_short_multi_sentence_utterance_together() {
        val text = "Sure. I've set the reminder for tomorrow morning."
        val phonemizedInputs = mutableListOf<String>()

        val chunks = InflectMicroTextFrontend.phonemizeChunks(
            normalizedText = text,
            phonemize = { input ->
                phonemizedInputs += input
                input
            },
        )

        assertEquals(1, chunks.size)
        assertEquals(text, chunks.single().text)
        assertEquals(listOf(text), phonemizedInputs)
    }

    @Test
    fun phonemizeChunks_keeps_order_and_bounds_long_punctuation_text() {
        val normalized = (1..180).joinToString(" ") { "phoneme" }

        val chunks = InflectMicroTextFrontend.phonemizeChunks(
            normalizedText = normalized,
            maxPhonemeLength = InflectMicroOnnxRunner.MAX_PHONEME_TEXT_LENGTH,
            phonemize = { it },
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.phonemes.length <= InflectMicroOnnxRunner.MAX_PHONEME_TEXT_LENGTH })
        assertEquals(normalized, chunks.joinToString(" ") { it.text })
    }

    @Test
    fun phonemizeChunks_splits_at_punctuation_before_words() {
        val chunks = InflectMicroTextFrontend.phonemizeChunks(
            normalizedText = "One two three. Four five six seven",
            maxPhonemeLength = 10,
            phonemize = { it },
        )

        assertEquals(
            listOf("One two", "three.", "Four five", "six seven"),
            chunks.map { it.text },
        )
    }

    @Test
    fun runInflectChunks_does_not_synthesize_later_chunks_after_cancellation() {
        val chunks = listOf(
            InflectMicroTextFrontend.PhonemizedChunk("first", "first"),
            InflectMicroTextFrontend.PhonemizedChunk("second", "second"),
        )
        val synthesized = mutableListOf<String>()
        val played = mutableListOf<String>()
        var shouldContinue = true

        runInflectChunks(
            chunks = chunks,
            shouldContinue = { shouldContinue },
            synthesize = { phonemes ->
                synthesized += phonemes
                phonemes
            },
            play = {
                played += it
                shouldContinue = false
            },
        )

        assertEquals(listOf("first"), synthesized)
        assertEquals(listOf("first"), played)
    }
}
