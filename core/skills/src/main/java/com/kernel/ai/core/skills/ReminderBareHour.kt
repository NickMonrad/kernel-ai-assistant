package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.slot.SlotSpec
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * Bare-hour rule for reminder times (#2132).
 *
 * A clock hour 1 to 11 given with no am/pm and no day-part word ("7", "6:30", "7 o'clock", "six")
 * is ambiguous. Then: "tonight" / "this evening" makes it PM today (hours 5 to 11); hours 1 to 4
 * with "tonight" ask which; a day other than today asks "Morning or evening?"; no day means the
 * next occurrence. Hour 0, hour 12, 24-hour times, and any am/pm or day-part word keep their meaning.
 */

/** Param holding an "HH:mm" hour the user typed that still needs am/pm. */
internal const val BARE_TIME_PARAM = "bare_time"

/** Slot name of the am/pm question for a pending [BARE_TIME_PARAM]. */
internal const val TIME_OF_DAY_SLOT = "time_of_day"

/** Param set to "tonight" when the reminder's day was said as tonight; the past-time message uses it. */
internal const val DAY_CUE_PARAM = "day_cue"

internal data class BareHour(val hour: Int, val minute: Int)

private val EVENING_CUE = Regex("""\b(?:tonight|this\s+evening)\b(?!')""", RegexOption.IGNORE_CASE)
private val TRAILING_EVENING_CUE = Regex("""\b(?:tonight|this\s+evening)\s*[.!?]*$""", RegexOption.IGNORE_CASE)
private val DAY_PART = Regex("""\b(?:morning|afternoon|evening|night)\b""", RegexOption.IGNORE_CASE)
private val AM_PM = Regex("""(?<![a-z])(?:am|pm|a\.m\.?|p\.m\.?)(?![a-z])""", RegexOption.IGNORE_CASE)

private val AM_ANSWER = Regex("""(?:in\s+the\s+)?morning|a\.?\s?m\.?""", RegexOption.IGNORE_CASE)
private val PM_ANSWER = Regex("""(?:in\s+the\s+)?(?:afternoon|evening)|p\.?\s?m\.?""", RegexOption.IGNORE_CASE)
private val YES_ANSWER = setOf("yes", "yeah", "yep", "yup")
private val NO_ANSWER = setOf("no", "nope")

/** True when the text says "tonight" or "this evening" (not "tonight's"). */
internal fun hasEveningCue(text: String): Boolean = EVENING_CUE.containsMatchIn(text)

/** True when the utterance ends with "tonight" or "this evening": "remind me to put the bins out tonight". */
internal fun endsWithEveningCue(utterance: String): Boolean = TRAILING_EVENING_CUE.containsMatchIn(utterance)

/** True when the utterance says "tonight at <timePhrase>" or "this evening by <timePhrase>". */
internal fun eveningCueBeforeTime(utterance: String, timePhrase: String): Boolean {
    if (timePhrase.isBlank()) return false
    return Regex("""\b(?:tonight|this\s+evening)\s+(?:at|by)\s+""" + Regex.escape(timePhrase), RegexOption.IGNORE_CASE)
        .containsMatchIn(utterance)
}

/**
 * The bare hour in a time phrase, or null when the phrase has am/pm or a day-part word (other than
 * the evening cue), or its hour is not 1 to 11. Digits go through [QuickIntentRouter.parseAlarmTime]
 * so compact times ("7 30") read as 7:30. Spoken hours ("six", "at six") are read when there are no digits.
 */
internal fun bareHourIn(timePhrase: String): BareHour? {
    val cleaned = timePhrase.trim().lowercase()
    if (cleaned.isEmpty() || AM_PM.containsMatchIn(cleaned)) return null
    if (DAY_PART.containsMatchIn(cleaned) && !hasEveningCue(cleaned)) return null

    val parsed = QuickIntentRouter.parseAlarmTime(cleaned)
    val hours = parsed["hours"]
    if (hours != null) {
        val hour = hours.toInt()
        return if (hour in 1..11) BareHour(hour, parsed.getValue("minutes").toInt()) else null
    }

    // "a" and "an" are quantities, not hours.
    val spoken = cleaned
        .replace(Regex("""\s*o'?clock\b"""), "")
        .replace(Regex("""^(?:at|by)\s+"""), "")
        .trim()
    if (spoken.split(Regex("""[\s-]+""")).any { it == "a" || it == "an" }) return null
    val hour = writtenQuantity(spoken) ?: return null
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

internal fun clockText(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/**
 * Schedule params for a reminder time phrase (#2132).
 *
 * [QuickIntentRouter.parseAlarmTime] runs first, so its day, label and compact times are kept.
 * Only a bare hour 1 to 11 changes the time. [dayWord] is the day the utterance or reply gave, normalised,
 * or null. [eveningCue] is a "tonight" or "this evening" cue that applies to this time. An explicit day
 * other than today or tonight overrides the cue.
 */
internal fun reminderScheduleParams(
    timePhrase: String,
    dayWord: String?,
    eveningCue: Boolean,
    now: LocalDateTime,
): Map<String, String> {
    val parsed = QuickIntentRouter.parseAlarmTime(timePhrase)
    val day = dayWord ?: parsed["day"]
    val evening = day == "tonight" || (eveningCue && (day == null || day == "today"))
    val bare = bareHourIn(timePhrase)
    if (bare == null) {
        return buildMap {
            putAll(parsed)
            if (day != null) put("day", day)
            if (evening) {
                put("day", "today")
                put(DAY_CUE_PARAM, "tonight")
            }
        }
    }
    return parsed.filterKeys { it != "time" && it != "hours" && it != "minutes" } + bareHourParams(bare, day, evening, now)
}

private fun bareHourParams(bare: BareHour, day: String?, evening: Boolean, now: LocalDateTime): Map<String, String> = when {
    // "tonight at 1" could be 1 am tomorrow or 1 pm today: ask which.
    evening && bare.hour <= 4 -> mapOf(BARE_TIME_PARAM to clockText(bare.hour, bare.minute), "day" to "tonight")
    evening -> mapOf(
        "time" to clockText(bare.hour + 12, bare.minute),
        "day" to "today",
        DAY_CUE_PARAM to "tonight",
    )
    // A day other than today: the hour could be morning or evening, so ask.
    day != null && day != "today" -> mapOf(BARE_TIME_PARAM to clockText(bare.hour, bare.minute), "day" to day)
    else -> {
        val time = resolveBareHour(bare, evening = false, now = now)
        buildMap {
            put("time", clockText(time.hour, time.minute))
            if (day != null) put("day", day)
        }
    }
}

/**
 * The am/pm answer for a pending [BARE_TIME_PARAM], as the time and day it gives. Null when the reply is
 * not an answer. With [day] "tonight" the question is "1 in the morning?" and yes/no answer it; otherwise
 * the reply is morning/evening or am/pm.
 */
internal fun timeOfDayParams(bareTime: String?, day: String?, reply: String): Map<String, String>? {
    if (bareTime.isNullOrBlank()) return null
    val tonight = day.equals("tonight", ignoreCase = true)
    val pm = pmAnswer(reply, tonight) ?: return null
    val (hour, minute) = bareTime.split(":").map { it.toInt() }
    return when {
        tonight && pm -> mapOf("time" to clockText(hour + 12, minute), "day" to "today", DAY_CUE_PARAM to "tonight")
        tonight -> mapOf("time" to clockText(hour, minute), "day" to "tomorrow")
        pm -> mapOf("time" to clockText(hour + 12, minute))
        else -> mapOf("time" to clockText(hour, minute))
    }
}

/** true = PM, false = AM, null = not an answer. */
private fun pmAnswer(reply: String, tonight: Boolean): Boolean? {
    val text = reply.trim().trimEnd('.', '!', '?').trim()
    val lower = text.lowercase()
    return when {
        AM_ANSWER.matches(text) -> false
        PM_ANSWER.matches(text) -> true
        tonight && lower in YES_ANSWER -> false
        tonight && lower in NO_ANSWER -> true
        else -> null
    }
}

/**
 * The am/pm question for a pending [BARE_TIME_PARAM], or null when none is pending. The question is
 * asked in place of "time" once the other required slots are filled.
 */
internal fun pendingTimeOfDaySlot(params: Map<String, String>): SlotSpec? {
    val bareTime = params[BARE_TIME_PARAM]?.takeIf { it.isNotBlank() } ?: return null
    if (!params["time"].isNullOrBlank()) return null
    val prompt = if (params["day"].equals("tonight", ignoreCase = true)) {
        val (hour, minute) = bareTime.split(":").map { it.toInt() }
        val shown = if (minute == 0) "$hour" else "$hour:%02d".format(minute)
        "Do you mean $shown in the morning? Yes or no."
    } else {
        "Morning or evening?"
    }
    return SlotSpec(name = TIME_OF_DAY_SLOT, promptTemplate = prompt)
}
