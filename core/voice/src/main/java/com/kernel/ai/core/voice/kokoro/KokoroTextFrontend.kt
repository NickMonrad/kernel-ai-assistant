package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.EnglishSpeechTextNormaliser
import com.kernel.ai.core.voice.SpeechCardText

/** Text preparation for the Kokoro phonemiser, applied after [SpeechCardText.speakable] and number normalisation. */
internal object KokoroTextFrontend {
    /**
     * Normalised text for [locale]'s phonemiser. en-gb expands contractions because the en-gb lexicon reads
     * "I'll" as "ˌIl" (ill), so British voices said "I remind you" for "I'll remind you" (#2116, #2117). en-us
     * keeps its lexicon's reading, which the owner judged correct, so its text is unchanged.
     */
    fun phonemizerInput(text: String, locale: String): String {
        val normalised = EnglishSpeechTextNormaliser.normalize(SpeechCardText.speakable(text))
        return if (locale == NativeKokoroPhonemizer.LOCALE_EN_GB) expandContractions(normalised) else normalised
    }

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
