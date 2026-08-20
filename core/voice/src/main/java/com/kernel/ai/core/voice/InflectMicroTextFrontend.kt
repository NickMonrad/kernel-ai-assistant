package com.kernel.ai.core.voice

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/**
 * Kotlin port of the pinned Inflect v2 reference frontend's deterministic text preparation.
 *
 * This intentionally stops before eSpeak: the custom Sherpa JNI seam supplies the reference
 * Piper/eSpeak phonemes, while this class owns all runtime text expansion and Inflect-specific
 * pronunciation corrections.
 */
object InflectMicroTextFrontend {
    private val whitespace = Regex("\\s+")
    private val letterNames = mapOf(
        'A' to "ay", 'B' to "bee", 'C' to "see", 'D' to "dee", 'E' to "ee",
        'F' to "eff", 'G' to "gee", 'H' to "aitch", 'I' to "eye", 'J' to "jay",
        'K' to "kay", 'L' to "ell", 'M' to "em", 'N' to "en", 'O' to "oh",
        'P' to "pee", 'Q' to "cue", 'R' to "ar", 'S' to "ess", 'T' to "tee",
        'U' to "you", 'V' to "vee", 'W' to "double you", 'X' to "ex", 'Y' to "why",
        'Z' to "zee",
    )

    private val abbreviations = listOf(
        "Dr." to "doctor", "Mr." to "mister", "Mrs." to "missus", "Ms." to "miss",
        "Prof." to "professor", "St." to "saint", "vs." to "versus", "etc." to "et cetera",
        "e.g." to "for example", "i.e." to "that is",
    )

    private val wordOverrides = listOf(
        "Qwen3" to "Qwen three", "Qwen" to "Qwen", "PyTorch" to "pie torch",
        "SQLite" to "ess cue lite", "USB-C" to "you ess bee see",
        "RTX 3060" to "ar tee ex thirty sixty", "RTX 3090" to "ar tee ex thirty ninety",
        "RTX 4090" to "ar tee ex forty ninety", "RTX 5080" to "ar tee ex fifty eighty",
        "RTX 5090" to "ar tee ex fifty ninety",
    )

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

    fun normalize(text: String): String {
        var value = text
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('–', '-')
            .replace("—", ", ")
            .replace("…", "...")
            .replace("(", ", ").replace(")", ", ")
            .replace("[", ", ").replace("]", ", ")
            .replace("{", ", ").replace("}", ", ")
            .replace(whitespace, " ")
            .trim()

        wordOverrides.forEach { (source, replacement) ->
            value = value.replace(Regex("\\b${Regex.escape(source)}\\b"), replacement)
        }
        abbreviations.forEach { (source, replacement) ->
            value = value.replace(Regex("\\b${Regex.escape(source)}", RegexOption.IGNORE_CASE), replacement)
        }

        value = value.replace(Regex("\\b([A-Z])(?:\\.([A-Z]))+\\.")) { match ->
            match.value.filter { it in 'A'..'Z' }.toCharArray().joinToString(" ")
        }
        value = value.replace(
            Regex("\\b(apartment|apt\\.?|suite|unit|room|flight|extension|order|invoice|locker|aisle|gate)\\s+([A-Za-z]?\\d{1,4}[A-Za-z]?)\\b", RegexOption.IGNORE_CASE),
        ) { match -> "${match.groupValues[1]} ${expandIdentifierToken(match.groupValues[2])}" }
        value = value.replace(
            Regex("\\b(\\d{3})(?=\\s+(?:North|South|East|West)\\b)", RegexOption.IGNORE_CASE),
        ) { match -> expandDigits(match.groupValues[1]) }
        value = value.replace(Regex("\\$(\\d[\\d,]*(?:\\.\\d{1,2})?)")) { match -> expandMoney(match.groupValues[1]) }
        value = value.replace(Regex("\\b(0?[1-9]|1[0-2])/(0?[1-9]|[12]\\d|3[01])/(20\\d{2}|19\\d{2})\\b")) { match -> expandDate(match) }
        value = value.replace(Regex("\\b(\\d{1,2}):(\\d{2})\\s*([AaPp]\\.?\\s*[Mm]\\.?)?\\b")) { match -> expandTime(match) }
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
        value = value.replace(Regex("\\b\\d[\\d,]*\\b")) { match ->
            val raw = match.value.replace(",", "")
            if (raw.length >= 5 && !raw.startsWith("20")) expandDigits(raw) else numberToWords(raw.toLong())
        }
        value = value.replace(Regex("\\b[A-Z]{2,}\\b")) { match ->
            match.value.map { letterNames[it] ?: it.toString() }.joinToString(" ")
        }

        value = value.replace(Regex(",(?:\\s*,)+"), ",")
            .replace(Regex(",\\s*([.!?])"), "$1")
            .replace(Regex("\\s+([,;:.!?])"), "$1")
            .replace(Regex("([,;:.!?])(?=\\S)"), "$1 ")
        return value.replace(whitespace, " ").trim()
    }

