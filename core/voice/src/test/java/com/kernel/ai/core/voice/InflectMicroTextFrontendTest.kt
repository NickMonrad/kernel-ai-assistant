package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
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
