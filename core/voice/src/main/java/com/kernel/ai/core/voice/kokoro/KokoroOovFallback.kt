package com.kernel.ai.core.voice.kokoro

import android.util.Log
import com.kernel.ai.core.voice.PronunciationLexicon

/**
 * Supplies phonemes for words phonemis silently drops (#2175). phonemis gives a word outside its lexicon no
 * phonemes and substitutes nothing, so Kokoro never says it. A dropped word gets English letter-to-sound
 * phonemes (macrons folded, common NZ English vowels); if that yields nothing, the word is spelled out letter by
 * letter. Only the word's length is logged when a fallback is used.
 */
internal object KokoroOovFallback {
    private const val TAG = "KokoroOovFallback"
    private const val SPAN_FIELDS = 4
    private const val VOWEL_LETTERS = "aeiou"

    /**
     * Phoneme characters that count as a vowel nucleus. A located word with none was dropped. Kokoro's en-gb
     * phonemis writes some diphthongs as capitals (Q in "road", W in "town"); without them common words re-spell.
     */
    private const val VOWEL_PHONEMES = "aeiouAIOQWYɑæʌəɛɪɔʊɜɚᵻɒɐ"

    private val WHITESPACE = Regex("\\s+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val POSSESSIVE = Regex("['\u2019]s$")
    private val QUOTES = Regex("[\"'\u2018\u2019\u201C\u201D]")
    private val CAMEL_CASE = Regex("(\\p{Ll})(\\p{Lu})")
    private val TRAILING_PUNCTUATION = Regex("[^\\p{L}\\p{N}]+$")

    /** Digraphs and trigraphs, longest first; matched at each position before single letters. */
    private val MULTI = listOf(
        "tion" to "ʃən", "sion" to "ʒən", "tch" to "ʧ", "dge" to "ʤ", "igh" to "aɪ",
        "ch" to "ʧ", "sh" to "ʃ", "th" to "θ", "ph" to "f", "wh" to "w", "ck" to "k", "ng" to "ŋ",
        "qu" to "kw", "gh" to "",
        "ee" to "i", "ea" to "i", "ie" to "i", "oo" to "u", "ew" to "u", "ou" to "aʊ", "ow" to "aʊ",
        "oi" to "ɔɪ", "oy" to "ɔɪ", "ai" to "eɪ", "ay" to "eɪ", "ei" to "eɪ", "au" to "ɔ", "aw" to "ɔ",
        "oa" to "oʊ", "ar" to "ɑɹ", "or" to "ɔɹ", "er" to "ɚ", "ir" to "ɚ", "ur" to "ɚ",
    )
    private val SHORT_VOWEL = mapOf('a' to "æ", 'e' to "ɛ", 'i' to "ɪ", 'o' to "ɑ", 'u' to "ʌ")
    private val LONG_VOWEL = mapOf('a' to "eɪ", 'e' to "i", 'i' to "aɪ", 'o' to "oʊ", 'u' to "ju")
    private val LETTER_NAMES = mapOf(
        'a' to "eɪ", 'b' to "bi", 'c' to "si", 'd' to "di", 'e' to "i", 'f' to "ɛf", 'g' to "ʤi", 'h' to "eɪʧ",
        'i' to "aɪ", 'j' to "ʤeɪ", 'k' to "keɪ", 'l' to "ɛl", 'm' to "ɛm", 'n' to "ɛn", 'o' to "oʊ", 'p' to "pi",
        'q' to "kju", 'r' to "ɑɹ", 's' to "ɛs", 't' to "ti", 'u' to "ju", 'v' to "vi", 'w' to "dʌbəlju",
        'x' to "ɛks", 'y' to "waɪ", 'z' to "zi",
    )
    private val DIGIT_NAMES = mapOf(
        '0' to "zɪɹoʊ", '1' to "wʌn", '2' to "tu", '3' to "θɹi", '4' to "fɔɹ", '5' to "faɪv", '6' to "sɪks",
        '7' to "sɛvən", '8' to "eɪt", '9' to "naɪn",
    )

    /**
     * Phonemes for [text] with every word phonemis dropped filled in. Words phonemis did not locate are
     * phonemised one at a time so no word is skipped. [onFallback] receives the length of each word that needed
     * the fallback.
     */
    fun phonemize(
        phonemizer: KokoroPhonemizer,
        text: String,
        onFallback: (wordLength: Int) -> Unit = ::logFallback,
    ): String {
        val raw = phonemizer.phonemize(text)
        if (raw.wordSpans.isNotEmpty()) return fillLocated(text, raw, { retryPhonemes(phonemizer, it) }, onFallback)
        return text.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ") { word ->
            fillWord(word, phonemizer.phonemize(word).phonemes, { retryPhonemes(phonemizer, it) }, onFallback)
        }
    }

    private fun fillLocated(
        text: String,
        raw: PhonemizedText,
        retry: (String) -> String?,
        onFallback: (Int) -> Unit,
    ): String {
        val input = text.codePoints().toArray()
        val phonemes = raw.phonemes.codePoints().toArray()
        val out = StringBuilder(raw.phonemes.length + 16)
        var cursor = 0
        for (base in raw.wordSpans.indices step SPAN_FIELDS) {
            val start = raw.wordSpans[base + 2].coerceIn(cursor, phonemes.size)
            val end = (start + raw.wordSpans[base + 3]).coerceIn(start, phonemes.size)
            out.append(String(phonemes, cursor, start - cursor))
            val wordStart = raw.wordSpans[base].coerceIn(0, input.size)
            val wordEnd = (wordStart + raw.wordSpans[base + 1]).coerceIn(wordStart, input.size)
            val word = String(input, wordStart, wordEnd - wordStart)
            out.append(fillWord(word, String(phonemes, start, end - start), retry, onFallback))
            cursor = end
        }
        out.append(String(phonemes, cursor, phonemes.size - cursor))
        return out.toString()
    }

    /**
     * [segment] unchanged when it already has a vowel or the word has nothing speakable. Otherwise phonemis is retried
     * on the word with quotes stripped and CamelCase split, then letter-to-sound. A word whose spelled form is blank
     * keeps phonemis's segment, so a standalone "'s" still says "s".
     */
    private fun fillWord(
        word: String,
        segment: String,
        retry: (String) -> String?,
        onFallback: (Int) -> Unit,
    ): String {
        if (!hasSpeakableCharacter(word) || segment.any { it in VOWEL_PHONEMES }) return segment
        // Keep the punctuation phonemis emitted for this word, so pauses at sentence ends survive.
        val punctuation = segment.filter { !it.isLetter() && !it.isWhitespace() }
        retry(word)?.let { return it.trim() + punctuation }
        val spoken = spokenForm(word)
        if (spoken.isBlank()) return segment
        onFallback(word.length)
        return spoken + punctuation
    }

    /** phonemis's phonemes for [word] after quote removal, CamelCase splitting and trailing punctuation removal; null if that changes nothing or has no vowel. */
    private fun retryPhonemes(phonemizer: KokoroPhonemizer, word: String): String? {
        val cleaned = word.replace(QUOTES, "").replace(CAMEL_CASE, "$1 $2").replace(TRAILING_PUNCTUATION, "")
        if (cleaned.isBlank() || cleaned == word) return null
        return phonemizer.phonemize(cleaned).phonemes.takeIf { phonemes -> phonemes.any { it in VOWEL_PHONEMES } }
    }

    private fun hasSpeakableCharacter(word: String): Boolean =
        PronunciationLexicon.fold(word).any { it in 'a'..'z' || it in '0'..'9' }

    private fun spokenForm(word: String): String {
        val folded = PronunciationLexicon.fold(word).lowercase()
        val possessive = POSSESSIVE.containsMatchIn(folded)
        val stem = if (possessive) POSSESSIVE.replace(folded, "") else folded
        val parts = stem.split(NON_ALNUM).filter { it.isNotEmpty() }
        return parts.mapIndexed { index, part ->
            val possessiveSuffix = if (possessive && index == parts.lastIndex) "z" else ""
            letterToSound(part).ifEmpty { spellOut(part) } + possessiveSuffix
        }.joinToString(" ")
    }

    /**
     * Simple English letter-to-sound for a lowercase a-z part. Returns "" for a part with digits so the caller
     * spells it out. Known limits: no stress marks, no real morphology, no exceptions beyond the rules here.
     */
    private fun letterToSound(part: String): String {
        if (part.any { it !in 'a'..'z' }) return ""
        val silentE = part.length > 2 && part.last() == 'e' &&
            part[part.length - 2] !in VOWEL_LETTERS && part.dropLast(1).any { it in VOWEL_LETTERS }
        val body = if (silentE) part.dropLast(1) else part
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val multi = MULTI.firstOrNull { body.startsWith(it.first, i) }
            if (multi != null) {
                out.append(multi.second)
                i += multi.first.length
                continue
            }
            val doubled = i + 1 < body.length && body[i + 1] == body[i] && body[i] !in VOWEL_LETTERS
            out.append(letterSound(body, i, silentE))
            i += if (doubled) 2 else 1
        }
        return out.toString()
    }

