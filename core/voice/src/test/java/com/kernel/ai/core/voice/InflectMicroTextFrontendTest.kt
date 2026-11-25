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
            "1/21/2026" to "January twenty first two thousand and twenty six",
            "21st" to "twenty first",
            "105th" to "one hundred and fifth",
            "\$105.05" to "one hundred and five dollars and five cents",
            "1,234" to "one thousand two hundred and thirty four",
        )

        vectors.forEach { (input, expected) ->
            assertEquals(expected, InflectMicroTextFrontend.normalize(input), input)
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
        assertEquals("one three zero zero, five five five, zero one nine", normalized)
        assertFalse(normalized.contains("one thousand"))
        assertFalse(normalized.contains("nineteen"))
        assertTrue(normalized.contains("zero one nine"))
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
            "two oh three, zero six seven eight",
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
    fun speechChunks_keeps_short_multi_sentence_reply_in_one_chunk() {
        val text = "Sure. I've set the reminder for tomorrow morning."

        assertEquals(listOf(text), InflectMicroTextFrontend.speechChunks(text))
    }

    @Test
    fun speechChunks_is_lossless_bounded_and_breaks_at_sentence_ends() {
        val text = (1..40).joinToString(" ") { "Sentence number $it is here." }

        val chunks = InflectMicroTextFrontend.speechChunks(text, maxChunkChars = 120)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 120 })
        assertTrue(chunks.all { it.endsWith('.') })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun speechChunks_splits_overlong_sentence_by_word_without_dropping_words() {
        val text = (1..300).joinToString(" ") { "word$it" }

        val chunks = InflectMicroTextFrontend.speechChunks(text, maxChunkChars = 50)

        assertTrue(chunks.all { it.length <= 50 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun speechChunks_prefers_clause_boundaries_before_word_boundaries() {
        val chunks = InflectMicroTextFrontend.speechChunks(
            normalizedText = "one two three four five, six seven eight nine ten, eleven twelve",
            maxChunkChars = 25,
        )

        assertEquals(
            listOf("one two three four five,", "six seven eight nine ten,", "eleven twelve"),
            chunks,
        )
    }

    @Test
    fun phonemizeSpeechChunks_phonemizes_lazily_one_chunk_at_a_time() {
        val text = (1..5).joinToString(" ") { "Sentence $it ${"x".repeat(90)}." }
        var phonemizeCalls = 0

        val first = InflectMicroTextFrontend.phonemizeSpeechChunks(text) { input ->
            phonemizeCalls++
            input
        }.first()

        assertEquals(1, phonemizeCalls)
        assertTrue(first.text.startsWith("Sentence 1"))
    }
}
