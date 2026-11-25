package com.kernel.ai.core.voice

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/** Language-level speech text normalisation (#2115). Engines choose whether to run it; they do not copy it. */
interface SpeechTextNormaliser {
    /** Expects whitespace already collapsed to single spaces. Returns text with digit runs spelled out as words. */
    fun normalize(text: String): String
}

/**
 * English numbers, money, dates, clock times and ordinals as words. Shared by Inflect Micro
 * ([InflectMicroTextFrontend], which runs it between its own word overrides and its acronym and punctuation
 * steps) and the Kokoro ExecuTorch lane (after [SpeechCardText.speakable]). Engine-specific steps stay in each
 * engine: Inflect's word overrides, identifiers, acronym letters and punctuation.
 *
 * Output: "1999" → "one thousand nine hundred and ninety nine", "2025" → "two thousand and twenty five",
 * "1/21/2026" → "January twenty first two thousand and twenty six", "$49.95" → "forty nine dollars and ninety five
 * cents", "7:05 p.m." → "seven oh five p m", "3.75" → "three point seven five". Hyphens and minus signs are left
 * as they are.
 */
object EnglishSpeechTextNormaliser : SpeechTextNormaliser {
    private val smallCardinals = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen",
    )
    private val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ordinalSmall = arrayOf(
        "zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth",
    )
    private val ordinalTens = arrayOf("", "", "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth")
    private val scales = arrayOf("", "thousand", "million", "billion", "trillion", "quadrillion")
    private val scaleOrdinals = arrayOf("", "thousandth", "millionth", "billionth", "trillionth", "quadrillionth")

    override fun normalize(text: String): String {
        var value = text
        value = value.replace(Regex("\\$(\\d[\\d,]*(?:\\.\\d{1,2})?)")) { match -> expandMoney(match.groupValues[1]) }
        value = value.replace(Regex("\\b(0?[1-9]|1[0-2])/(0?[1-9]|[12]\\d|3[01])/(20\\d{2}|19\\d{2})\\b")) { match -> expandDate(match) }
        value = value.replace(Regex("\\b(\\d{1,2}):(\\d{2})(?:\\s*([AaPp]\\.?\\s*[Mm]\\.?))?\\b")) { match -> expandTime(match) }
        value = value.replace(Regex("\\b(\\d{1,2})\\s*([AaPp]\\.?\\s*[Mm]\\.?)\\b")) { match ->
            val suffix = lettersOnly(match.groupValues[2]).lowercase(Locale.US)
            "${numberToWords(match.groupValues[1].toLong())} ${suffix.map { it.toString() }.joinToString(" ")}"
        }
        value = value.replace(Regex("\\b(\\d{3})-(\\d{4})\\b")) { match ->
            "${expandDigits(match.groupValues[1])}, ${expandDigits(match.groupValues[2])}"
        }
        value = value.replace(Regex("\\b\\d+(?:\\.\\d+){2,}\\b")) { match ->
            match.value.split('.').joinToString(" point ") { numberToWords(it.toLong()) }
        }
        value = value.replace(Regex("\\b(\\d+)\\.(\\d+)\\b")) { match ->
            "${numberToWords(match.groupValues[1].toLong())} point ${expandDigits(match.groupValues[2])}"
        }
        value = value.replace(Regex("\\b(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)) { match ->
            numberToWords(match.groupValues[1].toLong(), ordinal = true)
        }
        // #1486 grouped-digit identifiers: only runs of THREE OR MORE space-separated integer
        // groups are treated as a digit-sequence (e.g. "1300 555 019"). Two adjacent integers
        // such as "3 20" or "2 500" are ordinary quantities and must keep cardinal semantics.
        value = value.replace(Regex("\\b(\\d+(?:\\s+\\d+){2,})\\b")) { match ->
            match.value.split(Regex("\\s+")).joinToString(", ") { group ->
                group.map { smallCardinals[it - '0'] }.joinToString(" ")
            }
        }
        value = value.replace(Regex("\\b\\d[\\d,]*\\b")) { match ->
            val raw = match.value.replace(",", "")
            if (raw.length >= 5 && !raw.startsWith("20")) expandDigits(raw) else numberToWords(raw.toLong())
        }
        return value
    }

    internal fun expandDigits(value: String): String =
        value.mapIndexed { index, digit ->
            if (digit == '0' && index > 0) "oh" else smallCardinals[digit - '0']
        }.joinToString(" ")

    private fun expandMoney(raw: String): String {
        val parts = raw.replace(",", "").split('.', limit = 2)
        val dollars = parts[0].toLong()
        val result = mutableListOf(numberToWords(dollars), if (dollars == 1L) "dollar" else "dollars")
        if (parts.size == 2) {
            val cents = parts[1].padEnd(2, '0').take(2).toLong()
            if (cents != 0L) result += listOf("and", numberToWords(cents), if (cents == 1L) "cent" else "cents")
        }
        return result.joinToString(" ")
    }

    private fun expandDate(match: MatchResult): String {
        val month = match.groupValues[1].toInt()
        val day = match.groupValues[2].toInt()
        val year = match.groupValues[3].toInt()
        return try {
            LocalDate.of(year, month, day)
            val monthName = LocalDate.of(year, month, 1).month.name.lowercase().replaceFirstChar { it.titlecase() }
            "$monthName ${numberToWords(day.toLong(), ordinal = true)} ${numberToWords(year.toLong())}"
        } catch (_: DateTimeException) {
            match.value
        }
    }

    private fun expandTime(match: MatchResult): String {
        val hour = match.groupValues[1].toLong()
        val minute = match.groupValues[2].toInt()
        val suffix = lettersOnly(match.groupValues[3]).lowercase(Locale.US)
        val pieces = mutableListOf(numberToWords(hour))
        if (minute == 0) pieces += "o clock"
        else if (minute < 10) pieces += listOf("oh", numberToWords(minute.toLong()))
        else pieces += numberToWords(minute.toLong())
        if (suffix.isNotEmpty()) pieces += suffix.map { it.toString() }
        return pieces.joinToString(" ")
    }

    private fun lettersOnly(value: String): String = value.filter { it.isLetter() }

    internal fun numberToWords(value: Long, ordinal: Boolean = false): String {
        require(value >= 0) { "Only non-negative numbers are supported" }
        if (!ordinal) return cardinal(value)
        if (value < ordinalSmall.size) return ordinalSmall[value.toInt()]
        if (value < 100L) {
            val remainder = (value % 10).toInt()
            return if (remainder == 0) {
                ordinalTens[(value / 10).toInt()]
            } else {
                "${tens[(value / 10).toInt()]} ${ordinalSmall[remainder]}"
            }
        }
        val scale = largestScale(value)
        if (scale == 0) {
            val hundreds = value / 100
            val remainder = value % 100
            return if (remainder == 0L) {
                "${cardinal(hundreds)} hundredth"
            } else {
                "${cardinal(hundreds)} hundred and ${numberToWords(remainder, ordinal = true)}"
            }
        }
        val unit = scaleValue(scale)
        val major = value / unit
        val remainder = value % unit
        return if (remainder == 0L) {
            "${cardinal(major)} ${scaleOrdinals[scale]}"
        } else {
            val conjunction = if (remainder < 100L) " and " else " "
            "${cardinal(major)} ${scales[scale]}$conjunction${numberToWords(remainder, ordinal = true)}"
        }
    }

    private fun cardinal(value: Long): String {
        if (value < 20L) return smallCardinals[value.toInt()]
        if (value < 100L) {
            val remainder = (value % 10).toInt()
            return if (remainder == 0) {
                tens[(value / 10).toInt()]
            } else {
                "${tens[(value / 10).toInt()]} ${smallCardinals[remainder]}"
            }
        }
        val scale = largestScale(value)
        if (scale == 0) {
            val hundreds = value / 100
            val remainder = value % 100
            return if (remainder == 0L) {
                "${cardinal(hundreds)} hundred"
            } else {
                "${cardinal(hundreds)} hundred and ${cardinal(remainder)}"
            }
        }
        val unit = scaleValue(scale)
        val major = value / unit
        val remainder = value % unit
        return if (remainder == 0L) {
            "${cardinal(major)} ${scales[scale]}"
        } else {
            val conjunction = if (remainder < 100L) " and " else " "
            "${cardinal(major)} ${scales[scale]}$conjunction${cardinal(remainder)}"
        }
    }

    private fun largestScale(value: Long): Int =
        scales.indices.reversed().firstOrNull { it > 0 && value >= scaleValue(it) } ?: 0

    private fun scaleValue(index: Int): Long = 10L.pow(index * 3)

    private fun Long.pow(exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result = Math.multiplyExact(result, 10L) }
        return result
    }
}
