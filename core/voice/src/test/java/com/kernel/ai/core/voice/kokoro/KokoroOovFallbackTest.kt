package com.kernel.ai.core.voice.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fake with phonemis's behaviour (#2175): a whitespace word outside [known] gets no phonemes and nothing replaces
 * it. Trailing punctuation is emitted as-is, as phonemis does. [locates] = false mimics phonemis failing to locate
 * words, which makes [KokoroOovFallback] phonemise each word alone.
 */
private class DroppingPhonemis(
    private val known: Map<String, String>,
    private val locates: Boolean = true,
) : KokoroPhonemizer {
    override fun phonemize(text: String): PhonemizedText {
        val phonemes = StringBuilder()
        val spans = mutableListOf<Int>()
        var inputOffset = 0
        val tokens = text.split(" ")
        tokens.forEachIndexed { index, token ->
            val trailing = token.takeLastWhile { it in ".,?!" }
            val word = token.dropLast(trailing.length)
            val segment = known[word.lowercase()].orEmpty()
            val phonemeOffset = phonemes.length
            phonemes.append(segment).append(trailing)
            spans += listOf(inputOffset, token.length, phonemeOffset, segment.length + trailing.length)
            if (index < tokens.lastIndex) phonemes.append(' ')
            inputOffset += token.length + 1
        }
        return PhonemizedText(phonemes.toString(), if (locates) spans.toIntArray() else IntArray(0))
    }

    override fun close() = Unit
}

class KokoroOovFallbackTest {
    private val road = "ɹoʊd"
    private val vowels = "aeiouɑæʌəɛɪɔʊɜɚ"

    private fun phonemize(
        text: String,
        known: Map<String, String> = mapOf("road" to road),
        locates: Boolean = true,
        fallbacks: MutableList<Int> = mutableListOf(),
    ): String = KokoroOovFallback.phonemize(DroppingPhonemis(known, locates), text) { fallbacks += it }

    @Test
    fun out_of_vocabulary_words_keep_a_segment_with_a_vowel() {
        val words = listOf(
            "Remutaka",
            "Paekākāriki",
            "Whangārei",
            "Paraparaumu",
            "Ngāruawāhia",
            "Porirua",
            "zorvelkian",
            "Kāpiti",
        )
        for (word in words) {
            val segments = phonemize("$word road").split(" ")
            assertEquals(2, segments.size, "word=$word segments=$segments")
            assertTrue(segments[0].any { it in vowels }, "word=$word got=${segments[0]}")
            assertEquals(road, segments[1], "word=$word")
        }
    }

    @Test
    fun known_words_are_left_unchanged_and_not_logged() {
        val fallbacks = mutableListOf<Int>()

        assertEquals(road, phonemize("road", fallbacks = fallbacks))
        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun fallback_reports_only_the_word_length() {
        val fallbacks = mutableListOf<Int>()

        phonemize("Remutaka road", fallbacks = fallbacks)

        assertEquals(listOf("Remutaka".length), fallbacks)
    }

    @Test
    fun trailing_punctuation_phonemis_emitted_is_kept_after_the_fallback() {
        val result = phonemize("Remutaka.")

        assertTrue(result.endsWith("."), "got=$result")
        assertTrue(result.dropLast(1).any { it in vowels }, "got=$result")
    }

    @Test
    fun possessive_ends_in_z_after_the_stem() {
        val result = phonemize("Remutaka's road")

        val segments = result.split(" ")
        assertEquals(2, segments.size, "segments=$segments")
        assertTrue(segments[0].endsWith("z"), "got=${segments[0]}")
    }

    @Test
    fun digits_with_no_letter_to_sound_are_spelled_out() {
        val result = phonemize("7 road")

        assertTrue(result.startsWith("sɛvən"), "got=$result")
    }

    @Test
    fun words_that_phonemis_did_not_locate_are_each_checked() {
        val result = phonemize("Remutaka road", locates = false)

        val segments = result.split(" ")
        assertEquals(2, segments.size, "segments=$segments")
        assertTrue(segments[0].any { it in vowels }, "got=${segments[0]}")
        assertEquals(road, segments[1])
    }

    @Test
    fun kokoro_diphthong_symbols_count_as_a_vowel() {
        // en-gb phonemis writes diphthongs as capitals (Q = əʊ, W = aʊ). "road" and "town" must not fall back.
        val fallbacks = mutableListOf<Int>()
        val known = mapOf("road" to "ɹˈQd", "town" to "tˈWn")

        assertEquals("ɹˈQd tˈWn", phonemize("road town", known = known, fallbacks = fallbacks))
        assertTrue(fallbacks.isEmpty(), "fallbacks=$fallbacks")
    }
}
