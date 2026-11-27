package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.slot.SlotSpec
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * Bare-hour rule for reminder times (#2132, #2146).
 *
 * A clock hour 1 to 11 given with no am/pm and no day-part word ("7", "6:30", "7 o'clock", "six")
 * is ambiguous. Then: "tonight" / "this evening" makes it PM on that day (hours 5 to 11); hours 1 to 4
 * with "tonight" ask which; a day other than today asks "Morning or evening?"; no day means the
 * next occurrence. Hour 0, hour 12, 24-hour times, and any am/pm or day-part word keep their meaning.
 * "tonight" with an explicit morning time ("6 in the morning", "7am") is the next occurrence of that clock time.
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

/** Words of an answer to "Morning or evening?" once fillers are dropped. */
private val AM_WORDS = setOf("morning", "am")
private val PM_WORDS = setOf("evening", "afternoon", "night", "pm", "tonight")
private val ANSWER_FILLERS = setOf("please", "pls", "thanks", "thank", "you", "mate")
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
    // "tonight" and "this evening" are the evening cue, not a day part: "this evening at 7" is a bare 7.
    if (DAY_PART.containsMatchIn(EVENING_CUE.replace(cleaned, " "))) return null

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

/** The next time [hour]:[minute] reads after [now], as today or tomorrow. A time equal to [now] counts as passed. */
private fun nextOccurrence(hour: Int, minute: Int, now: LocalDateTime): Pair<LocalTime, String> {
    val time = LocalTime.of(hour, minute)
    return if (now.toLocalDate().atTime(time).isAfter(now)) time to "today" else time to "tomorrow"
}

internal fun clockText(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/**
 * Schedule params for a reminder time phrase (#2132, #2146).
 *
 * [QuickIntentRouter.parseAlarmTime] runs first, so its day, label and compact times are kept.
 * [dayWord] is the day the utterance or reply gave, normalised, or null. [eveningCue] is a "tonight" or
 * "this evening" cue in the utterance. "tonight" as a day counts as the same cue.
 */
internal fun reminderScheduleParams(
    timePhrase: String,
    dayWord: String?,
    eveningCue: Boolean,
    now: LocalDateTime,
): Map<String, String> {
    val parsed = QuickIntentRouter.parseAlarmTime(timePhrase)
    val day = dayWord ?: parsed["day"]
    val evening = eveningCue || day == "tonight"
    val sameDay = day == null || day == "today" || day == "tonight"
    val bare = bareHourIn(timePhrase)
    if (bare == null) {
        val hours = parsed["hours"]?.toInt()
        // "tonight at 6 in the morning" / "tonight at 7am": the next time that clock reads, not tonight.
        if (evening && hours != null && hours in 1..11) {
            val (time, dayFor) = nextOccurrence(hours, parsed.getValue("minutes").toInt(), now)
            return parsed.filterKeys { it != "time" && it != "hours" && it != "minutes" && it != "day" } +
                mapOf("time" to clockText(time.hour, time.minute), "day" to dayFor)
        }
        return buildMap {
            putAll(parsed)
            if (day != null) put("day", day)
            if (evening && sameDay) {
                put("day", "today")
                put(DAY_CUE_PARAM, "tonight")
            }
        }
    }
    return parsed.filterKeys { it != "time" && it != "hours" && it != "minutes" } +
        bareHourParams(bare, day, evening, sameDay, now)
}

private fun bareHourParams(
    bare: BareHour,
    day: String?,
    evening: Boolean,
    sameDay: Boolean,
    now: LocalDateTime,
): Map<String, String> {
    val bareTime = clockText(bare.hour, bare.minute)
    val pmTime = clockText(bare.hour + 12, bare.minute)
    // A question about the hour, on the day it was given (or no day).
    fun askOnDay(): Map<String, String> = buildMap {
        put(BARE_TIME_PARAM, bareTime)
        if (day != null) put("day", day)
    }
    return when {
        // "tonight at 1" could be 1 am tomorrow or 1 pm today: ask which.
        evening && bare.hour <= 4 && sameDay -> mapOf(BARE_TIME_PARAM to bareTime, "day" to "tonight")
        // "this evening at 3" on another day: the hour could be morning or evening, so ask.
        evening && bare.hour <= 4 -> askOnDay()
        evening && sameDay -> mapOf("time" to pmTime, "day" to "today", DAY_CUE_PARAM to "tonight")
        // "this evening at 7" on tomorrow: pm on that day, no question.
        evening -> buildMap {
            put("time", pmTime)
            if (day != null) put("day", day)
        }
        // A day other than today: the hour could be morning or evening, so ask.
        !sameDay -> askOnDay()
        else -> {
            val time = resolveBareHour(bare, evening = false, now = now)
            buildMap {
                put("time", clockText(time.hour, time.minute))
                if (day != null) put("day", day)
            }
        }
    }
}

/**
 * The answer to a pending [BARE_TIME_PARAM] question, as the time and day it gives. Null when the reply is
 * not an answer. With [day] "tonight" the question is "1 in the morning?" and yes/no answer it; otherwise
 * the reply is morning/evening, am/pm, or a time that names its own day part ("6 pm", "7 in the evening").
 */
internal fun timeOfDayParams(
    bareTime: String?,
    day: String?,
    reply: String,
    now: LocalDateTime,
): Map<String, String>? {
    if (bareTime.isNullOrBlank()) return null
    explicitTimeOfDay(reply, day, now)?.let { return it }
    val tonight = day.equals("tonight", ignoreCase = true)
    val pm = pmAnswer(reply, tonight) ?: return null
    val (hour, minute) = bareTime.split(":").map { it.toInt() }
    return when {
        tonight && pm -> mapOf("time" to clockText(hour + 12, minute), "day" to "today", DAY_CUE_PARAM to "tonight")
        tonight -> nextOccurrence(hour, minute, now).let { (time, dayWord) ->
            mapOf("time" to clockText(time.hour, time.minute), "day" to dayWord)
        }
        else -> buildMap {
            put("time", clockText(if (pm) hour + 12 else hour, minute))
            if (day != null) put("day", day)
        }
    }
}

/** A reply that names its own time and day part ("6 pm", "7 in the evening"), read as a time. Null otherwise. */
private fun explicitTimeOfDay(reply: String, day: String?, now: LocalDateTime): Map<String, String>? {
    val text = reply.trim()
    if (!AM_PM.containsMatchIn(text) && !DAY_PART.containsMatchIn(text)) return null
    val schedule = reminderScheduleParams(text, day, hasEveningCue(text), now)
    if (!schedule.containsKey("time")) return null
    return schedule.filterKeys { it == "time" || it == "day" || it == DAY_CUE_PARAM }
}

/**
 * true = PM, false = AM, null = not an answer. Fillers such as "please" are ignored. With [tonight] the
 * question is yes/no: yes is AM (the morning hour), no is PM.
 */
private fun pmAnswer(reply: String, tonight: Boolean): Boolean? {
    val words = reply.lowercase().replace(".", "").split(Regex("[^a-z]+"))
        .filter { it.isNotEmpty() && it !in ANSWER_FILLERS }
    if (tonight && words.size == 1 && words[0] in YES_ANSWER) return false
    if (tonight && words.size == 1 && words[0] in NO_ANSWER) return true
    val am = words.any { it in AM_WORDS }
    val pm = words.any { it in PM_WORDS }
    return when {
        am && !pm -> false
        pm && !am -> true
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
