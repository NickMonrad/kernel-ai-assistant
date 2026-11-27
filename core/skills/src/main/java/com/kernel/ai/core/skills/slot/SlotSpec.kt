package com.kernel.ai.core.skills.slot

import com.kernel.ai.core.skills.BARE_TIME_PARAM
import com.kernel.ai.core.skills.DAY_CUE_PARAM
import com.kernel.ai.core.skills.TIME_OF_DAY_SLOT
import com.kernel.ai.core.skills.hasEveningCue
import com.kernel.ai.core.skills.pendingTimeOfDaySlot
import com.kernel.ai.core.skills.reminderScheduleParams
import com.kernel.ai.core.skills.timeOfDayParams
import java.time.LocalDateTime

/**
 * Describes a required parameter that is missing from a matched intent, together with
 * a template for the clarifying question to ask the user.
 *
 * [promptTemplate] may reference existing params using {key} placeholders:
 *   e.g. "What would you like to say to {contact}?"
 */
data class SlotSpec(
    val name: String,
    val promptTemplate: String,
) {
    fun buildPrompt(existingParams: Map<String, String>): String =
        existingParams.entries.fold(promptTemplate) { acc, (k, v) -> acc.replace("{$k}", v) }
}


fun normalizeSlotReply(text: String, slotName: String): String {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return trimmed

    return when (slotName.lowercase()) {
        "contact" -> trimmed.replace(Regex("^(?:to|for)\\s+", RegexOption.IGNORE_CASE), "").trim()
        "date" -> normalizeDateSlotReply(trimmed)
        "time" -> normalizeTimeSlotReply(trimmed)
        "duration_seconds" -> normalizeDurationSlotReply(trimmed)
        "list_name" -> trimmed
        else -> trimmed
    }
}

/** Schedule keys a reminder reply can fill. */
private val REMINDER_SCHEDULE_KEYS = setOf("day", "time", BARE_TIME_PARAM, DAY_CUE_PARAM)

