package com.kernel.ai.core.voice

import android.util.Log

/**
 * Light text preparation for the Sherpa voices (Piper, Kokoro) before synthesis (#1751).
 *
 * Measured with the Cori voice (sherpa-onnx 1.13.0, the app's speed and gain; Whisper
 * transcripts over five renders each, because Piper's output varies between renders):
 * - Digits are read unreliably: "126 days from now (18 weeks) — Sunday, …" came out as
 *   "Wanhinu 26 days" or "one in 96 days" in 5 of 5 renders. With the brackets and dash as
 *   commas, 3 of 5 were right; with the number also in words, 5 of 5.
 * - Text with no final punctuation can end abruptly ("It's 8:31 am").
 * - "14 March" was heard as "forty March"; "the 14th of March" was not.
 *
 * Everything else is left to eSpeak, which already reads minus signs, decimals, years,
 * times, ordinals, phone numbers and URLs: a wider normaliser (the Inflect frontend) broke
 * several of those. Only plain integers are spelled out.
 */
internal object SherpaSpeechText {
    private const val TAG = "KernelAI"

    private val months = "January|February|March|April|May|June|July|August|September|October|November|December"
    private val dayMonth = Regex("""\b([1-9]|[12]\d|3[01]) ($months)\b""")
    private val brackets = Regex("""\s*[(\[]\s*([^()\[\]]*?)\s*[)\]]""")
    private val dashes = Regex("""\s*[—–]\s*""")

    /**
     * A plain integer: not part of a decimal, time, version, phone number, URL, money,
     * range, ordinal, percentage or unit-glued token, not next to another digit group
     * (spaced phone numbers such as "021 123 4567"), and with no leading zero. Comma
     * groups ("15,000") count. Four-digit numbers from 1100 to 2099 are left as they are,
     * so eSpeak reads them as years.
     */
    private val plainInteger = Regex(
        """(?<![\w.,:/$£€#+\-])(?<!\d )([1-9]\d{0,2}(?:,\d{3}){1,2}|[1-9]\d{0,5})(?![\w%°:/]|[.,]\d|-\d| \d)""",
    )
    private val terminalPause = Regex("""[.!?]["')\]]*$""")
    private val trailingSoftPause = Regex("""[,;:]+(["')\]]*)$""")

    private val ones = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen",
    )
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /**
     * @param chunkEnd true for one-shot text and the final streaming chunk: a trailing
     *   comma or colon becomes a full stop and a missing final pause is added. Earlier
     *   streaming chunks already end where the chunker chose.
     */
    fun prepare(text: String, chunkEnd: Boolean = true): String = try {
        prepareUnchecked(text, chunkEnd)
    } catch (e: RuntimeException) {
        Log.w(TAG, "SherpaSpeechText: preparation failed; speaking the text as it is", e)
        text
    }

    internal fun prepareUnchecked(text: String, chunkEnd: Boolean): String {
        var value = text.trim()
        if (value.isEmpty()) return value
        value = dayMonth.replace(value) { m -> "the ${m.groupValues[1]}${ordinalSuffix(m.groupValues[1].toInt())} of ${m.groupValues[2]}" }
        value = brackets.replace(value) { m -> if (m.groupValues[1].isBlank()) "" else ", ${m.groupValues[1]}," }
        value = dashes.replace(value, ", ")
        value = plainInteger.replace(value) { m ->
            val raw = m.groupValues[1]
            val n = raw.replace(",", "").toInt()
            if (!raw.contains(',') && raw.length == 4 && n in 1100..2099) raw else words(n)
        }
        value = value.replace(Regex(""",\s*,+"""), ",").replace(Regex("""\s+([,.;:!?])"""), "$1")
            .replace(Regex(""",([.!?])"""), "$1").replace(Regex("""\s+"""), " ").trim()
        if (chunkEnd && !terminalPause.containsMatchIn(value)) {
            value = if (trailingSoftPause.containsMatchIn(value)) {
                trailingSoftPause.replace(value) { m -> ".${m.groupValues[1]}" }
            } else {
                "$value."
            }
        }
        return value
    }

    private fun ordinalSuffix(day: Int): String = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    /** British-style cardinal words for 0..999,999,999 ("one hundred and twenty six"). */
    internal fun words(n: Int): String {
        require(n in 0..999_999_999)
        if (n < 20) return ones[n]
        if (n < 100) return tens[n / 10] + if (n % 10 != 0) " ${ones[n % 10]}" else ""
        if (n < 1000) {
            val rest = n % 100
            return "${ones[n / 100]} hundred" + if (rest != 0) " and ${words(rest)}" else ""
        }
        val (scale, name) = if (n < 1_000_000) 1000 to "thousand" else 1_000_000 to "million"
        val rest = n % scale
        val joiner = when {
            rest == 0 -> ""
            rest < 100 -> " and "
            else -> " "
        }
        return "${words(n / scale)} $name$joiner${if (rest != 0) words(rest) else ""}"
    }
}
