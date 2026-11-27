package com.kernel.ai.core.skills

import java.util.Locale

/*
 * Spoken clock words to digits for reminder times (#2146). Shared by every reminder reply path: typed,
 * voice, and the chat screen. Number words are rewritten only in a clock context: an hour word after
 * at/past/to/by/until/around, before o'clock, am/pm or a day part, or after "half"; a minute word before
 * "to"/"past" an hour, or after an hour word ("seven thirty"). "buy one more" and "take two tablets" stay as spoken.
 */

private val HOUR_WORDS: Map<String, Int> = mapOf(
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
)

private val MINUTE_WORDS: Map<String, Int> = mapOf(
    "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "twenty five" to 25, "thirty" to 30,
    "thirty five" to 35, "forty" to 40, "forty five" to 45, "fifty" to 50, "fifty five" to 55,
)

private val HOUR_WORD = HOUR_WORDS.keys.joinToString("|")
private val MINUTE_WORD = MINUTE_WORDS.keys.sortedByDescending { it.length }
    .joinToString("|") { it.replace(" ", "\\s+") }

/** A number word right before an hour word makes it part of a larger number ("oh seven", "twenty one"), not a clock hour. */
private val NUMBER_BEFORE_HOUR = """(?<!\b(?:oh|o|twenty|thirty|forty|fifty|$HOUR_WORD)\s)"""

/** "ten to eight", "twenty five past seven": a minute word, "to" or "past", then an hour. */
private val MINUTE_TO_HOUR = Regex("""\b($MINUTE_WORD)\s+(to|past)\s+($HOUR_WORD|\d{1,2})\b""", RegexOption.IGNORE_CASE)

/** "half seven" is seven thirty. */
private val HALF_HOUR_WORD = Regex("""\bhalf\s+($HOUR_WORD)\b""", RegexOption.IGNORE_CASE)

/** "seven thirty", "seven fifteen", "seven oh five", "seven ten". */
private val HOUR_WORD_THEN_MINUTE = Regex(
    """$NUMBER_BEFORE_HOUR\b($HOUR_WORD)\s+(?:(?:oh|o)\s+)?(five|ten|fifteen|twenty(?:\s+five)?|thirty(?:\s+five)?|forty(?:\s+five)?|fifty(?:\s+five)?)\b""",
    RegexOption.IGNORE_CASE,
)

/** An hour word after at/past/to/by/until/around: "at seven", "half past seven", "quarter to one". */
private val HOUR_WORD_IN_CONTEXT = Regex(
    """\b(at|past|to|by|until|around|till)(\s+)($HOUR_WORD)\b""",
    RegexOption.IGNORE_CASE,
)

/** An hour word before o'clock, am/pm, a day part or a day: "seven o'clock", "seven in the evening", "seven tomorrow". */
private val HOUR_WORD_BEFORE_DAY_PART = Regex(
    """$NUMBER_BEFORE_HOUR\b($HOUR_WORD)(?=\s+(?:o'?clock|[ap]\.?m\b|in\s+the\s+(?:morning|afternoon|evening)\b|at\s+night\b|tonight\b|this\s+evening\b|tomorrow\b|today\b))""",
    RegexOption.IGNORE_CASE,
)

private val HALF_PAST_DIGITS = Regex("""\bhalf\s+past\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val QUARTER_PAST_DIGITS = Regex("""\bquarter\s+past\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE)

/** "7 30 p.m." (spaced digits) reads as 7:30 p.m. The lookahead also matches at the end of the reply. */
private val SPACED_DIGIT_TIME = Regex(
    """\b(\d{1,2})\s+(\d{2})\s*(am|pm|a\.?m\.?|p\.?m\.?)(?![a-z])""",
    RegexOption.IGNORE_CASE,
)

private fun minuteValue(word: String): Int = MINUTE_WORDS.getValue(word.lowercase().split(Regex("\\s+")).joinToString(" "))

private fun hourValue(word: String): Int = word.toIntOrNull() ?: HOUR_WORDS.getValue(word.lowercase())

/**
 * [text] with spoken clock words in clock contexts rewritten as digits ("ten to eight" to "10 to 8",
 * "half seven" to "7:30", "seven o'clock" to "7 o'clock"). Other words, and "buy one more", are left as spoken.
 */
internal fun normalizeSpokenClock(text: String): String {
    var out = MINUTE_TO_HOUR.replace(text) { m ->
        "${minuteValue(m.groupValues[1])} ${m.groupValues[2].lowercase()} ${hourValue(m.groupValues[3])}"
    }
    out = HALF_HOUR_WORD.replace(out) { m -> "${hourValue(m.groupValues[1])}:30" }
    out = HOUR_WORD_THEN_MINUTE.replace(out) { m ->
        String.format(Locale.ROOT, "%d:%02d", hourValue(m.groupValues[1]), minuteValue(m.groupValues[2]))
    }
    out = HOUR_WORD_IN_CONTEXT.replace(out) { m -> "${m.groupValues[1]}${m.groupValues[2]}${hourValue(m.groupValues[3])}" }
    out = HOUR_WORD_BEFORE_DAY_PART.replace(out) { m -> hourValue(m.groupValues[1]).toString() }
    out = HALF_PAST_DIGITS.replace(out) { m -> "${m.groupValues[1]}:30" }
    out = QUARTER_PAST_DIGITS.replace(out) { m -> "${m.groupValues[1]}:15" }
    return SPACED_DIGIT_TIME.replace(out) { m ->
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour !in 1..12 || minute !in 0..59) return@replace m.value
        "$hour:${m.groupValues[2]} ${m.groupValues[3]}"
    }
}
