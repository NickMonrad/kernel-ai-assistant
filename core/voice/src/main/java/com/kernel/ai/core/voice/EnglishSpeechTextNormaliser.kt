package com.kernel.ai.core.voice

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/**
 * Shared English speech text normalisation (#2115), used by Inflect Micro ([InflectMicroTextFrontend], between its
 * word overrides and its acronym and punctuation steps) and the Kokoro ExecuTorch lane (after
 * [SpeechCardText.speakable]). Both voices get the same spoken forms; engine-specific steps stay in each engine.
 *
 * Output examples:
 * - Minus signs: "-5°C" → "minus five degrees", "−5" (U+2212) → "minus five". A hyphen or minus is a sign only when a
 *   digit follows it and no letter or digit comes before it, so "203-0678" and "2026-11-26" keep their dashes.
 * - Ranges: "3-5 days" → "three to five days", "5–10 km" → "five to ten kilometres".
 * - Years: a four-digit 1100–2099 with no comma is a year only after a year cue ("in", "since", "of", a month name,
 *   "founded", …), the shape rule [SherpaSpeechText] uses. "Founded in 1999" → "founded in nineteen ninety nine";
 *   "2005" after "in" → "two thousand and five"; "1900" → "nineteen hundred"; "1990s" → "nineteen nineties".
 *   Elsewhere a bare 1300 is a cardinal: "1300 people" → "one thousand three hundred people".
 * - Big numbers: "15,000" → "fifteen thousand", "1,000,000" → "one million", "1,000.50" → "one thousand point five".
 * - Money: "$49.95" → "forty nine dollars and ninety five cents", "NZ$20" → "twenty New Zealand dollars",
 *   "$12.345" → "twelve point three four five dollars".
 * - Dates and times: "1/21/2026" and "2026-11-26" → "January twenty first twenty twenty six" and
 *   "November twenty sixth twenty twenty six"; "7:05 p.m." → "seven oh five p m".
 * - Glued units and mixed tokens: "5km" → "five kilometres", "4B" → "four bee", "5G" → "five gee",
 *   "NZ123" → "en zee one two three", "1080p" → "one thousand and eighty pee".
 * - Digit strings: phone-style groups, grouped identifiers ("1300 555 019") and leading-zero or very long runs are
 *   read digit by digit, so no number is lost to a `Long` overflow.
 *
 * Output has no digits from the supported forms. Engine-specific pronunciation (identifier letter names, acronyms,
 * punctuation) is not decided here, except that the letter names used for spelled letters are [letterName].
 */
object EnglishSpeechTextNormaliser {
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
    private val letterNames = mapOf(
        'A' to "ay", 'B' to "bee", 'C' to "see", 'D' to "dee", 'E' to "ee",
        'F' to "eff", 'G' to "gee", 'H' to "aitch", 'I' to "eye", 'J' to "jay",
        'K' to "kay", 'L' to "ell", 'M' to "em", 'N' to "en", 'O' to "oh",
        'P' to "pee", 'Q' to "cue", 'R' to "ar", 'S' to "ess", 'T' to "tee",
        'U' to "you", 'V' to "vee", 'W' to "double you", 'X' to "ex", 'Y' to "why",
        'Z' to "zee",
    )
    private val currencyWords = mapOf("NZ" to "New Zealand", "US" to "US", "AU" to "Australian", "A" to "Australian")

