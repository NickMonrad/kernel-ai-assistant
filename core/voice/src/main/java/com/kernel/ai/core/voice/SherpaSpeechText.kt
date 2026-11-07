package com.kernel.ai.core.voice

/**
 * Text preparation for the Sherpa voices (Piper, Kokoro) before synthesis (#1751).
 *
 * Without it, Piper's eSpeak front end mangles digits ("126 days" came out as "Wuhanau 26
 * days" with the Cori voice) and clips the last word when the text has no final punctuation
 * ("It's 8:31 am"). The Inflect engine already uses [InflectMicroTextFrontend.normalize] for
 * numbers, dates, times, money and abbreviations; the Sherpa voices now share it.
 */
internal object SherpaSpeechText {
    private val terminalPause = Regex("""[.!?,;:]["')\]]*$""")
    private val months = "January|February|March|April|May|June|July|August|September|October|November|December"

    /** "14 March" → "the 14th of March": Cori's "fourteen March" was heard as "forty March". */
    private val dayMonth = Regex("""\b([1-9]|[12]\d|3[01]) ($months)\b""")

    fun prepare(text: String): String {
        val dated = dayMonth.replace(text) { m ->
            val day = m.groupValues[1].toInt()
            val suffix = when {
                day in 11..13 -> "th"
                day % 10 == 1 -> "st"
                day % 10 == 2 -> "nd"
                day % 10 == 3 -> "rd"
                else -> "th"
            }
            "the $day$suffix of ${m.groupValues[2]}"
        }
        val normalized = InflectMicroTextFrontend.normalize(
            dated,
            spellUppercaseWords = false,
            longNumbersAsDigits = false,
        )
        if (normalized.isBlank()) return normalized
        return if (terminalPause.containsMatchIn(normalized)) normalized else "$normalized."
    }
}