private val DAY_TOKEN = Regex(
    """\b((?:next\s+)?(?:today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thurs?|fri|sat|sun))\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Extracts reminder schedule values from one natural-language reply (#2132).
 *
 * A reply can fill both `day` and `time`, e.g. "tomorrow at 5 pm". A bare hour 1 to 11 with no am/pm
 * becomes [BARE_TIME_PARAM] and is asked am/pm. [existingDay] is the day already filled: it applies to
 * the reply's hour, but on its own it is not a schedule answer.
 */
fun parseReminderScheduleReply(
    text: String,
    existingDay: String? = null,
    now: LocalDateTime = LocalDateTime.now(),
): Map<String, String> {
    val cleaned = text.trim().trimEnd('.', '!', '?')
    if (cleaned.isBlank()) return emptyMap()

    val replyDay = DAY_TOKEN.find(cleaned)?.groupValues?.get(1)?.let(::normalizeReminderDay)
    val day = replyDay ?: existingDay?.let(::normalizeReminderDay)
    val schedule = reminderScheduleParams(cleaned, day, hasEveningCue(cleaned), now)
        .filterKeys { it in REMINDER_SCHEDULE_KEYS }
    if (replyDay == null && "time" !in schedule && BARE_TIME_PARAM !in schedule) return emptyMap()
    return schedule
}

/**
 * A reply to a reminder slot question, split into the text for the slot being asked and the
 * schedule values the same reply carries.
 *
 * [value] is null when the reply is only schedule words, so the asked slot has nothing to take.
 */
data class ReminderSlotReply(
    val value: String?,
    val schedule: Map<String, String>,
    /** The am/pm question got a reply that is not an answer: ask it again, store nothing. */
    val unanswered: Boolean = false,
)

private val REMINDER_SCHEDULE_SLOTS = setOf("day", "time")

private val REMINDER_DAY_PHRASE = "(?:today|tomorrow|tonight|(?:next\\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))"

// Times need am/pm or a colon, so a bare number inside an item ("call 3 people") is never a time.
// The dot after "a.m" / "p.m" is optional: replies lose their final dot when trailing punctuation is trimmed.
private val REMINDER_TIME_PHRASE = "(?:\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.?|p\\.m\\.?)|\\d{1,2}:\\d{2})"

/** A whole schedule phrase: "tomorrow", "on friday at 9am", "at 5 pm", "by 5pm on monday". */
private val REMINDER_SCHEDULE_PHRASE = Regex(
    "^(?:(?:on\\s+)?$REMINDER_DAY_PHRASE(?:\\s+(?:at|by)\\s+$REMINDER_TIME_PHRASE)?" +
        "|(?:at|by)\\s+$REMINDER_TIME_PHRASE(?:\\s+(?:on\\s+)?$REMINDER_DAY_PHRASE)?)$",
    RegexOption.IGNORE_CASE,
)

/**
 * A bare hour with a day or "this evening": "tomorrow at 6", "on monday at 3", "at 7 tomorrow",
 * "this evening at 7". The hour has no am/pm, so it is a day plus a bare hour, and the am/pm question follows.
 */
private val REMINDER_BARE_HOUR_PHRASE = Regex(
    "^(?:(?:at|by)\\s+\\d{1,2}(?::\\d{2})?\\s+(?:on\\s+)?$REMINDER_DAY_PHRASE" +
        "|(?:(?:on\\s+)?$REMINDER_DAY_PHRASE|this\\s+evening)\\s+(?:at|by)\\s+\\d{1,2}(?::\\d{2})?)$",
    RegexOption.IGNORE_CASE,
)

private fun isReminderSchedulePhrase(phrase: String): Boolean =
    REMINDER_SCHEDULE_PHRASE.matches(phrase) || REMINDER_BARE_HOUR_PHRASE.matches(phrase)

private fun parseReminderPhraseSchedule(text: String, existingDay: String?, now: LocalDateTime): Map<String, String> =
    parseReminderScheduleReply(text.trim().trimEnd('.', '!', '?'), existingDay, now)

/**
 * Splits a reply to a reminder slot question.
 *
 * For `day` and `time` the reply is the schedule itself, so [ReminderSlotReply.value] is null
 * whenever the reply carries schedule values. For the am/pm question ([TIME_OF_DAY_SLOT]) the reply is
 * morning or evening (yes or no when the hour was given with "tonight"). For any other missing slot (the
 * item) the reply is the item, unless it ends in a separate schedule phrase: "put the bins out at 5 pm"
 * gives item "put the bins out" and time 17:00, while "call 3 people" and "put on sun cream" stay whole.
 * "ring mum tomorrow at 6" gives item "ring mum" and day tomorrow; the bare hour is then asked am/pm.
 * A reply that is only a schedule phrase gives a null value.
 */
fun parseReminderSlotReply(
    text: String,
    missingSlot: String,
    existingParams: Map<String, String> = emptyMap(),
    now: LocalDateTime = LocalDateTime.now(),
): ReminderSlotReply {
    val trimmed = text.trim()
    val existingDay = existingParams["day"]
    if (missingSlot == TIME_OF_DAY_SLOT) {
        val schedule = timeOfDayParams(existingParams[BARE_TIME_PARAM], existingDay, trimmed, now)
            ?: return ReminderSlotReply(value = null, schedule = emptyMap(), unanswered = true)
        return ReminderSlotReply(value = null, schedule = schedule)
    }
    if (missingSlot in REMINDER_SCHEDULE_SLOTS) {
        val schedule = parseReminderPhraseSchedule(trimmed, existingDay, now)
        return ReminderSlotReply(value = if (schedule.isEmpty()) trimmed else null, schedule = schedule)
    }
    val phrase = trimmed.trimEnd('.', '!', '?')
    if (isReminderSchedulePhrase(phrase)) {
        val schedule = parseReminderPhraseSchedule(phrase, existingDay, now)
        if (schedule.isNotEmpty()) return ReminderSlotReply(value = null, schedule = schedule)
    }
    // Earliest split first, so the item keeps as many words as possible.
    for (separator in Regex("\\s+").findAll(phrase)) {
        val head = phrase.substring(0, separator.range.first).trim()
        val tail = phrase.substring(separator.range.last + 1)
        if (head.isEmpty() || !isReminderSchedulePhrase(tail)) continue
        val schedule = parseReminderPhraseSchedule(tail, existingDay, now)
        if (schedule.isNotEmpty()) return ReminderSlotReply(value = head, schedule = schedule)
    }
    return ReminderSlotReply(value = trimmed, schedule = emptyMap())
}

/**
 * The slot to ask next: the first required slot still blank, else the am/pm question for a pending
 * bare hour (#2132). While that question is pending it stands in for the blank "time".
 */
fun firstMissingSlot(requiredSlots: Map<String, SlotSpec>, params: Map<String, String>): SlotSpec? {
    val timeOfDay = pendingTimeOfDaySlot(params)
    return requiredSlots.entries
        .firstOrNull { (key, _) -> params[key].isNullOrBlank() && !(timeOfDay != null && key == "time") }
        ?.value
        ?: timeOfDay
}

/**
 * The params of a completed reminder. "tonight" is today for the handler, and [DAY_CUE_PARAM] keeps the word
 * for the past-time message. The am/pm question's own keys are not part of the reminder.
 */
fun completedSlotParams(params: Map<String, String>): Map<String, String> {
    val reminder = params - BARE_TIME_PARAM - TIME_OF_DAY_SLOT
    return if (reminder["day"] == "tonight") reminder + mapOf("day" to "today", DAY_CUE_PARAM to "tonight") else reminder
}


private fun normalizeReminderDay(raw: String): String {
    val maybeNext = if (raw.startsWith("next ", ignoreCase = true)) "next " else ""
    val day = raw.removePrefix("next ").removePrefix("Next ").lowercase()
    val normalized = when (day) {
        "mon" -> "monday"
        "tue", "tues" -> "tuesday"
        "wed" -> "wednesday"
        "thu", "thur", "thurs" -> "thursday"
        "fri" -> "friday"
        "sat" -> "saturday"
        "sun" -> "sunday"
        else -> day
    }
    return "$maybeNext$normalized"
}

private fun normalizeTimeSlotReply(trimmed: String): String {
    return trimmed.replace(Regex("^(?:for|at|by)\\s+", RegexOption.IGNORE_CASE), "").trim()
}

/**
 * Convert a natural-language duration string to total seconds.
 *
 * Supports patterns like "5 minutes", "30 seconds", "1 hour", "2 hours 30 minutes",
 * "minute 5", "a minute", "an hour", and simple word numbers ("two minutes", "one hour").
 * Returns the raw input if parsing fails, letting downstream code produce a clear error.
 */
private fun normalizeDurationSlotReply(trimmed: String): String {
    val input = trimmed.trim().lowercase()

    // Word-to-number mapping for simple English number words
    val wordNumbers = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
    )

    // Multiplier per time unit
    fun unitMultiplier(unit: String): Long? = when (unit) {
        "second", "seconds", "sec", "secs", "s" -> 1
        "minute", "minutes", "min", "mins", "m" -> 60
        "hour", "hours", "hr", "hrs", "h" -> 3600
        else -> null
    }

    // Try to extract a numeric value and unit
    fun parseValue(raw: String): Pair<Long, Long>? {
        // Pattern 1: "<value> <unit>"  e.g. "5 minutes", "2 hours"
        val simplePattern = Regex("""^(?:about\s+|around\s+)?(\d+)\s+(second|seconds|sec|secs|s|minute|minutes|min|mins|m|hour|hours|hr|hrs|h)\s*(?:and\s+)?(\d+)?\s*(second|seconds|sec|secs|s|minute|minutes|min|mins|m|hour|hours|hr|hrs|h)?$""")
        val m1 = simplePattern.matchEntire(raw)
        if (m1 != null) {
            val v1 = m1.groupValues[1].toLongOrNull() ?: return null
            val u1 = unitMultiplier(m1.groupValues[2]) ?: return null
            var total = v1 * u1
            if (m1.groupValues[3].isNotBlank() && m1.groupValues[4].isNotBlank()) {
                val v2 = m1.groupValues[3].toLongOrNull() ?: return null
                val u2 = unitMultiplier(m1.groupValues[4]) ?: return null
                total += v2 * u2
            }
            return Pair(total, u1.coerceAtLeast(1) / 60)
        }

        // Pattern 2: word number + unit  e.g. "five minutes", "two hours"
        val wordPattern = Regex("""^(?:about\s+|around\s+)?([a-z]+)\s+(second|seconds|sec|secs|s|minute|minutes|min|mins|m|hour|hours|hr|hrs|h)$""")
        val m2 = wordPattern.matchEntire(raw)
        if (m2 != null) {
            val number = wordNumbers[m2.groupValues[1]] ?: return null
            val mult = unitMultiplier(m2.groupValues[2]) ?: return null
            return Pair(number.toLong() * mult, mult)
        }

        // Pattern 3: "a minute", "an hour" — special case for "a"/"an"
        val aPattern = Regex("""^(?:a|an)\s+(second|seconds|sec|secs|s|minute|minutes|min|mins|m|hour|hours|hr|hrs|h)$""")
        val m3 = aPattern.matchEntire(raw)
        if (m3 != null) {
            val mult = unitMultiplier(m3.groupValues[1]) ?: return null
            return Pair(1 * mult, mult)
        }

        // Pattern 4: unit-first  e.g. "minute 5"
        val unitFirstPattern = Regex("""^(second|seconds|sec|secs|s|minute|minutes|min|mins|m|hour|hours|hr|hrs|h)\s+(\d+)$""")
        val m4 = unitFirstPattern.matchEntire(raw)
        if (m4 != null) {
            val mult = unitMultiplier(m4.groupValues[1]) ?: return null
            val number = m4.groupValues[2].toLongOrNull() ?: return null
            return Pair(number * mult, mult)
        }

        return null
    }

    val parsed = parseValue(input)
    return if (parsed != null) {
        parsed.first.toString()
    } else {
        // Return raw — downstream handler will produce a clear error
        trimmed
    }
}

private fun normalizeDateSlotReply(trimmed: String): String {
    DATE_SLOT_TRAILING_VALUE.matchEntire(trimmed)?.groupValues?.get(1)?.trim()?.let { return it }
    return trimmed.replace(Regex("^(?:on|for)\\s+", RegexOption.IGNORE_CASE), "").trim()
}

private val DATE_SLOT_TRAILING_VALUE = Regex(
    """(?i).*(?:\b(?:is|on|for)\b)\s+((?:\d{1,2}(?:st|nd|rd|th)?(?:\s+of)?\s+[a-zA-Z]+(?:\s+\d{4})?|[a-zA-Z]+\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?|\d{4}-\d{2}-\d{2}|\d{1,2}[/-]\d{1,2}[/-]\d{4}|today|tomorrow|tonight|(?:this|next)\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|week|month|year)|monday|tuesday|wednesday|thursday|friday|saturday|sunday))$""",
)