    /** Glued unit suffixes: singular and plural. */
    private val units = mapOf(
        "km/h" to ("kilometre per hour" to "kilometres per hour"),
        "mph" to ("mile per hour" to "miles per hour"),
        "km" to ("kilometre" to "kilometres"),
        "cm" to ("centimetre" to "centimetres"),
        "mm" to ("millimetre" to "millimetres"),
        "m" to ("metre" to "metres"),
        "kg" to ("kilogram" to "kilograms"),
        "mg" to ("milligram" to "milligrams"),
        "g" to ("gram" to "grams"),
        "ml" to ("millilitre" to "millilitres"),
        "L" to ("litre" to "litres"),
        "kW" to ("kilowatt" to "kilowatts"),
        "W" to ("watt" to "watts"),
        "GB" to ("gigabyte" to "gigabytes"),
        "MB" to ("megabyte" to "megabytes"),
        "TB" to ("terabyte" to "terabytes"),
        "kHz" to ("kilohertz" to "kilohertz"),
        "MHz" to ("megahertz" to "megahertz"),
        "GHz" to ("gigahertz" to "gigahertz"),
        "hPa" to ("hectopascal" to "hectopascals"),
        "min" to ("minute" to "minutes"),
        "hr" to ("hour" to "hours"),
    )
    private val unitAlternatives = units.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val minusSign = Regex("""(?<![\p{L}\p{N}])[-\u2212\u2013](?=\$?\d)""")
    private val isoDate = Regex("""(?<![\p{N}.,:/-])(\d{4})-(\d{2})-(\d{2})(?!\p{N})""")
    private val slashDate = Regex("""\b(0?[1-9]|1[0-2])/(0?[1-9]|[12]\d|3[01])/(\d{4})\b""")
    private val money = Regex("""(?<![\p{L}\p{N}])(NZ|US|AU|A)?\$(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?!\p{N})""")
    private val decade = Regex("""(?<![\p{L}\p{N}.,])((?:1[1-9]|20)\d0)s(?![\p{L}\p{N}])""")
    private val decimal = Regex("""\b(\d{1,3}(?:,\d{3})+|\d+)\.(\d+)\b""")
    private val ordinal = Regex("""\b(\d{1,18})(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val range = Regex("""(?<![\p{L}\p{N}/:$\-\u2212\u2013])(?<!\d[.,])(\d+)[-\u2212\u2013](\d+)(?![\p{N}/:\-\u2212\u2013]|[.,]\p{N})""")
    private val gluedUnit = Regex("""(?<![\p{L}\p{N}.,:/$])(\d{1,3}(?:,\d{3})+|\d+)($unitAlternatives)(?![\p{L}\p{N}/])""")
    private val yearAfterCue = Regex(
        """\b(in|since|from|until|till|by|during|circa|around|before|after|of|founded|established|built|renamed|updated|released|copyright|season|born|died|edition|January|February|March|April|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec)\s+((?:1[1-9]|20)\d{2})(?!\p{N})(?![.,]\p{N})""",
        RegexOption.IGNORE_CASE,
    )
    private val mixedToken = Regex("""(?<![\p{L}\p{N}])([A-Za-z]{1,4})?(\d+)([A-Za-z]{1,4})?(?![\p{L}\p{N}])""")
    private val plainNumber = Regex("""(?<![\p{L}\p{N}])(\d{1,3}(?:,\d{3})+|\d+)(?![\p{L}\p{N}])""")

    /** Letter name for spelled letters ("B" → "bee"). Non-letters are returned unchanged. */
    fun letterName(letter: Char): String = letterNames[letter.uppercaseChar()] ?: letter.toString()

    fun normalize(text: String): String {
        var value = minusSign.replace(text) { "minus " }
        value = isoDate.replace(value) { match ->
            spokenDate(match.groupValues[1], match.groupValues[2], match.groupValues[3]) ?: match.value
        }
        value = money.replace(value) { match -> expandMoney(match) }
        value = slashDate.replace(value) { match ->
            spokenDate(match.groupValues[3], match.groupValues[1], match.groupValues[2]) ?: match.value
        }
        value = value.replace(Regex("\\b(\\d{1,2}):(\\d{2})(?:\\s*([AaPp]\\.?\\s*[Mm]\\.?))?\\b")) { match -> expandTime(match) }
        value = value.replace(Regex("\\b(\\d{1,2})\\s*([AaPp]\\.?\\s*[Mm]\\.?)\\b")) { match ->
            val suffix = lettersOnly(match.groupValues[2]).lowercase(Locale.US)
            "${numberToWords(match.groupValues[1].toLong())} ${suffix.map { it.toString() }.joinToString(" ")}"
        }
        value = value.replace(Regex("\\b(\\d{3})-(\\d{4})\\b")) { match ->
            "${expandDigits(match.groupValues[1])}, ${expandDigits(match.groupValues[2])}"
        }
        value = decade.replace(value) { match -> spokenDecade(match.groupValues[1].toInt()) }
        value = value.replace(Regex("\\b\\d+(?:\\.\\d+){2,}\\b")) { match ->
            match.value.split('.').joinToString(" point ") { integerWords(it) }
        }
        value = decimal.replace(value) { match ->
            val fraction = match.groupValues[2].trimEnd('0').ifEmpty { "0" }
            "${integerWords(match.groupValues[1].replace(",", ""))} point ${expandDigits(fraction)}"
        }
        value = ordinal.replace(value) { match ->
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
        value = range.replace(value) { match -> "${match.groupValues[1]} to ${match.groupValues[2]}" }
        value = gluedUnit.replace(value) { match ->
            val digits = match.groupValues[1].replace(",", "")
            val (singular, plural) = units.getValue(match.groupValues[2])
            "${integerWords(digits)} ${if (digits == "1") singular else plural}"
        }
        value = yearAfterCue.replace(value) { match ->
            "${match.groupValues[1]} ${yearWords(match.groupValues[2].toInt())}"
        }
        value = mixedToken.replace(value) { match -> expandMixedToken(match) }
        value = plainNumber.replace(value) { match -> integerWords(match.groupValues[1].replace(",", "")) }
        return value
    }

    internal fun expandDigits(value: String): String =
        value.mapIndexed { index, digit ->
            if (digit == '0' && index > 0) "oh" else smallCardinals[digit - '0']
        }.joinToString(" ")

    private fun expandMoney(match: MatchResult): String {
        val currency = currencyWords[match.groupValues[1]]?.let { "$it " }.orEmpty()
        val rawDollars = match.groupValues[2].replace(",", "")
        val dollarWords = integerWords(rawDollars)
        val dollarUnit = if (rawDollars.toLongOrNull() == 1L) "dollar" else "dollars"
        val fraction = match.groupValues[3]
        return when {
            fraction.isEmpty() -> "$dollarWords ${currency}$dollarUnit"
            fraction.length > 2 -> "$dollarWords point ${expandDigits(fraction)} ${currency}dollars"
            else -> {
                val cents = fraction.padEnd(2, '0').toInt()
                if (cents == 0) {
                    "$dollarWords ${currency}$dollarUnit"
                } else {
                    "$dollarWords ${currency}$dollarUnit and ${cardinal(cents.toLong())} ${if (cents == 1) "cent" else "cents"}"
                }
            }
        }
    }

    private fun spokenDate(year: String, month: String, day: String): String? = try {
        val date = LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        if (date.year !in 100..9999) {
            null
        } else {
            val monthName = date.month.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            "$monthName ${numberToWords(date.dayOfMonth.toLong(), ordinal = true)} ${yearWords(date.year)}"
        }
    } catch (_: DateTimeException) {
        null
    }

    private fun spokenDecade(year: Int): String {
        val words = yearWords(year)
        return if (words.endsWith("y")) "${words.dropLast(1)}ies" else "${words}s"
    }

    /** A four-digit year as spoken: "nineteen ninety nine", "two thousand and five", "twenty twenty six". */
    internal fun yearWords(year: Int): String {
        require(year in 100..9999) { "Year out of range: $year" }
        if (year in 2000..2009) {
            val last = year % 100
            return if (last == 0) "two thousand" else "two thousand and ${cardinal(last.toLong())}"
        }
        val high = cardinal((year / 100).toLong())
        val low = year % 100
        return when {
            low == 0 -> "$high hundred"
            low < 10 -> "$high oh ${cardinal(low.toLong())}"
            else -> "$high ${cardinal(low.toLong())}"
        }
    }

    /** Digits as a cardinal. Leading zeros and runs too long for a Long are read digit by digit (codes, IDs). */
    private fun integerWords(digits: String): String {
        val value = digits.toLongOrNull()
        return if (value == null || (digits.length > 1 && digits[0] == '0')) expandDigits(digits) else cardinal(value)
    }

    private fun expandMixedToken(match: MatchResult): String {
        val prefix = match.groupValues[1]
        val digits = match.groupValues[2]
        val suffix = match.groupValues[3]
        if (prefix.isEmpty() && suffix.isEmpty()) return match.value
        val parts = mutableListOf<String>()
        if (prefix.isNotEmpty()) parts += spelledLetters(prefix)
        // A code after letters ("NZ123", "B1234") is read digit by digit; "B12" and "1080p" keep cardinals.
        parts += if (prefix.isNotEmpty() && digits.length >= 3) expandDigits(digits) else integerWords(digits)
        if (suffix.isNotEmpty()) parts += spelledLetters(suffix)
        return parts.joinToString(" ")
    }

    /** Short or all-capital letter runs are spelled ("mp" → "em pee", "NZ" → "en zee"); other words stay words ("Qwen"). */
    private fun spelledLetters(letters: String): String =
        if (letters.length <= 3 || letters.all { it.isUpperCase() }) {
            letters.map { letterName(it) }.joinToString(" ")
        } else {
            letters
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
