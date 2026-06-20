package com.kernel.ai.core.skills.slot

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry of [SlotValidator]s, keyed by `(intentName, slotName)`.
 *
 * Validation rules are registered once at construction time and shared across
 * the slot-fill path ([SlotFillerManager]) and the dispatch path ([NativeIntentHandler]).
 *
 * Adding a new validator for an intent:
 * ```
 * registry.register("my_intent", "my_slot") { _, _, value ->
 *     if (isValid(value)) SlotValidationResult.valid()
 *     else SlotValidationResult.invalid("Please provide a valid value for my_slot.")
 * }
 * ```
 *
 * Extending the registry itself (preferred for launch-relevant families):
 * ```
 * class MyValidator : SlotValidator { ... }
 * // then add to the BUILDERS list in the companion object
 * ```
 */
@Singleton
class SlotValidationRegistry @Inject constructor() {

    private val validators = mutableMapOf<String, SlotValidator>()

    /**
     * Returns the validator registered for `(intentName, slotName)`, or null
     * when no specific validation rules exist (value passes through unchecked).
     */
    fun get(intentName: String, slotName: String): SlotValidator? = validators[key(intentName, slotName)]

    /**
     * Validates [value] against the registered validator for `(intentName, slotName)`.
     * Returns [SlotValidationResult.valid] when no validator is registered.
     */
    fun validate(intentName: String, slotName: String, value: String): SlotValidationResult {
        val validator = get(intentName, slotName) ?: return SlotValidationResult.valid()
        return validator.validate(intentName, slotName, value)
    }

    /**
     * Register a [SlotValidator] for `(intentName, slotName)`.
     * Overwrites any previously registered validator for the same key.
     */
    fun register(intentName: String, slotName: String, validator: SlotValidator) {
        validators[key(intentName, slotName)] = validator
    }

    private fun key(intentName: String, slotName: String): String = "$intentName\u0000$slotName"

    companion object {
        /**
         * Intents and slots that have registered validators.
         * Called once at construction time to populate the registry.
         */
        private val REGISTRATIONS: List<Triple<String, String, SlotValidator>> = listOf(
            Triple("set_timer", "duration_seconds", TimerDurationValidator),
            Triple("set_timer", "label", NonEmptyStringValidator("What should I call this timer?")),
            Triple("set_alarm", "time", AlarmTimeValidator),
            Triple("set_alarm", "day", AlarmDayValidator),
            Triple("get_weather", "location", WeatherLocationValidator),
        )
    }

    init {
        REGISTRATIONS.forEach { (intent, slot, validator) ->
            register(intent, slot, validator)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Built-in validators
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Validates timer duration values.
 *
 * Accepts: positive integers only (already normalised by [SlotSpec.normalizeDurationSlotReply]
 * which parses human-readable forms like "5 minutes", "30 seconds" into total seconds).
 */
object TimerDurationValidator : SlotValidator {
    override fun validate(intentName: String, slotName: String, value: String): SlotValidationResult {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return SlotValidationResult.invalid("How long would you like the timer for?")
        }
        val seconds = trimmed.toIntOrNull()
        if (seconds == null || seconds <= 0) {
            return SlotValidationResult.invalid("Sorry, I didn't understand that duration. How long should the timer be?")
        }
        // Reject obviously impossible durations (e.g. > 24 hours in seconds)
        if (seconds > 24 * 3600) {
            return SlotValidationResult.invalid("Timers can be at most 24 hours. How long should the timer be?")
        }
        return SlotValidationResult.valid()
    }
}

/**
 * Validates non-empty string slots (labels, names, etc.).
 */
class NonEmptyStringValidator(
    private val errorMessage: String,
) : SlotValidator {
    override fun validate(intentName: String, slotName: String, value: String): SlotValidationResult {
        if (value.isBlank()) {
            return SlotValidationResult.invalid(errorMessage)
        }
        return SlotValidationResult.valid()
    }
}

/**
 * Validates alarm time values.
 *
 * Accepts: any string that [QuickIntentRouter.resolveTime] can parse into a valid
 * [LocalTime] — supports "5pm", "7:30", "14:00", "9 o'clock", etc.
 */
object AlarmTimeValidator : SlotValidator {
    // Reuse the same resolution logic as the alarm dispatch path
    private val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""", RegexOption.IGNORE_CASE)

    override fun validate(intentName: String, slotName: String, value: String): SlotValidationResult {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return SlotValidationResult.invalid("What time should I set the alarm for?")
        }
        val cleaned = trimmed.lowercase()
            .replace(Regex("""\s*(o'clock|oclock)\s*"""), "")
            .trim()
        val match = timeRegex.find(cleaned)
        if (match == null) {
            return SlotValidationResult.invalid("Sorry, I didn't understand that time. What time should the alarm be?")
        }
        val hours = match.groupValues[1].toIntOrNull() ?: return SlotValidationResult.invalid(
            "Sorry, I didn't understand that time. What time should the alarm be?",
        )
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3].replace(".", "").lowercase()
        val computedHours = when {
            meridiem == "pm" && hours < 12 -> hours + 12
            meridiem == "am" && hours == 12 -> 0
            else -> hours
        }
        if (computedHours !in 0..23 || minutes !in 0..59) {
            return SlotValidationResult.invalid("Sorry, I didn't understand that time. What time should the alarm be?")
        }
        return SlotValidationResult.valid()
    }
}

/**
 * Validates alarm day values.
 *
 * Accepts: day names (monday, tuesday, ...) or "today", "tomorrow", or empty (no day specified).
 */
object AlarmDayValidator : SlotValidator {
    private val VALID_DAYS = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun",
        "today", "tomorrow",
    )

    override fun validate(intentName: String, slotName: String, value: String): SlotValidationResult {
        val trimmed = value.trim().lowercase()
        if (trimmed.isBlank()) return SlotValidationResult.valid() // day is optional
        val dayName = trimmed.removePrefix("next ").removePrefix("this ").trim()
        if (dayName in VALID_DAYS) return SlotValidationResult.valid()
        return SlotValidationResult.invalid(
            "Sorry, I didn't understand that day. Which day should the alarm be for?",
        )
    }
}

/**
 * Lightweight guard for weather location values.
 *
 * Only rejects clearly unusable input (blank, single non-alpha characters, digits-only
 * postal codes that are unlikely to be valid location names). Most free-text values
 * are passed through — the weather API is better at rejecting bad locations than we are.
 */
object WeatherLocationValidator : SlotValidator {
    override fun validate(intentName: String, slotName: String, value: String): SlotValidationResult {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return SlotValidationResult.invalid("Which city or area would you like the weather for?")
        }
        // Reject single non-letter characters ("?", ".", ",", "1")
        if (trimmed.length <= 1 && !trimmed[0].isLetter()) {
            return SlotValidationResult.invalid("Which city or area would you like the weather for?")
        }
        // Digits-only that look like postal codes are passed through
        // (geocoding APIs handle them). Only reject clearly unusable noise.
        return SlotValidationResult.valid()
    }
}
