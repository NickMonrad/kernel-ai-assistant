package com.kernel.ai.core.skills.slot

import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.bareHourIn
import com.kernel.ai.core.skills.resolveBareHour
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

/**
 * Extracts any reminder schedule values present in one natural-language slot reply.
 *
 * A single reply can fill both `day` and `time` (for example, "tomorrow at 5 pm").
 * Replies containing only one value return only that value so slot filling can prompt
 * for the remaining schedule field.
 */
fun parseReminderScheduleReply(
    text: String,
    existingDay: String? = null,
    now: LocalDateTime = LocalDateTime.now(),
): Map<String, String> {
    val cleaned = text.trim().trimEnd('.', '!', '?')
    if (cleaned.isBlank()) return emptyMap()

    val params = linkedMapOf<String, String>()
    val dayToken = Regex(
        """\b((?:next\s+)?(?:today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thurs?|fri|sat|sun))\b""",
        RegexOption.IGNORE_CASE,
    ).find(cleaned)?.groupValues?.get(1)
    if (dayToken != null) {
        params["day"] = normalizeReminderDay(dayToken)
    }

    // A bare hour 1 to 11 takes its meaning from "tonight" (this reply, or the earlier fill) or the clock (#2132).
    val evening = if (dayToken != null) dayToken.equals("tonight", ignoreCase = true) else existingDay.equals("tonight", ignoreCase = true)
    val bare = bareHourIn(cleaned)
    if (bare != null) {
        val time = resolveBareHour(bare, evening, now)
        params["time"] = "%02d:%02d".format(time.hour, time.minute)
    } else {
        QuickIntentRouter.parseAlarmTime(cleaned)["time"]?.let { params["time"] = it }
    }
    return params
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
 * A day with a bare hour: "tomorrow at 6", "on monday at 3". The hour has no am/pm, so it is not
 * a time. Only the day is taken and the time is still asked for, rather than guessing AM or PM.
 */
private val REMINDER_DAY_BARE_HOUR_PHRASE = Regex(
    "^(?:on\\s+)?$REMINDER_DAY_PHRASE\\s+(?:at|by)\\s+\\d{1,2}$",
    RegexOption.IGNORE_CASE,
)

private fun isReminderSchedulePhrase(phrase: String): Boolean =
    REMINDER_SCHEDULE_PHRASE.matches(phrase) || REMINDER_DAY_BARE_HOUR_PHRASE.matches(phrase)

/**
 * Schedule values in a reply phrase. A day with a bare hour contributes the day only, so the time is
 * still asked for, except for "tonight", where the hour is PM today (#2132).
 */
private fun parseReminderPhraseSchedule(text: String, existingDay: String?, now: LocalDateTime): Map<String, String> {
    val phrase = text.trim().trimEnd('.', '!', '?')
    val schedule = parseReminderScheduleReply(phrase, existingDay, now)
    val dayWithBareHour = REMINDER_DAY_BARE_HOUR_PHRASE.matches(phrase) && !phrase.contains("tonight", ignoreCase = true)
    return if (dayWithBareHour) schedule.filterKeys { it == "day" } else schedule
}

/**
 * Splits a reply to a reminder slot question.
 *
 * For `day` and `time` the reply is the schedule itself, so [ReminderSlotReply.value] is null
 * whenever the reply carries schedule values. For any other missing slot (the item) the reply is
 * the item, unless it ends in a separate schedule phrase: "put the bins out at 5 pm" gives item
 * "put the bins out" and time 17:00, while "call 3 people" and "put on sun cream" stay whole.
 * "ring mum tomorrow at 6" gives item "ring mum" and day tomorrow; the bare hour is not a time.
 * A reply that is only a schedule phrase gives a null value.
 */
fun parseReminderSlotReply(
    text: String,
    missingSlot: String,
    existingDay: String? = null,
    now: LocalDateTime = LocalDateTime.now(),
): ReminderSlotReply {
    val trimmed = text.trim()
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