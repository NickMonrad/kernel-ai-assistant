package com.kernel.ai.core.skills.slot

import com.kernel.ai.core.skills.QuickIntentRouter

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
        "list_name" -> normalizeListSlotReply(trimmed)
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
fun parseReminderScheduleReply(text: String): Map<String, String> {
    val cleaned = text.trim().trimEnd('.', '!', '?')
    if (cleaned.isBlank()) return emptyMap()

    val params = linkedMapOf<String, String>()
    val dayMatch = Regex(
        """\b(today|tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thurs?|fri|sat|sun)\b""",
        RegexOption.IGNORE_CASE,
    ).find(cleaned)
    if (dayMatch != null) {
        params["day"] = normalizeReminderDay(dayMatch.groupValues[1])
    }

    QuickIntentRouter.parseAlarmTime(cleaned)["time"]?.let { params["time"] = it }
    return params
}

private fun normalizeReminderDay(raw: String): String = when (raw.lowercase()) {
    "tonight" -> "today"
    "mon" -> "monday"
    "tue", "tues" -> "tuesday"
    "wed" -> "wednesday"
    "thu", "thur", "thurs" -> "thursday"
    "fri" -> "friday"
    "sat" -> "saturday"
    "sun" -> "sunday"
    else -> raw.lowercase()
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

private fun normalizeListSlotReply(trimmed: String): String {
    val genericAlias = Regex(
        "^(?:(?:to|on|onto|in|into)\\s+)?(?:(?:my|the)\\s+)?(shopping|grocery|groceries|todo|to do|to-do)(?:\\s+list)?$",
        RegexOption.IGNORE_CASE,
    ).matchEntire(trimmed)
    return genericAlias?.groupValues?.get(1)?.lowercase()?.let(::canonicalGenericListAlias) ?: trimmed
}

private fun canonicalGenericListAlias(alias: String): String = when (alias) {
    "shopping", "grocery", "groceries" -> "shopping list"
    "todo", "to do", "to-do" -> "to-do list"
    else -> alias
}

private val DATE_SLOT_TRAILING_VALUE = Regex(
    """(?i).*(?:\b(?:is|on|for)\b)\s+((?:\d{1,2}(?:st|nd|rd|th)?(?:\s+of)?\s+[a-zA-Z]+(?:\s+\d{4})?|[a-zA-Z]+\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?|\d{4}-\d{2}-\d{2}|\d{1,2}[/-]\d{1,2}[/-]\d{4}|today|tomorrow|tonight|(?:this|next)\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|week|month|year)|monday|tuesday|wednesday|thursday|friday|saturday|sunday))$""",
)