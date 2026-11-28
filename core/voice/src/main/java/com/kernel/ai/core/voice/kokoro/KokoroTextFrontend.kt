package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.EnglishSpeechTextNormaliser
import com.kernel.ai.core.voice.PronunciationLexicon
import com.kernel.ai.core.voice.SpeechCardText
import java.text.Normalizer

/** Text preparation for the Kokoro phonemiser, applied after [SpeechCardText.speakable] and number normalisation. */
internal object KokoroTextFrontend {
    /**
     * Normalised text for [locale]'s phonemiser. The pronunciation lexicon's respellings apply before number
     * normalisation, as they do for Sherpa (#2175). Macron vowels then fold to plain ones: phonemis drops words with
     * macrons, but its lexicon knows the plain spellings (#2175). en-gb expands contractions because the en-gb lexicon
     * reads "I'll" as "ˌIl" (ill), so British voices said "I remind you" for "I'll remind you" (#2116, #2117). en-us
     * keeps its lexicon's reading, which the owner judged correct, so its text is unchanged.
     */
    fun phonemizerInput(text: String, locale: String): String {
        val spoken = foldMacrons(PronunciationLexicon.apply(SpeechCardText.speakable(text)))
        val normalised = separateJoinedWords(EnglishSpeechTextNormaliser.normalize(spoken))
        return if (locale == NativeKokoroPhonemizer.LOCALE_EN_GB) expandContractions(normalised) else normalised
    }

    /**
     * Makes each piece of a hyphen- or slash-joined token its own word. phonemis drops the pieces it does not
     * know, such as "ee" in the respelling "Pore-ee-roo-uh", and keeps the rest, so only a word boundary lets the
     * fallback see each piece (#2175).
     */
    internal fun separateJoinedWords(text: String): String = text.replace(JOINED_PIECES, " ")

    /** phonemis drops words with macron vowels (#2175); the plain spelling is what its lexicon knows. */
    internal fun foldMacrons(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .replace(MACRON_VOWEL) { PLAIN_VOWEL.getValue(it.value[0]).toString() }

    private val JOINED_PIECES = Regex("(?<=\\p{L})[-\u2013/](?=\\p{L})")
    private val MACRON_VOWEL = Regex("[āēīōūĀĒĪŌŪ]")
    private val PLAIN_VOWEL = mapOf(
        'ā' to 'a', 'ē' to 'e', 'ī' to 'i', 'ō' to 'o', 'ū' to 'u',
        'Ā' to 'A', 'Ē' to 'E', 'Ī' to 'I', 'Ō' to 'O', 'Ū' to 'U',
    )
    private val WILL = Regex("\\b([A-Za-z]+)['\u2019]ll\\b")
    private val HAVE = Regex("\\b([A-Za-z]+)['\u2019]ve\\b")
    private val ARE = Regex("\\b([A-Za-z]+)['\u2019]re\\b")
    private val AM = Regex("\\bI['\u2019]m\\b")
    private val CANNOT = Regex("\\bcan['\u2019]t\\b", RegexOption.IGNORE_CASE)
    private val WILL_NOT = Regex("\\bwon['\u2019]t\\b", RegexOption.IGNORE_CASE)
    private val SHALL_NOT = Regex("\\bshan['\u2019]t\\b", RegexOption.IGNORE_CASE)
    private val NOT = Regex("\\b([A-Za-z]+)n['\u2019]t\\b")

    internal fun expandContractions(text: String): String = text
        .replace(CANNOT) { match -> if (match.value[0].isUpperCase()) "Cannot" else "cannot" }
        .replace(WILL_NOT) { match -> if (match.value[0].isUpperCase()) "Will not" else "will not" }
        .replace(SHALL_NOT) { match -> if (match.value[0].isUpperCase()) "Shall not" else "shall not" }
        .replace(WILL) { match -> "${match.groupValues[1]} will" }
        .replace(HAVE) { match -> "${match.groupValues[1]} have" }
        .replace(ARE) { match -> "${match.groupValues[1]} are" }
        .replace(AM, "I am")
        .replace(NOT) { match -> "${match.groupValues[1]} not" }
}
