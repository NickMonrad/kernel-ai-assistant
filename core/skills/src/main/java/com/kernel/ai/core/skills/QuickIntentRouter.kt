package com.kernel.ai.core.skills

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Tier 2: Fast Intent Layer — pure Kotlin regex + future BERT-tiny zero-shot classifier.
 *
 * Intercepts simple device actions (<30ms) before they reach Gemma-4 E4B (~2s GPU).
 * Two-stage pipeline:
 *   1. Regex exact match (<1ms, 0 memory)
 *   2. BERT-tiny zero-shot similarity (future: ~30ms, ~15MB model)
 *
 * If neither stage matches with sufficient confidence, returns [RouteResult.FallThrough]
 * and the input continues to Tier 3 (E4B).
 */
class QuickIntentRouter(
    private val classifier: IntentClassifier? = null,
    private val similarityThreshold: Float = 0.85f,
) {

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class RouteResult {
        /** Regex matched with certainty — execute directly. */
        data class RegexMatch(
            val intent: MatchedIntent,
        ) : RouteResult()

        /** BERT-tiny matched above threshold — execute (with optional confirmation). */
        data class ClassifierMatch(
            val intent: MatchedIntent,
            val confidence: Float,
            val needsConfirmation: Boolean,
        ) : RouteResult()

        /** No match — fall through to E4B. */
        data class FallThrough(
            val input: String,
            val bestGuess: MatchedIntent? = null,
            val bestConfidence: Float = 0f,
        ) : RouteResult()
    }

    data class MatchedIntent(
        val intentName: String,
        val params: Map<String, String>,
        val source: String = "regex",
    )

    // ── Classifier interface (for BERT-tiny or mock) ──────────────────────────

    interface IntentClassifier {
        data class Classification(val intentName: String, val confidence: Float)
        fun classify(input: String): Classification?
    }

    // ── Regex patterns ────────────────────────────────────────────────────────

    private data class IntentPattern(
        val intentName: String,
        val regex: Regex,
        val paramExtractor: (MatchResult, String) -> Map<String, String>,
    )

    private val patterns: List<IntentPattern> = listOf(
        // ── Flashlight ──
        // Pattern: "turn/switch on the torch" — verb + on + object
        IntentPattern(
            intentName = "toggle_flashlight_on",
            regex = Regex(
                """(?:turn|switch|put)\s+on\s+(?:the\s+)?(?:torch|flashlight|flash\s*light|light)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "turn the torch on" — verb + object + on
        IntentPattern(
            intentName = "toggle_flashlight_on",
            regex = Regex(
                """(?:turn|switch|put)\s+(?:the\s+)?(?:torch|flashlight|flash\s*light|light)\s+on""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "flashlight on" / "torch on" — object + on (no verb)
        IntentPattern(
            intentName = "toggle_flashlight_on",
            regex = Regex(
                """^(?:torch|flashlight|flash\s*light)\s+on$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "turn/switch off the torch" — verb + off + object
        IntentPattern(
            intentName = "toggle_flashlight_off",
            regex = Regex(
                """(?:turn|switch|put)\s+off\s+(?:the\s+)?(?:torch|flashlight|flash\s*light|light)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "turn the torch off" — verb + object + off
        IntentPattern(
            intentName = "toggle_flashlight_off",
            regex = Regex(
                """(?:turn|switch|put)\s+(?:the\s+)?(?:torch|flashlight|flash\s*light|light)\s+off""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "flashlight off" / "torch off" — object + off (no verb)
        IntentPattern(
            intentName = "toggle_flashlight_off",
            regex = Regex(
                """^(?:torch|flashlight|flash\s*light)\s+off$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Alarm ──
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """(?:set|create|make)\s+(?:an?\s+)?alarm\s+(?:for|at)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseAlarmTime(match.groupValues[1].trim()) },
        ),
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """(?:wake|get)\s+me\s+up\s+(?:at|by)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseAlarmTime(match.groupValues[1].trim()) },
        ),
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """alarm\s+(?:for|at)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseAlarmTime(match.groupValues[1].trim()) },
        ),
        // "remind me tomorrow at 9" / "remind me on friday at 7am" → set_alarm
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """remind\s+me\s+(?:on\s+)?(tomorrow|today|tonight|(?:next\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thurs?|fri|sat|sun))\s+(?:at|by)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                parseAlarmTime("${match.groupValues[1]} ${match.groupValues[2]}")
            },
        ),
        // "remind me at/by <time>" → set_alarm
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """remind\s+me\s+(?:at|by)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseAlarmTime(match.groupValues[1].trim()) },
        ),
        // "wake me at/by <time>" (without "up") → set_alarm
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """wake\s+me\s+(?:at|by)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseAlarmTime(match.groupValues[1].trim()) },
        ),
        // "alarm <time>" without preposition → set_alarm
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """^alarm\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm))""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseAlarmTime(match.groupValues[1].trim()) },
        ),
        // "<time> alarm" order → set_alarm
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """^(\d{1,2}(?::\d{2})?\s*(?:am|pm))\s+alarm\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseAlarmTime(match.groupValues[1].trim()) },
        ),

        // ── Cancel Alarm ──
        IntentPattern(
            intentName = "cancel_alarm",
            regex = Regex(
                """(?:cancel|delete|remove|dismiss|clear|turn\s+off|stop)\s+(?:my\s+|the\s+|an?\s+)?alarm""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Timer ──
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """(?:set|start|create)\s+(?:a\s+)?(?:timer|countdown)\s+(?:for\s+)?(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)(?:\s+(?:and\s+)?(\d+)\s*(minutes?|mins?|seconds?|secs?|m|s))?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, input -> parseTimerDuration(match, input) },
        ),
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """(?:timer|countdown)\s+(?:for\s+)?(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, input -> parseTimerDuration(match, input) },
        ),
        // "5 minute timer" / "5 minute egg timer" / "3 minute pasta timer"
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)\s+.*?timer""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, input -> parseTimerDuration(match, input) },
        ),
        // "remind me in <N> <unit>" → set_timer
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """remind\s+me\s+in\s+(\d+)\s*(hours?|minutes?|mins?|seconds?|secs?|h|m|s)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, input -> parseTimerDuration(match, input) },
        ),
        // "time me for <N> <unit>" → set_timer
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """time\s+me\s+(?:for\s+)?(\d+)\s*(hours?|minutes?|mins?|seconds?|secs?|h|m|s)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, input -> parseTimerDuration(match, input) },
        ),

        // ── Cancel Timer ──
        IntentPattern(
            intentName = "cancel_timer",
            regex = Regex(
                """(?:cancel|stop|clear|end|dismiss)\s+(?:my\s+|the\s+|a\s+)?(?:timer|countdown)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Calendar ──
        // "add/create/schedule/set up a [dentist/gym/meeting/event] [for/on] [date] [at time]"
        IntentPattern(
            intentName = "create_calendar_event",
            regex = Regex(
                """(?:add|create|schedule|put|book|set)\s+(?:a\s+|an\s+)?(?:calendar\s+)?(?:event|appointment|meeting|entry|invite|session|booking)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, raw -> extractCalendarHints(raw) },
        ),
        IntentPattern(
            intentName = "create_calendar_event",
            regex = Regex(
                """(?:set\s+up|schedule)\s+(?:a\s+|an\s+)?meeting\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, raw -> extractCalendarHints(raw) },
        ),
        IntentPattern(
            intentName = "create_calendar_event",
            regex = Regex(
                """block\s+(?:out\s+)?(?:time\s+)?(?:on\s+(?:my\s+)?calendar\b|(?:this|next)\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|week|morning|afternoon|evening)\b|tomorrow\b)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, raw -> extractCalendarHints(raw) },
        ),
        IntentPattern(
            intentName = "create_calendar_event",
            regex = Regex(
                """put\s+.{3,40}\s+(?:in|on|into)\s+(?:my\s+)?calendar\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, raw -> extractCalendarHints(raw) },
        ),
        // "invite Sarah to my Friday dinner" / "invite John and Sarah to the team meeting"
        IntentPattern(
            intentName = "create_calendar_event",
            regex = Regex(
                """^invite\s+.+\s+to\s+(?:my\s+|the\s+|a\s+|an\s+)?.{3,}""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, raw -> extractCalendarHints(raw) },
        ),
        // "set a dental appointment" / "book a hair appointment" — requires article "a/an" so
        // bare-noun forms like "add dentist appointment to my calendar" fall through to LLM.
        IntentPattern(
            intentName = "create_calendar_event",
            regex = Regex(
                """(?:add|create|schedule|put|book|set)\s+(?:a|an)\s+(?:\S+\s+){1,4}?(?:appointment|meeting|event|session|booking)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, raw -> extractCalendarHints(raw) },
        ),

        // ── Do Not Disturb ──
        IntentPattern(
            intentName = "toggle_dnd_on",
            regex = Regex(
                """(?:turn|switch|enable|put)\s+(?:on\s+)?(?:do\s+not\s+disturb|dnd|silent\s+mode|quiet\s+mode)(?:\s+on)?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // "silence/mute my phone/notifications" → toggle_dnd_on
        IntentPattern(
            intentName = "toggle_dnd_on",
            regex = Regex(
                """(?:silence|mute|hush)\s+(?:my\s+)?(?:phone|notifications?)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "toggle_dnd_on",
            regex = Regex(
                """(?:silence|quieten?|mute)\s+(?:my\s+)?(?:phone|notifications?|ringer|sounds?)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "toggle_dnd_off",
            regex = Regex(
                """(?:turn|switch|disable|put)\s+(?:off\s+)?(?:do\s+not\s+disturb|dnd|silent\s+mode|quiet\s+mode)(?:\s+off)?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Battery ──
        IntentPattern(
            intentName = "get_battery",
            regex = Regex(
                """(?:battery|charge|power)\s+(?:level|status|percentage|left|remaining)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "get_battery",
            regex = Regex(
                """how\s+much\s+(?:battery|charge|power)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "get_battery",
            regex = Regex(
                """(?:check|show|get|what(?:'s| is))\s+(?:my\s+|the\s+)?(?:battery|charge|power)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // "am I / is my phone charging?" → get_battery
        IntentPattern(
            intentName = "get_battery",
            regex = Regex(
                """(?:am\s+i|is\s+(?:my\s+)?(?:phone|device))\s+charging""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // "how's my battery?" → get_battery
        IntentPattern(
            intentName = "get_battery",
            regex = Regex(
                """how(?:'?s|\s+is)\s+my\s+(?:battery|charge|power)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Time / Date ──
        // query_type drives the response format in NativeIntentHandler.getTime():
        //   "time"        → just the time (e.g. "It's 7:27 PM")
        //   "date"        → just the date (e.g. "Today is Thursday, 16 April 2026")
        //   "day_of_week" → just the weekday name
        //   "year"        → just the year
        //   "month"       → just the month name
        //   "week"        → week number
        //   (absent)      → full datetime (fallback)
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what(?:'s| is)\s+(?:the\s+)?(?:current\s+)?(time|date|day)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                when (match.groupValues[1].lowercase()) {
                    "time" -> mapOf("query_type" to "time")
                    "date" -> mapOf("query_type" to "date")
                    "day" -> mapOf("query_type" to "day_of_week")
                    else -> emptyMap()
                }
            },
        ),
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what\s+time\s+is\s+it""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("query_type" to "time") },
        ),
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """(?:tell|give)\s+me\s+(?:the\s+)?(time|date)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                when (match.groupValues[1].lowercase()) {
                    "time" -> mapOf("query_type" to "time")
                    "date" -> mapOf("query_type" to "date")
                    else -> emptyMap()
                }
            },
        ),
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what\s+(day|date)\s+is\s+(?:it|today)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                when (match.groupValues[1].lowercase()) {
                    "day" -> mapOf("query_type" to "day_of_week")
                    "date" -> mapOf("query_type" to "date")
                    else -> emptyMap()
                }
            },
        ),
        // "what day of the week is it" — explicit pattern so it doesn't fall to LLM
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what\s+day\s+of\s+the\s+week\s+(?:is\s+it|it\s+is|are\s+we\s+in)\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("query_type" to "day_of_week") },
        ),
        // Pattern: "what's today's date" / "what is today's date" (must be before broader "what is today")
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what(?:'s| is)\s+today'?s\s+(?:date|day)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("query_type" to "date") },
        ),
        // Pattern: "what is today" / "what's today"
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what(?:'s| is)\s+today""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("query_type" to "date") },
        ),
        // Pattern: "what year is it" / "what year are we in"
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what\s+year\s+(?:is\s+it|are\s+we\s+in|is\s+this)\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("query_type" to "year") },
        ),
        // Pattern: "what year is it currently" / "what's the year"
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what(?:'s| is)\s+(?:the\s+)?(?:current\s+)?year\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("query_type" to "year") },
        ),
        // Pattern: "is it still Monday" / "is it Monday today" / "is today Monday" / "is it Friday today"
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """(?:is\s+it\s+(?:still\s+)?|is\s+today\s+)(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\s+today)?\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("query_type" to "day_of_week") },
        ),
        // Pattern: "what month is it" / "what week is it" / "what month are we in"
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what\s+(month|week)\b\s+(?:is\s+it|are\s+we\s+in|is\s+this)\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                when (match.groupValues[1].lowercase()) {
                    "month" -> mapOf("query_type" to "month")
                    "week" -> mapOf("query_type" to "week")
                    else -> emptyMap()
                }
            },
        ),
        // Pattern: "what's the month" / "what's the week"
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what(?:'s| is)\s+(?:the\s+)?(?:current\s+)?(month|week)\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                when (match.groupValues[1].lowercase()) {
                    "month" -> mapOf("query_type" to "month")
                    "week" -> mapOf("query_type" to "week")
                    else -> emptyMap()
                }
            },
        ),

        // ── Weather ──
        // City-named weather: "weather in Auckland" / "what's the weather in London" /
        // "weather forecast for Paris" — captures city into `location` param.
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what(?:'s| is)\s+(?:the\s+)?weather(?:\s+(?:like|today|tonight|now))?\s+in\s+|how(?:'s|\s+is)\s+(?:the\s+)?weather\s+in\s+|weather(?:\s+forecast)?\s+(?:in|for|at)\s+)([\w\s,]+?)(?:\s+today|\s+tonight|\s+now|\s+forecast|\s+this\s+week)?\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("location" to match.groupValues[1].trim()) },
        ),
        // GPS weather: current location queries with no city mentioned.
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what(?:'s| is)\s+(?:the\s+)?weather(?:\s+(?:like|today|tonight|now|outside|currently))?|how(?:'s|\s+is)\s+(?:the\s+)?weather(?:\s+(?:today|tonight|now|outside))?|weather\s+(?:today|tonight|now|outside|forecast|this\s+week))\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Precipitation: "will it rain", "is it raining", "do I need an umbrella"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:will\s+it\s+rain|is\s+it\s+raining|do\s+i\s+need\s+(?:an?\s+)?umbrella|chance\s+of\s+rain)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Temperature queries: "how hot/cold is it", "what's the temperature outside"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:how\s+(?:hot|cold|warm)\s+is\s+it|what(?:'s| is)\s+(?:the\s+)?temperature(?:\s+(?:outside|today|now|currently))?|temperature\s+(?:outside|today|now|currently))""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // UV index queries: "what's the UV index", "is the UV high today"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what(?:'s| is)\s+(?:the\s+)?uv\s+index|is\s+(?:the\s+)?uv\s+(?:high|low|bad|strong)|uv\s+index(?:\s+(?:today|now|currently))?|how\s+(?:high|bad|strong)\s+is\s+(?:the\s+)?uv)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Air quality queries: "what's the air quality", "what's the AQI"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what(?:'s| is)\s+(?:the\s+)?(?:air\s+quality|aqi)|how(?:'s|\s+is)\s+(?:the\s+)?air\s+quality|air\s+quality(?:\s+(?:today|now|index))?|is\s+(?:the\s+)?air\s+(?:clean|dirty|polluted|bad|good))""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Sunrise/sunset queries: "what time is sunrise", "when does the sun set"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what\s+time\s+is\s+(?:sunrise|sunset)|when\s+(?:does\s+(?:the\s+)?sun\s+(?:rise|set)|is\s+(?:sunrise|sunset))|sunrise\s+(?:time|today)?|sunset\s+(?:time|today)?)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Volume ──
        IntentPattern(
            intentName = "set_volume",
            regex = Regex(
                """(?:set\s+)?(?:turn\s+(?:the\s+)?)?volume(?:\s+(?:up|down))?\s+(?:to\s+|at\s+)?(\d+)\s*(%)?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                mapOf(
                    "value" to match.groupValues[1],
                    "is_percent" to if (match.groupValues[2].isNotBlank()) "true" else "false",
                )
            },
        ),

        // ── WiFi ──
        IntentPattern(
            intentName = "toggle_wifi",
            regex = Regex(
                """(?:turn\s+)?(?:on|off|enable|disable|switch\s+(?:on|off))\s+(?:wi-?fi|wireless)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.lowercase().contains(Regex("""\b(on|enable)\b"""))) "on" else "off"
                mapOf("state" to state)
            },
        ),
        IntentPattern(
            intentName = "toggle_wifi",
            regex = Regex(
                """(?:switch\s+)?(?:wi-?fi|wireless)\s+(?:on|off)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.lowercase().contains(Regex("""\b(on|enable)\b"""))) "on" else "off"
                mapOf("state" to state)
            },
        ),

        // ── Bluetooth ──
        IntentPattern(
            intentName = "toggle_bluetooth",
            regex = Regex(
                """(?:turn\s+)?(?:on|off|enable|disable)\s+(?:bluetooth|bt)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.lowercase().contains(Regex("""\b(on|enable)\b"""))) "on" else "off"
                mapOf("state" to state)
            },
        ),
        IntentPattern(
            intentName = "toggle_bluetooth",
            regex = Regex(
                """(?:bluetooth|bt)\s+(?:on|off)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.lowercase().contains(Regex("""\b(on|enable)\b"""))) "on" else "off"
                mapOf("state" to state)
            },
        ),

        // ── Airplane Mode ──
        IntentPattern(
            intentName = "toggle_airplane_mode",
            regex = Regex(
                """(?:turn\s+)?(?:on|off|enable|disable)\s+(?:airplane|flight)\s+mode""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.lowercase().contains(Regex("""\b(on|enable)\b"""))) "on" else "off"
                mapOf("state" to state)
            },
        ),
        IntentPattern(
            intentName = "toggle_airplane_mode",
            regex = Regex(
                """(?:airplane|flight)\s+mode\s+(?:on|off)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.lowercase().contains(Regex("""\b(on|enable)\b"""))) "on" else "off"
                mapOf("state" to state)
            },
        ),

        // ── Hotspot ──
        IntentPattern(
            intentName = "toggle_hotspot",
            regex = Regex(
                """(?:turn\s+)?(?:on|off|enable|disable)\s+(?:hot\s*spot|tethering|mobile\s+hotspot)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.lowercase().contains(Regex("""\b(on|enable)\b"""))) "on" else "off"
                mapOf("state" to state)
            },
        ),
        IntentPattern(
            intentName = "toggle_hotspot",
            regex = Regex(
                """(?:mobile\s+)?(?:hot\s*spot|tethering)\s+(?:on|off)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.lowercase().contains(Regex("""\b(on|enable)\b"""))) "on" else "off"
                mapOf("state" to state)
            },
        ),

        // ── Media (most specific first) ──
        // Plex — matches before generic play
        IntentPattern(
            intentName = "play_plex",
            regex = Regex(
                """(?:play|watch)\s+(.+?)\s+on\s+plex""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("title" to match.groupValues[1].trim()) },
        ),
        // YouTube — "play X on youtube" / "watch X on youtube"
        IntentPattern(
            intentName = "play_youtube",
            regex = Regex(
                """(?:play|watch|search)\s+(.+?)\s+on\s+youtube""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // Spotify — "play X on spotify"
        IntentPattern(
            intentName = "play_spotify",
            regex = Regex(
                """(?:play|listen\s+to)\s+(.+?)\s+on\s+spotify""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // Netflix — "play X on netflix" / "watch X on netflix"
        IntentPattern(
            intentName = "play_netflix",
            regex = Regex(
                """(?:play|watch)\s+(.+?)\s+on\s+netflix""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // Open app — "open YouTube" / "launch Spotify"
        // Excludes timer/countdown phrases that start with "start" to prevent false matches
        // Also excludes "new conversation/chat" to prevent false matches on conversational phrases
        IntentPattern(
            intentName = "open_app",
            regex = Regex(
                """^(?:open|launch|start)\s+(?:the\s+)?(?!(?:a\s+)?(?:count(?:down|ing)|timer|alarm|new\s+conversation|new\s+chat|conversation|chat)\b)(.+?)(?:\s+app)?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("app_name" to match.groupValues[1].trim()) },
        ),
        // Album — matches before generic play
        IntentPattern(
            intentName = "play_media_album",
            regex = Regex(
                """play\s+(?:the\s+)?album\s+(.+?)(?:\s+(?:by|from)\s+(.+))?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf("album" to match.groupValues[1].trim())
                if (match.groupValues[2].isNotBlank()) params["artist"] = match.groupValues[2].trim()
                params
            },
        ),
        // Playlist — matches before generic play
        IntentPattern(
            intentName = "play_media_playlist",
            regex = Regex(
                """play\s+(?:(?:my|the)\s+)?(?:(.+?)\s+)?playlist(?:\s+(.+))?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val name = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    ?: match.groupValues[2].trim()
                mapOf("playlist" to name)
            },
        ),
        // Generic play — MUST come after plex/album/playlist
        IntentPattern(
            intentName = "play_media",
            regex = Regex(
                """play\s+(.+?)(?:\s+(?:by|from)\s+(.+))?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf("query" to match.groupValues[1].trim())
                if (match.groupValues[2].isNotBlank()) params["artist"] = match.groupValues[2].trim()
                params
            },
        ),

        // ── Navigation ──
        IntentPattern(
            intentName = "navigate_to",
            regex = Regex(
                """(?:navigate|directions?|drive|take\s+me|get\s+me)(?:\s+to)?\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("destination" to match.groupValues[1].trim()) },
        ),
        // ── Find Nearby (most specific first to avoid greedy mis-capture) ──
        // "find me nearby cafes" — verb + me + nearby + query
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """(?:find|show|get)\s+me\s+nearby\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // "find me cafes nearby" — verb + me + query + location marker
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """(?:find|show|get)\s+me\s+(.+?)\s+(?:near(?:by|\s+me)|close\s+by|around\s+(?:here|me))""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // "locate nearest pharmacy" — verb + (the)? + nearest + query
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """(?:find|show\s+me|search\s+for|look\s+for|locate)\s+(?:the\s+)?nearest\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // "find nearby restaurants" — verb + nearby + query (no "me")
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """(?:find|search\s+for|look\s+for|locate)\s+nearby\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // "find cafes nearby" / "show me petrol stations close by" — general pattern
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """(?:find|show\s+me|look\s+for|search\s+for|locate)\s+(.+?)\s+(?:near(?:by|\s+me)|close\s+by|around\s+(?:here|me))""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),

        // ── Communication ──
        IntentPattern(
            intentName = "make_call",
            regex = Regex(
                """^(?:call|ring|dial|phone)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("contact" to match.groupValues[1].trim()) },
        ),
        // "send a text to John saying hello" / "text John hey" / "sms John meet at 5"
        IntentPattern(
            intentName = "send_sms",
            regex = Regex(
                """^(?:send\s+(?:a\s+)?(?:text|sms|message)|text|sms)\s+(?:(?:message\s+)?to\s+)?(.+?)(?:\s+(?:saying|that|with\s+message)\s+(.+))?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf("contact" to match.groupValues[1].trim())
                val msg = match.groupValues[2].trim()
                if (msg.isNotBlank()) params["message"] = msg
                params
            },
        ),
        // "send an email to John about meeting" / "email John with body Please review"
        IntentPattern(
            intentName = "send_email",
            regex = Regex(
                """^(?:send\s+(?:an?\s+)?email|email)\s+(?:to\s+)?(.+?)""" +
                    """(?:\s+(?:about|regarding|re|subject:?)\s+(.+?))?""" +
                    """(?:\s+(?:body|message|saying|containing)\s+(.+))?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf("contact" to match.groupValues[1].trim())
                val subject = match.groupValues[2].trim()
                val body = match.groupValues[3].trim()
                if (subject.isNotBlank()) params["subject"] = subject
                if (body.isNotBlank()) params["body"] = body
                params
            },
        ),

        // ── System Info ──
        // "show my device info" / "what are my device specs" / "show system info"
        IntentPattern(
            intentName = "get_system_info",
            regex = Regex(
                """(?:show|get|what(?:'s|\s+are)?)\s+(?:my\s+)?(?:device\s+(?:info|specs|details)|system\s+info(?:rmation)?)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Lists ──
        IntentPattern(
            intentName = "add_to_list",
            regex = Regex(
                """add\s+(.+?)\s+to\s+(?:(?:my|the)\s+)?(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                mapOf(
                    "item" to match.groupValues[1].trim(),
                    "list_name" to match.groupValues[2].trim(),
                )
            },
        ),
        // "put milk on my shopping list" / "put eggs on the grocery list"
        IntentPattern(
            intentName = "add_to_list",
            regex = Regex(
                """put\s+(.+?)\s+on\s+(?:(?:my|the)\s+)?(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                mapOf(
                    "item" to match.groupValues[1].trim(),
                    "list_name" to match.groupValues[2].trim(),
                )
            },
        ),
        // "create a groceries list" / "make a new shopping list" / "start a meal plan list"
        IntentPattern(
            intentName = "create_list",
            regex = Regex(
                """(?:create|make|start|new)\s+(?:a\s+|an\s+|my\s+|new\s+)?(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("list_name" to match.groupValues[1].trim()) },
        ),
        // "remove milk from my shopping list" / "take eggs off the grocery list"
        IntentPattern(
            intentName = "remove_from_list",
            regex = Regex(
                """(?:remove|delete|take|cross off)\s+(.+?)\s+(?:from|off)\s+(?:(?:my|the)\s+)?(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                mapOf(
                    "item" to match.groupValues[1].trim(),
                    "list_name" to match.groupValues[2].trim(),
                )
            },
        ),
        // "what's on my shopping list" / "show me my grocery list" / "read out my to-do list"
        IntentPattern(
            intentName = "get_list_items",
            regex = Regex(
                """(?:what(?:'s|\s+is)\s+on|show(?:\s+me)?|read(?:\s+out)?|get|list)\s+(?:(?:my|the)\s+)?(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("list_name" to match.groupValues[1].trim()) },
        ),

        // ── Save Memory ──
        // Pattern: "save [to/that/...] memory that X" / "save this to memory: X"
        IntentPattern(
            intentName = "save_memory",
            regex = Regex(
                """(?:save|store|keep)\s+(?:to\s+memory|in\s+memory|that|this|it)\s*[:\-–]?\s*(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("content" to match.groupValues[1].trim()) },
        ),
        // Pattern: "remember that X" / "remember: X"
        IntentPattern(
            intentName = "save_memory",
            regex = Regex(
                """remember\s+(?:that\s+)?[:\-–]?\s*(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("content" to match.groupValues[1].trim()) },
        ),
        // Pattern: "note that X" / "make a note that X" / "don't forget that X"
        IntentPattern(
            intentName = "save_memory",
            regex = Regex(
                """(?:(?:make\s+a\s+)?note|don't\s+forget)\s+(?:that\s+)?[:\-–]?\s*(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("content" to match.groupValues[1].trim()) },
        ),
        // Pattern: "my X is Y" — captures personal facts like "my dog's name is Biscuit"
        // Anchored to start of message and requires "my" to reduce false positives.
        IntentPattern(
            intentName = "save_memory",
            regex = Regex(
                """^my\s+(.{3,60})\s+is\s+(.{1,100})$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, raw ->
                mapOf("content" to raw.trim())
            },
        ),

        // ── Brightness ──
        IntentPattern(
            intentName = "set_brightness",
            regex = Regex(
                """(?:increase|raise|turn\s+up|brighten|max(?:imize)?)\s+(?:the\s+)?(?:screen\s+)?brightness""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("direction" to "up") },
        ),
        IntentPattern(
            intentName = "set_brightness",
            regex = Regex(
                """(?:decrease|reduce|lower|turn\s+down|dim|min(?:imize)?)\s+(?:the\s+)?(?:screen\s+)?brightness""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("direction" to "down") },
        ),
        IntentPattern(
            intentName = "set_brightness",
            regex = Regex(
                """brightness\s+(?:up|down|max|min|full|half|low)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, raw ->
                val direction = if (raw.lowercase().contains(Regex("up|max|full"))) "up" else "down"
                mapOf("direction" to direction)
            },
        ),
        IntentPattern(
            intentName = "set_brightness",
            regex = Regex(
                """(?:set|change)\s+(?:the\s+)?(?:screen\s+)?brightness\s+(?:to\s+)?(\d+)\s*%?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                mapOf("value" to match.groupValues[1], "is_percent" to "true")
            },
        ),

        // ── Smart Home (MUST BE LAST — most generic) ──
        // Exclude media/computing devices that the phone can't directly control.
        IntentPattern(
            intentName = "smart_home_on",
            regex = Regex(
                """(?:turn|switch)\s+on\s+(?!.*\b(?:tv|television|music|tunes|spotify|netflix|youtube|plex)\b)(?:the\s+)?(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("device" to match.groupValues[1].trim()) },
        ),
        IntentPattern(
            intentName = "smart_home_off",
            regex = Regex(
                """(?:turn|switch)\s+off\s+(?!.*\b(?:tv|television|music|tunes|spotify|netflix|youtube|plex)\b)(?:the\s+)?(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("device" to match.groupValues[1].trim()) },
        ),
    )

    // ── Main routing ──────────────────────────────────────────────────────────

    fun route(input: String): RouteResult {
        val trimmed = input.trim()

        // Stage 1: Regex
        for (pattern in patterns) {
            val match = pattern.regex.find(trimmed)
            if (match != null) {
                val params = pattern.paramExtractor(match, trimmed)
                return RouteResult.RegexMatch(
                    MatchedIntent(
                        intentName = pattern.intentName,
                        params = params,
                        source = "regex",
                    ),
                )
            }
        }

        // Stage 2: BERT-tiny classifier (if available)
        classifier?.let { cls ->
            val result = cls.classify(trimmed)
            if (result != null && result.confidence >= similarityThreshold) {
                return RouteResult.ClassifierMatch(
                    intent = MatchedIntent(
                        intentName = result.intentName,
                        params = emptyMap(), // Classifier doesn't extract params — E4B does
                        source = "classifier",
                    ),
                    confidence = result.confidence,
                    needsConfirmation = result.confidence < 0.90f,
                )
            }
            // Below threshold — report as fallthrough with best guess
            if (result != null) {
                return RouteResult.FallThrough(
                    input = trimmed,
                    bestGuess = MatchedIntent(result.intentName, emptyMap(), "classifier"),
                    bestConfidence = result.confidence,
                )
            }
        }

        // Stage 3: Fall through to E4B
        return RouteResult.FallThrough(input = trimmed)
    }

    // ── Parameter parsing helpers ─────────────────────────────────────────────

    companion object {
        /**
         * Builds calendar intent params from a raw user query. Always includes `raw_query`.
         * Attempts to pre-extract a `extracted_title` hint from "for a/an X" phrasing so the
         * LLM prompt can be made more specific (reducing "title is required" failures).
         */
        fun extractCalendarHints(raw: String): Map<String, String> {
            val params = mutableMapOf("raw_query" to raw)
            // Match "for a/an X" or "for X" where X doesn't start with a date/time keyword.
            // Stops before at/from/on/next/this/tomorrow/weekday names/digits.
            val titleMatch = Regex(
                """(?:^|\s)for\s+(?:a\s+|an\s+)?([a-zA-Z][a-zA-Z\s]{1,40}?)(?=\s+(?:at|from|on|next|this|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d)|$)""",
                RegexOption.IGNORE_CASE,
            ).find(raw)
            titleMatch?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && it.length >= 2 }
                ?.let { title ->
                    params["extracted_title"] = title.split(" ")
                        .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                }
            return params
        }

        fun parseAlarmTime(raw: String): Map<String, String> {
            val params = mutableMapOf<String, String>()
            val cleaned = raw.lowercase().trim()
                .replace(Regex("""\s*(o'clock|oclock)"""), "")
                .trim()

            // Try patterns: "10pm", "10:30pm", "22:00", "9:05 am", "7"
            val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""")
            val match = timeRegex.find(cleaned) ?: return params

            var hours = match.groupValues[1].toIntOrNull() ?: return params
            val minutes = match.groupValues[2].toIntOrNull() ?: 0
            val meridiem = match.groupValues[3].replace(".", "").lowercase()

            // Convert to 24h
            if (meridiem == "pm" && hours < 12) hours += 12
            if (meridiem == "am" && hours == 12) hours = 0

            params["hours"] = hours.toString()
            params["minutes"] = minutes.toString()
            // Also emit a "time" key in HH:mm format so setAlarm() can use resolveTime()
            params["time"] = "${hours}:${minutes.toString().padStart(2, '0')}"

            // Extract day name (today, tomorrow, weekday names including abbreviations)
            val dayRegex = Regex(
                """\b(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thurs?|fri|sat|sun)\b""",
                RegexOption.IGNORE_CASE,
            )
            val dayMatch = dayRegex.find(cleaned)
            if (dayMatch != null) {
                params["day"] = normalizeDayName(dayMatch.groupValues[1].lowercase())
            }

            // Extract label from "called X", "named X", "labeled X", "labelled X" — mirrors parseTimerDuration.
            // Non-greedy capture with lookahead to stop before time/date keywords so
            // "alarm called wake up at 7am" extracts "wake up", not "wake up at 7am".
            val labelRegex = Regex(
                """(?:called|named|label(?:l?)ed)\s+(.+?)(?=\s+(?:at|on|for|by|from|tomorrow|today|tonight|morning|afternoon|evening|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d{1,2}(?::\d{2})?\s*(?:am|pm))(?:\s|$)|$)""",
                RegexOption.IGNORE_CASE,
            )
            val labelMatch = labelRegex.find(cleaned)
            if (labelMatch != null) {
                params["label"] = labelMatch.groupValues[1].trim()
            }

            return params
        }

        fun parseTimerDuration(match: MatchResult, input: String = ""): Map<String, String> {
            val amount1 = match.groupValues[1].toIntOrNull() ?: 0
            val unit1 = normalizeTimeUnit(match.groupValues[2])

            var totalSeconds = toSeconds(amount1, unit1)

            // Optional second component (e.g., "5 minutes and 30 seconds")
            if (match.groupValues.size > 4 && match.groupValues[3].isNotEmpty()) {
                val amount2 = match.groupValues[3].toIntOrNull() ?: 0
                val unit2 = normalizeTimeUnit(match.groupValues[4])
                totalSeconds += toSeconds(amount2, unit2)
            }

            val params = mutableMapOf("duration_seconds" to totalSeconds.toString())

            // Extract label from "called X", "named X", "labeled X", "labelled X"
            val labelRegex = Regex("""(?:called|named|label(?:l?)ed)\s+(.+)$""", RegexOption.IGNORE_CASE)
            val labelMatch = labelRegex.find(input)
            if (labelMatch != null) {
                params["label"] = labelMatch.groupValues[1].trim()
            }

            // Also handle "N unit timer for X" where "for" is a label keyword after "timer"
            if (!params.containsKey("label")) {
                val timerForRegex = Regex("""timer\s+for\s+([a-zA-Z].+)$""", RegexOption.IGNORE_CASE)
                val timerForMatch = timerForRegex.find(input)
                if (timerForMatch != null) {
                    params["label"] = timerForMatch.groupValues[1].trim()
                }
            }

            return params
        }

        private fun normalizeTimeUnit(unit: String): String = when {
            unit.startsWith("h", ignoreCase = true) -> "hours"
            unit.startsWith("m", ignoreCase = true) -> "minutes"
            unit.startsWith("s", ignoreCase = true) -> "seconds"
            else -> "minutes"
        }

        private fun toSeconds(amount: Int, unit: String): Int = when (unit) {
            "hours" -> amount * 3600
            "minutes" -> amount * 60
            "seconds" -> amount
            else -> amount * 60
        }

        private fun normalizeDayName(day: String): String = when (day) {
            "mon" -> "monday"
            "tue", "tues" -> "tuesday"
            "wed" -> "wednesday"
            "thu", "thur", "thurs" -> "thursday"
            "fri" -> "friday"
            "sat" -> "saturday"
            "sun" -> "sunday"
            else -> day
        }

        // ── Public surface for tests and callers ─────────────────────────────

        /** Lightweight result type returned by [matchQuickIntent]. */
        data class QuickIntent(
            val action: String,
            val params: Map<String, String> = emptyMap(),
        )

        /**
         * Regex-only intent match — no classifier, no fallthrough.
         * Returns [QuickIntent] when a regex pattern matches, or null otherwise.
         */
        fun matchQuickIntent(input: String): QuickIntent? {
            return when (val result = QuickIntentRouter().route(input)) {
                is RouteResult.RegexMatch -> QuickIntent(result.intent.intentName, result.intent.params)
                else -> null
            }
        }

        /**
         * Parses a human-readable time string into "HH:mm" 24-hour format.
         * Handles: "5pm", "9:30am", "14:00", "9 o'clock".
         * Returns null if the string cannot be parsed.
         */
        internal fun resolveTime(input: String): String? {
            val cleaned = input.lowercase().trim()
                .replace(Regex("""\s*(o'clock|oclock)\s*"""), "")
                .trim()
            val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""")
            val match = timeRegex.find(cleaned) ?: return null
            var hours = match.groupValues[1].toIntOrNull() ?: return null
            val minutes = match.groupValues[2].toIntOrNull() ?: 0
            val meridiem = match.groupValues[3].replace(".", "").lowercase()
            if (meridiem == "pm" && hours < 12) hours += 12
            if (meridiem == "am" && hours == 12) hours = 0
            if (hours !in 0..23 || minutes !in 0..59) return null
            return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
        }

        /**
         * Resolves a relative or day-name date string into an ISO-8601 "YYYY-MM-DD" string.
         * Handles: "today", "tomorrow", "next monday" … "next sunday".
         * Returns null if the string cannot be resolved.
         */
        internal fun resolveDate(input: String): String? {
            val normalized = input.lowercase().trim()
            val today = LocalDate.now()
            return when (normalized) {
                "today" -> today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                "tomorrow" -> today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                else -> {
                    val isThis = normalized.startsWith("this ")
                    val dayName = normalized.removePrefix("next ").removePrefix("this ").trim()
                    val dow: DayOfWeek = when (dayName) {
                        "monday" -> DayOfWeek.MONDAY
                        "tuesday" -> DayOfWeek.TUESDAY
                        "wednesday" -> DayOfWeek.WEDNESDAY
                        "thursday" -> DayOfWeek.THURSDAY
                        "friday" -> DayOfWeek.FRIDAY
                        "saturday" -> DayOfWeek.SATURDAY
                        "sunday" -> DayOfWeek.SUNDAY
                        else -> return null
                    }
                    val date = if (isThis && today.dayOfWeek == dow) today
                               else today.with(TemporalAdjusters.next(dow))
                    date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                }
            }
        }

        /**
         * String-based overload of [parseTimerDuration] for direct testing.
         * Returns total duration in seconds, or null if no duration pattern is found.
         */
        fun parseTimerDuration(input: String): Int? {
            val regex = Regex(
                """(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)(?:\s+(?:and\s+)?(\d+)\s*(minutes?|mins?|seconds?|secs?|m|s))?""",
                RegexOption.IGNORE_CASE,
            )
            val match = regex.find(input) ?: return null
            val amount1 = match.groupValues[1].toIntOrNull() ?: 0
            val unit1 = normalizeTimeUnit(match.groupValues[2])
            var total = toSeconds(amount1, unit1)
            if (match.groupValues[3].isNotEmpty()) {
                val amount2 = match.groupValues[3].toIntOrNull() ?: 0
                val unit2 = normalizeTimeUnit(match.groupValues[4])
                total += toSeconds(amount2, unit2)
            }
            return total
        }
    }
}
