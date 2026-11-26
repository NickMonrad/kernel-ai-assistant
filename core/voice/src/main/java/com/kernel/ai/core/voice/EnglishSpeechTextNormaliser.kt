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
 * - Years: a four-digit 1000–2099 with no comma is a year only after a year cue ("in", "since", "of", "year", a month
 *   name, "founded", …), before "season" or "edition", or as one end of a year range ("from 2020 to 2026",
 *   "1990–1995"). Unlike [SherpaSpeechText], which reads any such number by its shape, a bare number stays a cardinal:
 *   "1300 people" → "one thousand three hundred people", "1999 was a great year" → "one thousand nine hundred and
 *   ninety nine was a great year". "Founded in 1999" → "founded in nineteen ninety nine"; "in 1066" → "in ten sixty
 *   six"; "the 2026 season" → "the twenty twenty six season".
 * - Big numbers: "15,000" → "fifteen thousand", "1,000,000" → "one million", "1,000.50" → "one thousand point five",
 *   "1,000,000th" → "one millionth".
 * - Money: "$49.95" → "forty nine dollars and ninety five cents", "NZ$20" → "twenty New Zealand dollars",
 *   "$12.345" → "twelve point three four five dollars", "$1.5 million" / "$1.5m" → "one point five million dollars",
 *   "$2bn" → "two billion dollars", "$100k" → "one hundred thousand dollars".
 * - Dates (day first, as NZ writes them): "26/11/2026", "27 November 2026" and "2026-11-26" → "the twenty sixth of
 *   November, twenty twenty six". A slash date reads month first only when day first is not a real date:
 *   "1/21/2026" → "the twenty first of January, twenty twenty six". "November 27, 2026" → "the twenty seventh of
 *   November, twenty twenty six".
 * - Times: "7:05 p.m." → "seven oh five p m", "15:00" → "three p m", "00:00" → "midnight", "8:00" → "eight o clock".
 * - Glued units and mixed tokens: "5km" → "five kilometres", "1.5km" → "one point five kilometres", "2.4GHz" → "two point
 *   four gigahertz", "0.75x" → "zero point seven five times", "4B" → "four bee", "5G" → "five gee", "NZ123" → "en zee one
 *   two three", "1080p" → "ten eighty p", "Qwen2.5" → "Qwen two point five", "Llama3.1" → "Llama three point one".
 * - Decades: "the 90s" and "'90s" → "the nineties", "the 1990s" → "the nineteen nineties".
 * - Phone numbers: NZ groups are read digit by digit with "oh" for every zero, in groups: "021-123-4567" → "oh two one,
 *   one two three, four five six seven", "+64 21 123 4567" → "plus six four, two one, one two three, four five six
 *   seven". "Ring 111" → "ring one one one". "1300 555 019" and "203-0678" are grouped digit strings too.
 * - Dates and times: "7:05 p.m." → "seven oh five p m".
 * - Digit strings: phone-style groups, grouped identifiers ("1300 555 019") and leading-zero or very long runs are
 *   read digit by digit, so no number is lost to a `Long` overflow.
 *
 * Output has no digits from the supported forms. Symbols such as "/", ":", "+", "#", "€" and "£" are only removed
 * from the forms above; fractions, ratios, time ranges and currency symbols other than "$" are not handled here.
 * Engine-specific pronunciation (identifier letter names, acronyms, punctuation) is not decided here, except that the
 * letter names used for spelled letters are [letterName].
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
    private val scaleAbbreviations = mapOf("k" to "thousand", "m" to "million", "b" to "billion", "bn" to "billion")
    private val monthNames = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val monthAlternation = (monthNames + listOf("Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sep", "Sept", "Oct", "Nov", "Dec"))
        .joinToString("|")

    /** Glued unit suffixes: singular and plural. */
    private val units = mapOf(
        "km/h" to ("kilometre per hour" to "kilometres per hour"),
        "mph" to ("mile per hour" to "miles per hour"),
        "km" to ("kilometre" to "kilometres"),
        "kms" to ("kilometre" to "kilometres"),
        "cm" to ("centimetre" to "centimetres"),
        "mm" to ("millimetre" to "millimetres"),
        "m" to ("metre" to "metres"),
        "kg" to ("kilogram" to "kilograms"),
        "mg" to ("milligram" to "milligrams"),
        "g" to ("gram" to "grams"),
        "ml" to ("millilitre" to "millilitres"),
        "mL" to ("millilitre" to "millilitres"),
        "L" to ("litre" to "litres"),
        "kW" to ("kilowatt" to "kilowatts"),
        "W" to ("watt" to "watts"),
        "GB" to ("gigabyte" to "gigabytes"),
        "MB" to ("megabyte" to "megabytes"),
        "TB" to ("terabyte" to "terabytes"),
        "kHz" to ("kilohertz" to "kilohertz"),
        "MHz" to ("megahertz" to "megahertz"),
        "GHz" to ("gigahertz" to "gigahertz"),
        "Hz" to ("hertz" to "hertz"),
        "hPa" to ("hectopascal" to "hectopascals"),
        "min" to ("minute" to "minutes"),
        "mins" to ("minute" to "minutes"),
        "hr" to ("hour" to "hours"),
        "hrs" to ("hour" to "hours"),
        "s" to ("second" to "seconds"),
        "sec" to ("second" to "seconds"),
        "secs" to ("second" to "seconds"),
        "x" to ("time" to "times"),
    )
    private val unitAlternatives = units.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val minusSign = Regex("""(?<![\p{L}\p{N}])[-\u2212\u2013](?=\$?\d)""")
    private val isoDate = Regex("""(?<![\p{N}.,:/-])(\d{4})-(\d{2})-(\d{2})(?!\p{N})""")
    private val slashDate = Regex("""(?<![\p{N}.,:/-])(\d{1,2})/(\d{1,2})/(\d{4})(?![\p{N}/])""")
    private val dayMonthYear = Regex("""\b([Tt]he\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($monthAlternation),?\s+(\d{4})(?!\p{N})""")
    private val monthDayYear = Regex("""\b($monthAlternation)\.?\s+(?:[Tt]he\s+)?(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\p{N})""")
    private val money = Regex(
        """(?<![\p{L}\p{N}])(NZ|US|AU|A)?\$(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?:\s?(?i:(million|billion|trillion|thousand))\b|(?i:(bn|k|m|b))(?!\p{L}))?(?!\p{N})""",
    )
    private val emergencyCue = Regex("""(?i)\b(call|ring|dial|emergency)\s+111(?!\p{N})""")
    private val emergencyLead = Regex("""\b111(?=\s+in an emergency\b)""")
    private val countryCode = Regex("""(?<![\p{N}.,])\+64[\s-]+(?=\d)""")
    private val timeColon = Regex("""\b(\d{1,2}):(\d{2})(?:\s*([AaPp]\.?\s*[Mm]\.?))?\b""")
    private val timeSuffix = Regex("""\b(\d{1,2})\s*([AaPp]\.?\s*[Mm]\.?)\b""")
    private val resolution = Regex("""(?<![\p{L}\p{N}.,])(240|360|480|576|720|1080|1440|2160)p(?![\p{L}\p{N}])""")
    private val hyphenPhone = Regex("""(?<![\p{N}.,:/$])(0\d{2,3}|\d{4})-(\d{3})-(\d{3,4})(?!\p{N}|[.,]\p{N}|[:/-])""")
    private val hyphenPair = Regex("""\b(\d{3})-(\d{4})\b""")
    private val decade = Regex("""(?<![\p{L}\p{N}.,])((?:1[1-9]|20)\d0)s(?![\p{L}\p{N}])""")
    private val shortDecade = Regex("""(?<![\p{L}\p{N}.,])((?:[Tt]he\s+)?)('?)([1-9]0)s(?![\p{L}\p{N}])""")
    private val multiDot = Regex("""\b\d+(?:\.\d+){2,}\b""")
    private val ordinal = Regex("""\b(\d{1,3}(?:,\d{3})+|\d{1,18})(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    // #1486 grouped-digit identifiers: only runs of THREE OR MORE space-separated integer
    // groups are treated as a digit-sequence (e.g. "1300 555 019"). Two adjacent integers
    // such as "3 20" or "2 500" are ordinary quantities and must keep cardinal semantics.
    private val digitGroups = Regex("""\b(\d+(?:\s+\d+){2,})\b""")
    private val yearRange = Regex(
        """(?<![\p{L}\p{N}.,:/$])((?:10|1[1-9]|20)\d{2})(?:\s*[-\u2212\u2013]\s*|\s+(?:to|until|till)\s+)((?:10|1[1-9]|20)\d{2})(?!\p{N}|[.,]\p{N}|[-\u2212\u2013/:])""",
    )
    private val seasonYear = Regex("""(?<![\p{L}\p{N}.,:/$])((?:10|1[1-9]|20)\d{2})(?=\s+(?:season|edition)\b)""")
    private val range = Regex("""(?<![\p{L}\p{N}/:$\-\u2212\u2013])(?<!\d[.,])(\d+)[-\u2212\u2013](\d+)(?![\p{N}/:\-\u2212\u2013]|[.,]\p{N})""")
    /** A glued unit, optionally after a decimal: "5km", "1.5km", "2.4GHz". A decimal is never split from its unit. */
    private val gluedUnit = Regex(
        """(?<![\p{L}\p{N}.,:/$])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?($unitAlternatives)(?![\p{L}\p{N}/])""",
    )
    private val decimal = Regex("""(?<![\p{N}.,])(\d{1,3}(?:,\d{3})+|\d+)\.(\d+)(?![\p{N}]|\.\p{N})""")
    private val yearAfterCue = Regex(
        """\b(in|since|from|until|till|by|during|circa|around|before|after|of|founded|established|built|renamed|updated|released|copyright|season|born|died|edition|year|January|February|March|April|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec)\s+((?:10|1[1-9]|20)\d{2})(?!\p{N})(?![.,]\p{N})""",
        RegexOption.IGNORE_CASE,
    )
    private val mixedToken = Regex("""(?<![\p{L}\p{N}])([A-Za-z]{1,4})?(\d+)([A-Za-z]{1,4})?(?![\p{L}\p{N}])""")
    private val plainNumber = Regex("""(?<![\p{L}\p{N}])(\d{1,3}(?:,\d{3})+|\d+)(?![\p{L}\p{N}])""")

    /** Letter name for spelled letters ("B" → "bee"). Non-letters are returned unchanged. */
    fun letterName(letter: Char): String = letterNames[letter.uppercaseChar()] ?: letter.toString()

    fun normalize(text: String): String {
        var value = minusSign.replace(text) { "minus " }
        value = isoDate.replace(value) { match ->
            spokenDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) ?: match.value
        }
        value = slashDate.replace(value) { match ->
            spokenSlashDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) ?: match.value
        }
        value = dayMonthYear.replace(value) { match ->
            val spoken = spokenDate(
                year = match.groupValues[4].toInt(),
                month = monthNumber(match.groupValues[3]),
                day = match.groupValues[2].toInt(),
            ) ?: return@replace match.value
            if (match.groupValues[1].startsWith("T")) spoken.replaceFirstChar { it.uppercaseChar() } else spoken
        }
        value = monthDayYear.replace(value) { match ->
            spokenDate(
                year = match.groupValues[3].toInt(),
                month = monthNumber(match.groupValues[1]),
                day = match.groupValues[2].toInt(),
            ) ?: match.value
        }
        value = money.replace(value) { match -> expandMoney(match) }
        value = emergencyCue.replace(value) { match -> "${match.groupValues[1]} one one one" }
        value = emergencyLead.replace(value, "one one one")
        value = countryCode.replace(value, "plus six four, ")
        value = timeColon.replace(value) { match -> expandTime(match) }
        value = timeSuffix.replace(value) { match ->
            val suffix = lettersOnly(match.groupValues[2]).lowercase(Locale.US)
            "${numberToWords(match.groupValues[1].toLong())} ${suffix.map { it.toString() }.joinToString(" ")}"
        }
        value = resolution.replace(value) { match -> "${yearWords(match.groupValues[1].toInt())} p" }
        value = hyphenPhone.replace(value) { match ->
            listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3]).joinToString(", ") { expandDigits(it) }
        }
        value = hyphenPair.replace(value) { match ->
            "${expandDigits(match.groupValues[1])}, ${expandDigits(match.groupValues[2])}"
        }
        value = decade.replace(value) { match -> pluralDecade(yearWords(match.groupValues[1].toInt())) }
        value = shortDecade.replace(value) { match ->
            val words = pluralDecade(cardinal(match.groupValues[3].toLong()))
            val lead = match.groupValues[1]
            when {
                lead.isNotEmpty() -> "$lead$words"
                match.groupValues[2].isNotEmpty() -> "the $words"
                else -> words
            }
        }
        value = multiDot.replace(value) { match ->
            match.value.split('.').joinToString(" point ") { integerWords(it) }
        }
        value = ordinal.replace(value) { match ->
            val number = match.groupValues[1].replace(",", "").toLongOrNull() ?: return@replace match.value
            numberToWords(number, ordinal = true)
        }
        value = digitGroups.replace(value) { match ->
            match.value.split(Regex("\\s+")).joinToString(", ") { group -> expandDigits(group) }
        }
        value = yearRange.replace(value) { match ->
            "${yearWords(match.groupValues[1].toInt())} to ${yearWords(match.groupValues[2].toInt())}"
        }
        value = seasonYear.replace(value) { match -> yearWords(match.groupValues[1].toInt()) }
        value = range.replace(value) { match -> "${match.groupValues[1]} to ${match.groupValues[2]}" }
        value = gluedUnit.replace(value) { match ->
            val whole = match.groupValues[1]
            val fraction = match.groupValues[2]
            val (singular, plural) = units.getValue(match.groupValues[3])
            val isOne = fraction.isEmpty() && whole.replace(",", "") == "1"
            "${spokenNumber(whole, fraction)} ${if (isOne) singular else plural}"
        }
        value = decimal.replace(value) { match ->
            // "Qwen2.5" → "Qwen two point five": a letter directly before the number needs a space.
            val lead = if (match.range.first > 0 && value[match.range.first - 1].isLetter()) " " else ""
            lead + spokenNumber(match.groupValues[1], match.groupValues[2])
        }
        value = yearAfterCue.replace(value) { match ->
            "${match.groupValues[1]} ${yearWords(match.groupValues[2].toInt())}"
        }
        value = mixedToken.replace(value) { match -> expandMixedToken(match) }
        value = plainNumber.replace(value) { match -> integerWords(match.groupValues[1].replace(",", "")) }
        return value
    }

    /** Every digit as a word, with "oh" for zero: phone groups ("oh two one"), decimals ("point oh"), codes. */
    internal fun expandDigits(value: String): String =
        value.map { digit -> if (digit == '0') "oh" else smallCardinals[digit - '0'] }.joinToString(" ")

    private fun expandMoney(match: MatchResult): String {
        val currency = currencyWords[match.groupValues[1]]?.let { "$it " }.orEmpty()
        val rawDollars = match.groupValues[2].replace(",", "")
        val fraction = match.groupValues[3]
        val scale = match.groupValues[4].lowercase(Locale.US)
            .ifEmpty { scaleAbbreviations[match.groupValues[5].lowercase(Locale.US)].orEmpty() }
        if (scale.isNotEmpty()) {
            return "${spokenNumber(rawDollars, fraction)} $scale ${currency}dollars"
        }
        val dollarWords = integerWords(rawDollars)
        val dollarUnit = if (rawDollars.toLongOrNull() == 1L) "dollar" else "dollars"
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

    /** A number with its decimal part: "1.5" → "one point five", "1.0" → "one point oh". */
    private fun spokenNumber(whole: String, fraction: String): String {
        val words = integerWords(whole.replace(",", ""))
        if (fraction.isEmpty()) return words
        return "$words point ${expandDigits(fraction.trimEnd('0').ifEmpty { "0" })}"
    }

    /** Day first, as NZ writes dates; month first only when the day-first reading is not a real date. */
    private fun spokenSlashDate(first: Int, second: Int, year: Int): String? =
        spokenDate(year, second, first) ?: spokenDate(year, first, second)

    private fun monthNumber(name: String): Int = monthNames.indexOfFirst { it.startsWith(name.take(3), ignoreCase = true) } + 1

    /** "the twenty sixth of November, twenty twenty six"; null when the day, month and year are not a real date. */
    private fun spokenDate(year: Int, month: Int, day: Int): String? = try {
        val date = LocalDate.of(year, month, day)
        if (date.year !in 100..9999) {
            null
        } else {
            "the ${numberToWords(day.toLong(), ordinal = true)} of ${monthNames[month - 1]}, ${yearWords(year)}"
        }
    } catch (_: DateTimeException) {
        null
    }

    /** "nineties", "tens", "twenty tens": the plural of a decade's spoken year or cardinal. */
    private fun pluralDecade(words: String): String =
        if (words.endsWith("y")) "${words.dropLast(1)}ies" else "${words}s"

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

    /**
     * "7:05 am" → "seven oh five a m"; "8:00" → "eight o clock"; "15:00" → "three p m"; "00:00" → "midnight".
     * A 24-hour time with no suffix after 12 gets "p m"; an a m or p m suffix never also says "o clock".
     */
    private fun expandTime(match: MatchResult): String {
        val hour24 = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        val explicit = lettersOnly(match.groupValues[3]).lowercase(Locale.US)
        if (explicit.isEmpty() && hour24 == 0 && minute == 0) return "midnight"
        val (hour, suffix) = when {
            explicit.isNotEmpty() -> (hour24 % 12).let { if (it == 0) 12 else it } to explicit
            hour24 in 13..23 -> (hour24 - 12) to "pm"
            hour24 == 0 -> 12 to "am"
            else -> hour24 to ""
        }
        val pieces = mutableListOf(numberToWords(hour.toLong()))
        when {
            minute == 0 && suffix.isEmpty() -> pieces += "o clock"
            minute == 0 -> Unit
            minute < 10 -> pieces += listOf("oh", numberToWords(minute.toLong()))
            else -> pieces += numberToWords(minute.toLong())
        }
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