    fun applyPhonemeOverrides(phonemes: String): String {
        var value = Normalizer.normalize(phonemes, Normalizer.Form.NFD)
        value = value.replace("sˈæskɐtʃˌuːən", "sɐskˈætʃəwən")
        value = value.replace("flʊɹɹˈɛsənt", "flʊˈɹɛsənt")
        return value.replace(whitespace, " ").trim()
    }

    private fun expandDigits(value: String): String =
        value.mapIndexed { index, digit ->
            if (digit == '0' && index > 0) "oh" else smallCardinals[digit - '0']
        }.joinToString(" ")

    private fun expandIdentifierToken(token: String): String {
        val match = Regex("^([A-Za-z]?)(\\d+)([A-Za-z]?)$").matchEntire(token) ?: return token
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        val suffix = match.groupValues[3]
        val pieces = buildList {
            if (prefix.isNotEmpty()) add(letterNames[prefix[0].uppercaseChar()]!!)
            add(if (digits.length == 3 || digits.startsWith('0')) expandDigits(digits) else numberToWords(digits.toLong()))
            if (suffix.isNotEmpty()) add(letterNames[suffix[0].uppercaseChar()]!!)
        }
        return pieces.joinToString(" ")
    }

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

    private fun numberToWords(value: Long, ordinal: Boolean = false): String {
        require(value >= 0) { "Only non-negative numbers are supported" }
        if (!ordinal) return cardinal(value)
        if (value < ordinalSmall.size) return ordinalSmall[value.toInt()]
        if (value < 100L) {
            val remainder = (value % 10).toInt()
            return if (remainder == 0) ordinalTens[(value / 10).toInt()] else "${tens[(value / 10).toInt()]} ${ordinalSmall[remainder]}"
        }
        val scale = largestScale(value)
        if (scale == 0) return "${cardinal(value / 100)} hundredth"
        val unit = scaleValue(scale)
        return if (value % unit == 0L) {
            "${cardinal(value / unit)} ${scaleOrdinals[scale]}"
        } else {
            "${cardinal(value / unit)} ${scales[scale]} ${numberToWords(value % unit, ordinal = true)}"
        }
    }

    private fun cardinal(value: Long): String {
        if (value < 20L) return smallCardinals[value.toInt()]
        if (value < 100L) {
            val remainder = (value % 10).toInt()
            return if (remainder == 0) tens[(value / 10).toInt()] else "${tens[(value / 10).toInt()]} ${smallCardinals[remainder]}"
        }
        val scale = largestScale(value)
        if (scale == 0) {
            val hundreds = value / 100
            val remainder = value % 100
            return if (remainder == 0L) "${cardinal(hundreds)} hundred" else "${cardinal(hundreds)} hundred ${cardinal(remainder)}"
        }
        val unit = scaleValue(scale)
        val major = value / unit
        val remainder = value % unit
        return if (remainder == 0L) "${cardinal(major)} ${scales[scale]}" else "${cardinal(major)} ${scales[scale]} ${cardinal(remainder)}"
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
