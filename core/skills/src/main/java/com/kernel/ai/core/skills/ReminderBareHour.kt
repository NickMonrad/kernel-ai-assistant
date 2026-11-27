package com.kernel.ai.core.skills

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A clock hour 1 to 11 given with no am/pm, for example "7", "6:30", "7 o'clock" or "six" (#2132).
 *
 * Only these hours are ambiguous. Hour 0, hour 12, and 24-hour times such as "19" keep their meaning.
 */
internal data class BareHour(val hour: Int, val minute: Int)

private val EVENING_CUE = Regex("""\b(?:tonight|this\s+evening)\b""", RegexOption.IGNORE_CASE)

// Digits with an optional am/pm. The same shape as QuickIntentRouter's alarm time parsing.
private val DIGIT_TIME = Regex("""(\d{1,2})(?::(\d{2}))?(\s*(?:am|pm|a\.m\.?|p\.m\.?))?""", RegexOption.IGNORE_CASE)

/** True when the text says "tonight" or "this evening". */
internal fun hasEveningCue(text: String): Boolean = EVENING_CUE.containsMatchIn(text)

/**
 * The bare hour in a time phrase, or null when the phrase has am/pm, is not hour 1 to 11,
 * or has no time. Accepts digits ("7", "6:30") and spoken hours ("six", "at six").
 */
internal fun bareHourIn(text: String): BareHour? {
    val cleaned = text.trim().lowercase()
        .replace(Regex("""\s*o'?clock\b"""), "")
        .replace(Regex("""^(?:at|by)\s+"""), "")
        .trim()
    if (cleaned.isEmpty()) return null

    val digits = DIGIT_TIME.find(cleaned)
    if (digits != null) {
        if (digits.groups[3] != null) return null
        val hour = digits.groupValues[1].toInt()
        val minute = digits.groupValues[2].ifEmpty { "0" }.toInt()
        return if (hour in 1..11 && minute in 0..59) BareHour(hour, minute) else null
    }

    // "a" and "an" are quantities, not hours.
    if (cleaned.split(Regex("""[\s-]+""")).any { it == "a" || it == "an" }) return null
    val hour = writtenQuantity(cleaned) ?: return null
    return if (hour in 1..11) BareHour(hour, 0) else null
}

/**
 * The clock time a bare hour means. With an evening cue it is PM ("tonight at 7" is 19:00).
 * Without one it is the nearest future occurrence of the hour after [now]: today if still ahead,
 * otherwise tomorrow. A time equal to [now] counts as passed.
 *
 * The evening result is returned even when it is already past, so the caller can report that
 * rather than silently moving the reminder to tomorrow.
 */
internal fun resolveBareHour(bare: BareHour, evening: Boolean, now: LocalDateTime): LocalTime {
    if (evening) return LocalTime.of(bare.hour + 12, bare.minute)
    val candidates = listOf(bare.hour, bare.hour + 12).map { LocalTime.of(it, bare.minute) }
    val today = now.toLocalDate()
    return candidates.filter { today.atTime(it).isAfter(now) }.minOrNull() ?: candidates.min()
}
