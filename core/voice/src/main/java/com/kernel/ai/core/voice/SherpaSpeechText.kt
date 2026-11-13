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
 * - Money and clock times were read mechanically (#1487): "$49.95" as "dollar forty nine
 *   point nine five" and "8:00" as "eight zero zero". Both are spelled out here.
 *
 * Everything else is left to eSpeak, which already reads minus signs, decimals, years,
 * ordinals, phone numbers and URLs: a wider normaliser (the Inflect frontend) broke
 * several of those. Only plain integers, dollar amounts and clock times are spelled out.
 */
internal object SherpaSpeechText {
    private const val TAG = "KernelAI"

    private val months = "January|February|March|April|May|June|July|August|September|October|November|December"
    private val day = "[1-9]|[12]\\d|3[01]"
    private val dayRange = Regex("""\b($day) ?(?:-|–|to) ?($day) ($months)\b""")
    private val dayMonth = Regex("""(?<![\d\-–] ?)\b($day) ($months)\b""")
    /** "5–10", "9-5": a short number range reads "five to ten" (a dash would become a pause). */
    private val numberRange = Regex("""(?<![\d.,:/\-–])(\d{1,3}) ?[-–] ?(\d{1,3})(?![\d.,:/\-–])""")
    private val citation = Regex("""\s*\[\d{1,3}]""")
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
        """(?<![\w.,:/$£€¥₹#+\-−])(?<!\d )([1-9]\d{0,2}(?:,\d{3}){1,2}|[1-9]\d{0,5})(?![\w%°:/]|[.,]\d|-\d| \d)""",
    )
    /** "$49.95", "$1,200", "$5": dollars with optional two-digit cents. */
    private val money = Regex("""(?<![\w$])\$([1-9]\d{0,2}(?:,\d{3}){1,2}|[1-9]\d{0,8}|0)(?:\.(\d{2}))?(?![\d.,]\d|\w)""")

    /** "8:00", "7:30 pm", "14:05": a clock time, not part of a longer number or ratio. */
    private val clockTime = Regex(
        """(?<![\w.:/])([01]?\d|2[0-3]):([0-5]\d)(?:\s?([AaPp])\.?[Mm]\.?)?(?![\w:]|[.,]\d)""",
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
        value = dayRange.replace(value) { m ->
            "${ordinalDay(m.groupValues[1])} to ${ordinalDay(m.groupValues[2])} of ${m.groupValues[3]}"
        }
        value = dayMonth.replace(value) { m -> "${ordinalDay(m.groupValues[1])} of ${m.groupValues[2]}" }
        value = numberRange.replace(value, "$1 to $2")
        value = citation.replace(value, "")
        repeat(3) { // nested brackets, innermost first
            value = brackets.replace(value) { m -> if (m.groupValues[1].isBlank()) "" else ", ${m.groupValues[1]}," }
        }
        value = dashes.replace(value, ", ")
        value = money.replace(value) { m -> spokenMoney(m.groupValues[1], m.groupValues[2]) }
        value = clockTime.replace(value) { m -> spokenTime(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        value = plainInteger.replace(value) { m ->
            val raw = m.groupValues[1]
            val n = raw.replace(",", "").toInt()
            if (!raw.contains(',') && raw.length == 4 && n in 1100..2099) raw else words(n)
        }
        value = value.replace(Regex(""",\s*,+"""), ",").replace(Regex("""\s+([,.;:!?])"""), "$1")
            .replace(Regex(""",([.!?])"""), "$1").replace(Regex("""([.!?;:]),"""), "$1")
            .replace(Regex("""\s+"""), " ").trim().removePrefix(",").trim()
        if (chunkEnd && !terminalPause.containsMatchIn(value)) {
            value = if (trailingSoftPause.containsMatchIn(value)) {
                trailingSoftPause.replace(value) { m -> ".${m.groupValues[1]}" }
            } else {
                "$value."
            }
        }
        return value
    }

    private fun spokenMoney(rawDollars: String, rawCents: String): String {
        val dollars = rawDollars.replace(",", "").toInt()
        val cents = rawCents.ifEmpty { "0" }.toInt()
        val dollarWords = "${words(dollars)} ${if (dollars == 1) "dollar" else "dollars"}"
        if (cents == 0) return dollarWords
        val centWords = "${words(cents)} ${if (cents == 1) "cent" else "cents"}"
        return if (dollars == 0) centWords else "$dollarWords and $centWords"
    }

    private fun spokenTime(rawHour: String, rawMinute: String, meridiem: String): String {
        val hour = rawHour.toInt()
        val minute = rawMinute.toInt()
        // "P M" as separate capitals: Cori reads "p.m." as one rushed syllable (#1487 device check).
        val suffix = if (meridiem.isEmpty()) "" else " ${meridiem.uppercase()} M"
        val minuteWords = when {
            minute == 0 && hour > 12 && meridiem.isEmpty() -> "hundred"
            minute == 0 -> "o'clock"
            minute < 10 -> "oh ${words(minute)}"
            else -> words(minute)
        }
        return "${words(hour)} $minuteWords$suffix"
    }

    private fun ordinalDay(raw: String): String = "the $raw${ordinalSuffix(raw.toInt())}"

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
