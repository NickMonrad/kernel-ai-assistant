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
}
