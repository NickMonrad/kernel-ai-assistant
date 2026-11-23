package com.kernel.ai.core.voice

/**
 * Speech wording for dates and weather units, shared by every TTS engine (#2077, #1901). Run from
 * [SpeechCardText.speakable], so Android TTS, Piper/Kokoro and Inflect all receive the same text.
 *
 * Weekday and month abbreviations are expanded only where they are unambiguous: a weekday before a
 * day number or month, a day number before a month, or a month before a day number. "Mon 23 Nov"
 * becomes "Monday the 23rd of November". Units and percentages become words: "11°C" → "11 degrees",
 * "92%" → "92 percent", "0mm" → "0 millimetres", "1.9 m/s" → "1.9 metres per second". Idempotent.
 */
internal object SpeechAbbreviationText {
    private val weekdays = mapOf(
        "Mon" to "Monday", "Tue" to "Tuesday", "Tues" to "Tuesday", "Wed" to "Wednesday",
        "Thu" to "Thursday", "Thur" to "Thursday", "Thurs" to "Thursday", "Fri" to "Friday",
        "Sat" to "Saturday", "Sun" to "Sunday",
    )
    private val months = mapOf(
        "Jan" to "January", "Feb" to "February", "Mar" to "March", "Apr" to "April", "May" to "May",
        "Jun" to "June", "Jul" to "July", "Aug" to "August", "Sep" to "September", "Sept" to "September",
        "Oct" to "October", "Nov" to "November", "Dec" to "December",
    )
    private const val DAY = """([1-9]|[12]\d|3[01])"""
    private val monthPattern = months.keys.joinToString("|")

    // A weekday is expanded only when a day number or month follows it, with optional "." or ",".
    private val weekdayBeforeDate = Regex(
        """\b(${weekdays.keys.joinToString("|")})\b(?=\.?,?\s+(?:$DAY\b|(?:$monthPattern)\b))""",
    )
    // Not after a range, slash or decimal point ("5-10 Nov", "2/3 May", "1.5 Mar"), which are not single dates.
    private val dayBeforeMonth = Regex("""(?<![\d\-–/.] ?)\b$DAY ($monthPattern)\b""")
    // Not before a range, slash or decimal point ("Nov 5-10").
    private val monthBeforeDay = Regex("""\b($monthPattern)\b\.? $DAY\b(?![-–/.]\d)""")
    private val degreesCelsius = Regex("""(\d)\s*°\s*C(?![A-Za-z])""")
    private val degreesFahrenheit = Regex("""(\d)\s*°\s*F(?![A-Za-z])""")
    // "45°N" → "45 degrees N": keep a space so the following letter is not joined to "degrees".
    private val degreesBeforeLetter = Regex("""(\d)\s*°(?=[A-Za-z])""")
    private val degrees = Regex("""(\d)\s*°""")
    private val percent = Regex("""(\d)\s*%""")
    private val millimetres = Regex("""(\d)\s*mm\b""")
    private val metresPerSecond = Regex("""(\d)\s*m/s\b""")
    private val kilometresPerHour = Regex("""(\d)\s*km/h\b""")

    fun expand(text: String): String {
        var value = weekdayBeforeDate.replace(text) { weekdays.getValue(it.groupValues[1]) }
        value = dayBeforeMonth.replace(value) { match ->
            "the ${ordinal(match.groupValues[1].toInt())} of ${months.getValue(match.groupValues[2])}"
        }
        value = monthBeforeDay.replace(value) { match ->
            "${months.getValue(match.groupValues[1])} the ${ordinal(match.groupValues[2].toInt())}"
        }
        value = degreesCelsius.replace(value) { "${it.groupValues[1]} degrees" }
        value = degreesFahrenheit.replace(value) { "${it.groupValues[1]} degrees Fahrenheit" }
        value = degreesBeforeLetter.replace(value) { "${it.groupValues[1]} degrees " }
        value = degrees.replace(value) { "${it.groupValues[1]} degrees" }
        value = percent.replace(value) { "${it.groupValues[1]} percent" }
        value = millimetres.replace(value) { "${it.groupValues[1]} millimetres" }
        value = metresPerSecond.replace(value) { "${it.groupValues[1]} metres per second" }
        return kilometresPerHour.replace(value) { "${it.groupValues[1]} kilometres per hour" }
    }

    private fun ordinal(day: Int): String {
        val suffix = when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }
}