    private fun letterSound(body: String, i: Int, silentE: Boolean): String {
        val c = body[i]
        val next = body.getOrNull(i + 1)
        val last = i == body.lastIndex
        val nextIsVowel = next != null && next in VOWEL_LETTERS
        return when (c) {
            in VOWEL_LETTERS -> when {
                silentE && i == body.length - 2 && next != null && !nextIsVowel -> LONG_VOWEL.getValue(c)
                c == 'a' && last -> "ə"
                else -> SHORT_VOWEL.getValue(c)
            }
            'y' -> if (i == 0 || nextIsVowel) "j" else "i"
            'c' -> if (next != null && next in "eiy") "s" else "k"
            'g' -> if (next != null && next in "eiy") "ʤ" else "ɡ"
            'h' -> if (last && i > 0 && body[i - 1] in VOWEL_LETTERS) "" else "h"
            's' -> if (i > 0 && body[i - 1] in VOWEL_LETTERS && nextIsVowel) "z" else "s"
            'x' -> "ks"
            'q' -> "k"
            'j' -> "ʤ"
            'r' -> "ɹ"
            else -> c.toString()
        }
    }

    private fun spellOut(part: String): String =
        part.mapNotNull { LETTER_NAMES[it] ?: DIGIT_NAMES[it] }.joinToString(" ")

    private fun logFallback(wordLength: Int) {
        Log.w(TAG, "phonemis dropped a word of $wordLength characters; used letter-to-sound fallback")
    }
}
