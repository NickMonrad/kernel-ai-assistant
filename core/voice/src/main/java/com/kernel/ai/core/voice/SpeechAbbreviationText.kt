package com.kernel.ai.core.voice

/**
 * Speech wording for dates and weather units, shared by every TTS engine (#2077, #1901). Run from
 * [SpeechCardText.speakable], so Android TTS, Piper/Kokoro and Inflect all receive the same text.
 *
 * Weekday and month abbreviations are expanded only where they are unambiguous: a weekday before a
 * day number, a day number before a month, or a month before a day number. "Mon 23 Nov" becomes
 * "Monday the 23rd of November". Units and percentages become words: "11°C" → "11 degrees",
 * "92%" → "92 percent", "0mm" → "0 millimetres". Idempotent.
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

    private val weekdayBeforeDay = Regex("""\b(${weekdays.keys.joinToString("|")})\b(?=\.?\s+$DAY\b)""")
    private val dayBeforeMonth = Regex("""\b$DAY (${months.keys.joinToString("|")})\b""")
    private val monthBeforeDay = Regex("""\b(${months.keys.joinToString("|")})\b\.? $DAY\b""")
    private val degreesCelsius = Regex("""(\d)\s*°\s*C\b""")
    private val degreesFahrenheit = Regex("""(\d)\s*°\s*F\b""")
    private val degrees = Regex("""(\d)\s*°""")
    private val percent = Regex("""(\d)\s*%""")
    private val millimetres = Regex("""(\d)\s*mm\b""")
    private val kilometresPerHour = Regex("""(\d)\s*km/h\b""")

    fun expand(text: String): String {
        var value = weekdayBeforeDay.replace(text) { weekdays.getValue(it.groupValues[1]) }
        value = dayBeforeMonth.replace(value) { match ->
            "the ${ordinal(match.groupValues[1].toInt())} of ${months.getValue(match.groupValues[2])}"
        }
        value = monthBeforeDay.replace(value) { match ->
            "${months.getValue(match.groupValues[1])} the ${ordinal(match.groupValues[2].toInt())}"
        }
        value = degreesCelsius.replace(value) { "${it.groupValues[1]} degrees" }
        value = degreesFahrenheit.replace(value) { "${it.groupValues[1]} degrees Fahrenheit" }
        value = degrees.replace(value) { "${it.groupValues[1]} degrees" }
        value = percent.replace(value) { "${it.groupValues[1]} percent" }
        value = millimetres.replace(value) { "${it.groupValues[1]} millimetres" }
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
