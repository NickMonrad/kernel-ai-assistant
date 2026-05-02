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

        /**
         * Regex matched but a required parameter is absent — pause execution and ask the user
         * to supply the missing slot before the intent can be dispatched.
         */
        data class NeedsSlot(
            val intent: MatchedIntent,
            val missingSlot: com.kernel.ai.core.skills.slot.SlotSpec,
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

    // ── Intent prefix normalisation ───────────────────────────────────────────

    /** Strips common conversational prefixes before pattern matching so that
     *  "I want to turn on the torch" matches the same regex as "turn on the torch". */
    private val INTENT_PREFIX_RE = Regex(
        """^(?:(?:i\s+)?(?:want|need|would\s+like)\s+to|can\s+you|could\s+you|please|hey(?:\s+jandal)?)[,\s]+""",
        RegexOption.IGNORE_CASE,
    )

    private val slotContracts: Map<String, Map<String, com.kernel.ai.core.skills.slot.SlotSpec>> = mapOf(
        "make_call" to mapOf(
            "contact" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "contact",
                promptTemplate = "Who would you like to call?",
            ),
        ),
        "send_sms" to mapOf(
            "contact" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "contact",
                promptTemplate = "Who do you want to send a message to?",
            ),
            "message" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "message",
                promptTemplate = "What would you like to say to {contact}?",
            ),
        ),
        "send_email" to mapOf(
            "contact" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "contact",
                promptTemplate = "Who would you like to email?",
            ),
            "subject" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "subject",
                promptTemplate = "What's the subject of your email to {contact}?",
            ),
            "body" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "body",
                promptTemplate = "What would you like the email to say?",
            ),
        ),
        "add_to_list" to mapOf(
            "item" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "item",
                promptTemplate = "What would you like to add?",
            ),
            "list_name" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "list_name",
                promptTemplate = "Which list should I add it to?",
            ),
        ),
        "create_list" to mapOf(
            "list_name" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "list_name",
                promptTemplate = "What would you like to call the list?",
            ),
        ),
        "save_memory" to mapOf(
            "content" to com.kernel.ai.core.skills.slot.SlotSpec(
                name = "content",
                promptTemplate = "What would you like me to remember?",
            ),
        ),
    )

    private fun slotContract(intentName: String): Map<String, com.kernel.ai.core.skills.slot.SlotSpec> =
        slotContracts[intentName] ?: emptyMap()

    private val placeholderContacts = setOf(
        "someone",
        "somebody",
        "anyone",
        "anybody",
    )

    private val placeholderItems = setOf(
        "something",
        "anything",
    )

    private fun normalizeOptionalContact(raw: String): String? = raw.trim()
        .takeIf { it.isNotBlank() }
        ?.takeUnless { it.lowercase() in placeholderContacts }

    private fun normalizeOptionalItem(raw: String): String? = raw.trim()
        .takeIf { it.isNotBlank() }
        ?.takeUnless { it.lowercase() in placeholderItems }



    // ── Regex patterns ────────────────────────────────────────────────────────

    private data class IntentPattern(
        val intentName: String,
        val regex: Regex,
        val paramExtractor: (MatchResult, String) -> Map<String, String>,
        /**
         * Required slots that must be present in the extracted params before the intent can be
         * executed. If a key is absent or blank, [route] returns [RouteResult.NeedsSlot] so
         * the caller can ask the user to supply the missing value before proceeding.
         */
        val requiredSlots: Map<String, com.kernel.ai.core.skills.slot.SlotSpec> = emptyMap(),
        /**
         * When true, this pattern is a catch-all / last-resort match. It is only tried in
         * Pass 2 of [route], after all non-fallback patterns have failed to match. This
         * eliminates first-match-wins ordering fragility for broad patterns like generic
         * `play_media` and the terse smart-home `(.+?)\s+on/off` catch-alls.
         */
        val isFallback: Boolean = false,
    )

    private val patterns: List<IntentPattern> = listOf(
        // ── Flashlight ──
        // Pattern: "turn/switch on the torch" — verb + on + object
        IntentPattern(
            intentName = "toggle_flashlight_on",
            regex = Regex(
                """(?:turn|switch|put)\s+on\s+(?:the\s+)?(?:torch|flashlight|flash\s*light|light|illumination)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "turn the torch on" — verb + object + on
        IntentPattern(
            intentName = "toggle_flashlight_on",
            regex = Regex(
                """(?:turn|switch|put)\s+(?:the\s+)?(?:torch|flashlight|flash\s*light|light|illumination)\s+on""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "flashlight on" / "torch on" / "light on" / "illumination on" — object + on
        IntentPattern(
            intentName = "toggle_flashlight_on",
            regex = Regex(
                """^(?:torch|flashlight|flash\s*light|light|illumination)\s+on\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: terse ON command like "torch", "flashlight", or "torch please"
        IntentPattern(
            intentName = "toggle_flashlight_on",
            regex = Regex(
                """^(?:torch|flashlight|flash\s*light|illuminate)(?:\s+please)?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "turn/switch off the torch" — verb + off + object
        IntentPattern(
            intentName = "toggle_flashlight_off",
            regex = Regex(
                """(?:turn|switch|put)\s+off\s+(?:the\s+)?(?:torch|flashlight|flash\s*light|light|illumination)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "turn the torch off" — verb + object + off
        IntentPattern(
            intentName = "toggle_flashlight_off",
            regex = Regex(
                """(?:turn|switch|put)\s+(?:the\s+)?(?:torch|flashlight|flash\s*light|light|illumination)\s+off""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Pattern: "flashlight off" / "torch off" / "light off" / "illumination off" — object + off
        IntentPattern(
            intentName = "toggle_flashlight_off",
            regex = Regex(
                """^(?:torch|flashlight|flash\s*light|light|illumination)\s+off\b""",
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
        // "set an alarm" / "create an alarm" (no time given) → ask for time
        IntentPattern(
            intentName = "set_alarm",
            regex = Regex(
                """^(?:set|create|make)\s+(?:an?\s+)?alarm$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = mapOf(
                "time" to com.kernel.ai.core.skills.slot.SlotSpec(
                    name = "time",
                    promptTemplate = "What time would you like the alarm set for?",
                ),
            ),
        ),

        // ── Cancel Alarm ──
        IntentPattern(
            intentName = "cancel_alarm",
            regex = Regex(
                """(?:cancel|delete|remove|dismiss|clear|turn\s+off|stop|get\s+rid\s+of)\s+(?:(?:all\s+)?my\s+|the\s+|an?\s+|all\s+)?(?:\S+\s+)*alarms?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Cancel Timer ──
        // These must appear before set_timer — "cancel the 10 minute timer" substring-matches set_timer
        // patterns unless cancel intents take priority in the list.
        // Explicit "turn off" pattern first — long alternation groups can misbehave on Android's regex engine
        IntentPattern(
            intentName = "cancel_timer",
            regex = Regex(
                """turn\s+off\s+(?:my\s+|the\s+|a\s+)?(?:timer|countdown)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // cancel_timer_named must come before the generic cancel_timer patterns
        IntentPattern(
            intentName = "cancel_timer_named",
            regex = Regex(
                """(?:cancel|stop|dismiss|delete)\s+the\s+(?!timer\b)(.+?)\s+timer\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val name = match.groupValues.getOrNull(1)?.trim() ?: ""
                if (name.isNotBlank()) mapOf("name" to name) else emptyMap()
            },
        ),
        IntentPattern(
            intentName = "cancel_timer_named",
            regex = Regex(
                """(?:cancel|stop|dismiss|delete)\s+(?:my\s+)?(?!the\s+timer\b)(\w[\w\s]+?)\s+timer\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val name = match.groupValues.getOrNull(1)?.trim() ?: ""
                if (name.isNotBlank()) mapOf("name" to name) else emptyMap()
            },
        ),
        IntentPattern(
            intentName = "cancel_timer",
            regex = Regex(
                """(?:cancel|stop|clear|end|dismiss)\s+(?:my\s+|the\s+|a\s+)?(?:timer|countdown)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── List Timers ──
        IntentPattern(
            intentName = "list_timers",
            regex = Regex(
                """(?:list|show|what|how\s+many)\b.{0,20}\btimers?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "list_timers",
            regex = Regex(
                """timers?\s+(?:do\s+I\s+have|are\s+running|running)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Timer Remaining ──
        IntentPattern(
            intentName = "get_timer_remaining",
            regex = Regex(
                """how\s+(?:long|much\s+time)\s+(?:is\s+)?(?:left|remaining)\b.{0,30}\btimer\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, raw ->
                val nameMatch = Regex("""(?:the\s+)?(\w+)\s+timer""", RegexOption.IGNORE_CASE).find(raw)
                val name = nameMatch?.groupValues?.getOrNull(1)
                    ?.takeIf { it !in setOf("the", "my", "a", "an", "timer") }
                if (name != null) mapOf("name" to name) else emptyMap()
            },
        ),
        IntentPattern(
            intentName = "get_timer_remaining",
            regex = Regex(
                """how\s+long\s+until\s+(?:the\s+)?timer\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "get_timer_remaining",
            regex = Regex(
                """timer\s+(?:time\s+)?remaining\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "get_timer_remaining",
            regex = Regex(
                """(?:get|show|what(?:'s|\s+is))\s+(?:the\s+)?time\s+remaining\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "get_timer_remaining",
            regex = Regex(
                """how\s+much\s+time\s+(?:is|as)\s+remaining\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Timer (set) ──
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
        // "start a one hour timer" / "set a half hour timer" — written-out durations before open_app
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """(?:set|start|create)\s+(?:a\s+)?(?:one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|an?\s+|half\s+an?\s+)\s*(?:and\s+a\s+half\s+)?(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)\s+(?:\w+\s+)*timer""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val wordMap = mapOf(
                    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
                    "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
                    "fifteen" to 15, "twenty" to 20, "thirty" to 30, "an" to 1, "a" to 1,
                )
                val lower = input.lowercase()
                val numMatch = Regex("""(one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|an?)\s+(?:and\s+a\s+half\s+)?(hours?|hrs?|minutes?|mins?|seconds?|secs?)""").find(lower)
                if (numMatch != null) {
                    val qty = wordMap[numMatch.groupValues[1]] ?: 1
                    val unit = numMatch.groupValues[2]
                    val half = lower.contains("and a half") || lower.contains("and-a-half")
                    val seconds = when {
                        unit.startsWith("h") -> qty * 3600 + (if (half) 1800 else 0)
                        unit.startsWith("m") -> qty * 60 + (if (half) 30 else 0)
                        else -> qty + (if (half) 0 else 0)
                    }
                    mapOf("duration_seconds" to seconds.toString())
                } else {
                    emptyMap()
                }
            },
        ),
        // "set a timer" / "start a timer" (no duration given) → ask how long
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """^(?:set|start|create)\s+(?:a\s+)?(?:timer|countdown)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = mapOf(
                "duration_seconds" to com.kernel.ai.core.skills.slot.SlotSpec(
                    name = "duration_seconds",
                    promptTemplate = "How long would you like the timer for?",
                ),
            ),
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
        // Terse: "DND on" / "DND off"
        IntentPattern(
            intentName = "toggle_dnd_on",
            regex = Regex(
                """^(?:dnd|do\s+not\s+disturb)\s+on$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "toggle_dnd_off",
            regex = Regex(
                """^(?:dnd|do\s+not\s+disturb)\s+off$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "toggle_dnd_on",
            regex = Regex(
                """^toggle\s+(?:dnd|do\s+not\s+disturb)$""",
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
        // "battery" — bare single-word query
        IntentPattern(
            intentName = "get_battery",
            regex = Regex(
                """^(?:battery|charge\s+level|power\s+level)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // "running low on battery" — bare form; conversational "am I running low?" → fallthrough to classifier
        IntentPattern(
            intentName = "get_battery",
            regex = Regex(
                """^(?:running|run)\s+low\s+on\s+(?:battery|charge|power)$""",
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
        // Multi-day forecast (digit): "what's the forecast for the next 7 days" /
        // "what's the weather forecast for the next 7 days"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what(?:'s| is)\s+)?(?:the\s+)?(?:weather\s+)?forecast\s+for\s+the\s+next\s+(\d+)\s+days""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val days = match.groupValues[1].takeIf { it != "1" }
                buildMap<String, String> {
                    if (days != null) put("forecast_days", days)
                }
            },
        ),
        // Multi-day forecast (digit): "how's the weather looking for the next 5 days"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """how(?:'s|\s+is)\s+(?:the\s+)?weather\s+looking\s+for\s+the\s+next\s+(\d+)\s+days""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val days = match.groupValues[1].takeIf { it != "1" }
                buildMap<String, String> {
                    if (days != null) put("forecast_days", days)
                }
            },
        ),
        // Multi-day forecast (digit): "7 day weather forecast for Brisbane"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(\d+)\s+day\s+(?:weather\s+)?forecast\s+(?:in|for|at)\s+([\w\s,]+?)\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val days = match.groupValues[1].takeIf { it != "1" }
                val location = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
                buildMap<String, String> {
                    if (location != null) put("location", location)
                    if (days != null) put("forecast_days", days)
                }
            },
        ),
        // Multi-day forecast (digit): "what's the weather forecast for Paris next 4 days"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """what(?:'s| is)\s+the\s+weather\s+forecast\s+for\s+([\w\s,]+?)\s+next\s+(\d+)\s+days""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val days = match.groupValues[2].takeIf { it != "1" }
                val location = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                buildMap<String, String> {
                    if (location != null) put("location", location)
                    if (days != null) put("forecast_days", days)
                }
            },
        ),
        // Multi-day forecast (word): "what's the forecast for the next seven days" /
        // "what's the weather forecast for the next seven days"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what(?:'s| is)\s+)?(?:the\s+)?(?:weather\s+)?forecast\s+for\s+the\s+next\s+(one|two|three|four|five|six|seven|eight|nine|ten)\s+days""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val wordToNum = mapOf(
                    "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
                    "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
                )
                val daysWord = match.groupValues[1].takeIf { it != "one" }
                buildMap<String, String> {
                    if (daysWord != null) put("forecast_days", wordToNum[daysWord] ?: daysWord)
                }
            },
        ),
        // Multi-day forecast (word): "how's the weather looking for the next five days"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """how(?:'s|\s+is)\s+(?:the\s+)?weather\s+looking\s+for\s+the\s+next\s+(one|two|three|four|five|six|seven|eight|nine|ten)\s+days""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val wordToNum = mapOf(
                    "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
                    "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
                )
                val daysWord = match.groupValues[1].takeIf { it != "one" }
                buildMap<String, String> {
                    if (daysWord != null) put("forecast_days", wordToNum[daysWord] ?: daysWord)
                }
            },
        ),
        // Multi-day forecast (word): "three day weather forecast for Auckland"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(one|two|three|four|five|six|seven|eight|nine|ten)\s+day\s+(?:weather\s+)?forecast\s+(?:in|for|at)\s+([\w\s,]+?)\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val wordToNum = mapOf(
                    "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
                    "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
                )
                val daysWord = match.groupValues[1].takeIf { it != "one" }
                val location = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
                buildMap<String, String> {
                    if (location != null) put("location", location)
                    if (daysWord != null) put("forecast_days", wordToNum[daysWord] ?: daysWord)
                }
            },
        ),
        // Multi-day forecast (word): "what's the weather forecast for Paris next seven days"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """what(?:'s| is)\s+the\s+weather\s+forecast\s+for\s+([\w\s,]+?)\s+next\s+(one|two|three|four|five|six|seven|eight|nine|ten)\s+days""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val wordToNum = mapOf(
                    "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
                    "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
                )
                val daysWord = match.groupValues[2].takeIf { it != "one" }
                val location = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                buildMap<String, String> {
                    if (location != null) put("location", location)
                    if (daysWord != null) put("forecast_days", wordToNum[daysWord] ?: daysWord)
                }
            },
        ),
        // Tomorrow weather: "is it going to rain tomorrow", "will it rain tomorrow"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:will\s+it\s+rain\s+tomorrow\s*$|is\s+it\s+(?:going\s+to|gonna)\s+rain\s+tomorrow\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("day" to "tomorrow") },
        ),
        // Tomorrow weather with location: "what's the weather in Brisbane tomorrow" / "tomorrow weather in Auckland"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what(?:'s| is)\s+(?:the\s+)?weather\s+in\s+([\w\s,]+?)\s+tomorrow\s*$|tomorrow(?:'s)?\s+(?:weather|temperature)\s+in\s+([\w\s,]+?)\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val location = match.groupValues[1].takeIf { it.isNotEmpty() }
                    ?: match.groupValues[2].takeIf { it.isNotEmpty() }
                if (location != null) mapOf("location" to location.trim(), "day" to "tomorrow")
                else mapOf("day" to "tomorrow")
            },
        ),
        // Tomorrow weather: "what's the weather tomorrow", "tomorrow weather", "how's the weather looking tomorrow"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:what(?:'s| is)\s+(?:the\s+)?weather\s+(?:looking\s+)?tomorrow\s*$|tomorrow(?:'s)?\s+(?:weather|temperature)\s*$|how(?:'s|\s+is)\s+(?:the\s+)?weather\s+(?:looking\s+)?tomorrow\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("day" to "tomorrow") },
        ),
        // Tomorrow weather with condition: "will it be sunny tomorrow", "is it going to be cloudy tomorrow"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:will\s+it\s+be\s+(?:sunny|cloudy|rainy|snowy|stormy|foggy|windy|clear|overcast|dry)\s+tomorrow\s*$|is\s+it\s+(?:going\s+to\s+(?:be\s+)?)?(?:sunny|cloudy|rainy|snowy|stormy|foggy|windy|clear|overcast|dry)\s+tomorrow\s*$)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("day" to "tomorrow") },
        ),
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
                """(?:what(?:'s| is)\s+(?:the\s+)?weather(?:\s+(?:looking\s+like|looking|like|out\s+there|today|tonight|now|outside|currently))?|how(?:'s|\s+is)\s+(?:the\s+)?weather(?:\s+(?:looking\s+like|looking|like|out\s+there|today|tonight|now|outside))?|weather\s+(?:today|tonight|now|outside|forecast|this\s+week))\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Precipitation: current state only — "will it rain", "is it raining", "do I need an umbrella"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:will\s+it\s+rain(?:\s+today|\s+tonight)?\s*$|is\s+it\s+(?:going\s+to|gonna)\s+rain(?:\s+today|\s+tonight)?\s*$|is\s+it\s+raining|do\s+i\s+need\s+(?:an?\s+)?umbrella|chance\s+of\s+rain(?:\s+today|\s+tonight)?)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Temperature queries: "how hot/cold is it", "what's the temperature outside", "temperature in Wellington"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """(?:how\s+(?:hot|cold|warm)\s+is\s+it|what(?:'s| is)\s+(?:the\s+)?temperature(?:\s+(?:outside|today|now|currently))?|temperature\s+(?:outside|today|now|currently))""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // "temperature in Wellington" — city-specific temperature query
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """temperature\s+in\s+([\w\s]+?)(?:\s+today|\s+now|\s+tonight)?\s*$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("location" to match.groupValues[1].trim()) },
        ),
        // "what's it like outside" / "what's it like out there"
        IntentPattern(
            intentName = "get_weather",
            regex = Regex(
                """what(?:'s|\s+is)\s+it\s+like\s+(?:outside|out\s+there|out)""",
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
        // Numeric-level forms must come BEFORE direction-only patterns to win the match
        // "turn the volume up to 8" / "volume up to 5" / "volume down to 3"
        IntentPattern(
            intentName = "set_volume",
            regex = Regex(
                """(?:turn\s+)?(?:(?:the|my)\s+)?volume\s+(?:up|down)\s+to\s+(\d+)\s*(?:%|percent)?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, input ->
                mapOf(
                    "value" to match.groupValues[1],
                    "is_percent" to if (input.contains(Regex("""\d+\s*(?:%|percent)""", RegexOption.IGNORE_CASE))) "true" else "false",
                )
            },
        ),
        // set_volume explicit splits (Android long-alternation workaround)
        IntentPattern(
            intentName = "set_volume",
            regex = Regex("""turn\s+(?:(?:the|my)\s+)?volume\s+(?:up|down)""", RegexOption.IGNORE_CASE),
            paramExtractor = { _, input ->
                val isUp = input.contains(Regex("\\bup\\b", RegexOption.IGNORE_CASE))
                mapOf("direction" to if (isUp) "up" else "down")
            },
        ),
        IntentPattern(
            intentName = "set_volume",
            regex = Regex("""(?:raise|increase)\s+(?:(?:the|my)\s+)?volume""", RegexOption.IGNORE_CASE),
            paramExtractor = { _, _ -> mapOf("direction" to "up") },
        ),
        IntentPattern(
            intentName = "set_volume",
            regex = Regex("""(?:lower|decrease|crank\s+down)\s+(?:(?:the|my)\s+)?volume""", RegexOption.IGNORE_CASE),
            paramExtractor = { _, _ -> mapOf("direction" to "down") },
        ),
        // "turn the volume up/down" / "raise/lower the volume" — no numeric value
        IntentPattern(
            intentName = "set_volume",
            regex = Regex(
                """turn\s+(?:the\s+|my\s+)?volume\s+(?:up|down)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val isUp = input.contains(Regex("up", RegexOption.IGNORE_CASE))
                mapOf("direction" to if (isUp) "up" else "down")
            },
        ),
        // "raise/lower/increase/decrease/crank the volume up/down" — remaining verb forms
        IntentPattern(
            intentName = "set_volume",
            regex = Regex(
                """(?:raise|lower|increase|decrease|crank)\s+(?:(?:the|my)\s+)?volume(?:\s+(?:up|down))?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val isUp = input.contains(Regex("(?:raise|increase|up)", RegexOption.IGNORE_CASE))
                mapOf("direction" to if (isUp) "up" else "down")
            },
        ),
        // "volume up/down/higher/lower" — noun-first terse form
        IntentPattern(
            intentName = "set_volume",
            regex = Regex(
                """volume\s+(?:up|down|higher|lower)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val isUp = input.contains(Regex("(?:up|higher)", RegexOption.IGNORE_CASE))
                mapOf("direction" to if (isUp) "up" else "down")
            },
        ),
        // "mute/unmute the phone/device/sound/volume"
        IntentPattern(
            intentName = "set_volume",
            regex = Regex(
                """(?:mute|unmute)\s+(?:the\s+)?(?:phone|device|sound|volume)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val isUp = input.contains(Regex("unmute", RegexOption.IGNORE_CASE))
                mapOf("direction" to if (isUp) "up" else "down")
            },
        ),
        // Terse: "louder" / "quieter" / "softer" / "mute"
        IntentPattern(
            intentName = "set_volume",
            regex = Regex(
                """^(?:louder|quieter|softer|mute|unmute)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val isUp = input.contains(Regex("(?:louder|unmute)", RegexOption.IGNORE_CASE))
                mapOf("direction" to if (isUp) "up" else "down")
            },
        ),
        // "set volume to 50%" / "volume at 80" / "set volume to 50 percent"
        IntentPattern(
            intentName = "set_volume",
            regex = Regex(
                """(?:set\s+)?(?:the\s+)?volume\s+(?:to\s+|at\s+)?(\d+)\s*(?:%|percent)?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, input ->
                mapOf(
                    "value" to match.groupValues[1],
                    "is_percent" to if (input.contains(Regex("""\d+\s*(?:%|percent)""", RegexOption.IGNORE_CASE))) "true" else "false",
                )
            },
        ),

        // ── WiFi ──
        // Explicit Android-compat: "turn off/on wifi" — split out from long alternation
        IntentPattern(
            intentName = "toggle_wifi",
            regex = Regex(
                """turn\s+(?:off|on)\s+(?:wi-?fi|wireless)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.contains(Regex("""\bon\b""", RegexOption.IGNORE_CASE))) "on" else "off"
                mapOf("state" to state)
            },
        ),
        IntentPattern(
            intentName = "toggle_wifi",
            regex = Regex(
                """(?:on|off|enable|disable|switch\s+(?:on|off))\s+(?:wi-?fi|wireless)""",
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
        IntentPattern(
            intentName = "toggle_bluetooth",
            regex = Regex(
                """toggle\s+(?:bluetooth|bt)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "toggle_wifi",
            regex = Regex(
                """toggle\s+(?:wi-?fi|wireless)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Airplane Mode ──
        // Explicit Android-compat: "enable/disable airplane/flight mode" — split from optional-prefix form
        IntentPattern(
            intentName = "toggle_airplane_mode",
            regex = Regex(
                """(?:enable|disable)\s+(?:airplane|flight)\s+mode""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, input ->
                val state = if (input.contains(Regex("""\benable\b""", RegexOption.IGNORE_CASE))) "on" else "off"
                mapOf("state" to state)
            },
        ),
        IntentPattern(
            intentName = "toggle_airplane_mode",
            regex = Regex(
                """(?:turn\s+)?(?:on|off)\s+(?:airplane|flight)\s+mode""",
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
        // Plexamp — MUST come before play_plex (Plexamp contains "Plex")
        // Explicit Android-compat: "play X on Plexamp" — split from listen-to alternation
        IntentPattern(
            intentName = "play_plexamp",
            regex = Regex(
                """play\s+(.+?)\s+on\s+plexamp""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        IntentPattern(
            intentName = "play_plexamp",
            regex = Regex(
                """(?:play|listen\s+to)\s+(.+?)\s+(?:on|in)\s+plexamp""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // YouTube Music — MUST come before play_youtube ("YouTube Music" contains "YouTube")
        // Explicit Android-compat: "play X on YouTube Music" — split from listen-to alternation
        IntentPattern(
            intentName = "play_youtube_music",
            regex = Regex(
                """play\s+(.+?)\s+on\s+youtube\s+music""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        IntentPattern(
            intentName = "play_youtube_music",
            regex = Regex(
                """(?:play|listen\s+to)\s+(.+?)\s+on\s+youtube\s+music""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        IntentPattern(
            intentName = "play_youtube_music",
            regex = Regex(
                """(?:play|open|launch|start)\s+youtube\s+music\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "play_plexamp",
            regex = Regex(
                """(?:play|open|launch|start)\s+plexamp\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Plex — word boundary ensures "on plex" doesn't match "on Plexamp"
        IntentPattern(
            intentName = "play_plex",
            regex = Regex(
                """(?:play|watch)\s+(.+?)\s+on\s+plex\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("title" to match.groupValues[1].trim()) },
        ),
        // YouTube — word boundary ensures "on youtube" doesn't match "on YouTube Music"
        // Explicit Android-compat: "search YouTube for X" — split from nested two-alternative form
        IntentPattern(
            intentName = "play_youtube",
            regex = Regex(
                """search\s+(?:youtube|yt)\s+for\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        IntentPattern(
            intentName = "play_youtube",
            regex = Regex(
                """(?:(?:play|watch|search)\s+(.+?)\s+on\s+youtube\b|search\s+(?:youtube|yt)(?:\s+for)?\s+(.+))""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                mapOf("query" to (match.groupValues[1].trim().ifEmpty { match.groupValues[2].trim() }))
            },
        ),
        // Spotify — "play X on spotify" / "put on X on spotify"
        IntentPattern(
            intentName = "play_spotify",
            regex = Regex(
                """(?:play|listen\s+to|put\s+on)\s+(.+?)\s+on\s+spotify""",
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
        // Open app — "open an app" / "launch an app" (no specific app named) → ask which app
        // Must come BEFORE the generic open_app pattern below to prevent "an" being captured as app_name
        IntentPattern(
            intentName = "open_app",
            regex = Regex(
                """^(?:open|launch|start)\s+an?\s+app$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = mapOf(
                "app_name" to com.kernel.ai.core.skills.slot.SlotSpec(
                    name = "app_name",
                    promptTemplate = "Which app would you like to open?",
                ),
            ),
        ),
        // Open app — "open YouTube" / "launch Spotify"
        // Excludes timer/countdown/alarm phrases and phrases that contain "timer" anywhere
        // Also excludes list and new-conversation/chat phrases to prevent false matches on slot-fill commands
        IntentPattern(
            intentName = "open_app",
            regex = Regex(
                """^(?:open|launch|start)\s+(?:the\s+)?(?!(?:a\s+)?(?:count(?:down|ing)|timer|alarm|(?:my\s+)?list|new\s+conversation|new\s+chat|conversation|chat)\b)(?!.*\btimer\b)(.+?)(?:\s+app)?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("app_name" to match.groupValues[1].trim()) },
        ),
        // ── Podcast Controls ──
        // play_podcast — MUST require "podcast" or "episode" keyword to avoid collision with play_media/play_spotify
        IntentPattern(
            intentName = "play_podcast",
            regex = Regex(
                """\bplay\s+(?:the\s+)?(?:latest\s+)?(?:episode\s+of\s+|podcast\s+)?(.+?)\s+podcast\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val show = match.groupValues[1].trim()
                if (show.isNotBlank()) mapOf("show" to show) else emptyMap()
            },
        ),
        IntentPattern(
            intentName = "play_podcast",
            regex = Regex(
                """\bplay\s+(?:the\s+)?latest\s+episode\s+of\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val show = match.groupValues[1].trim()
                if (show.isNotBlank()) mapOf("show" to show) else emptyMap()
            },
        ),
        IntentPattern(
            intentName = "play_podcast",
            regex = Regex(
                """\bput\s+on\s+(?:the\s+)?(.+?)\s+podcast\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val show = match.groupValues[1].trim()
                if (show.isNotBlank()) mapOf("show" to show) else emptyMap()
            },
        ),
        IntentPattern(
            intentName = "play_podcast",
            regex = Regex(
                """\b(?:listen\s+to|subscribe\s+to)\s+(?:the\s+)?(.+?)\s+(?:podcast|episode)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val show = match.groupValues[1].trim()
                if (show.isNotBlank()) mapOf("show" to show) else emptyMap()
            },
        ),
        // podcast_skip_forward — skip forward N seconds / skip intro
        IntentPattern(
            intentName = "podcast_skip_forward",
            regex = Regex(
                """\bskip\s+(?:forward|ahead)\s+(\d+)\s*(second|sec|minute|min)s?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val amount = match.groupValues[1].toIntOrNull() ?: 30
                val unit = match.groupValues[2].lowercase()
                val seconds = if (unit.startsWith("min")) amount * 60 else amount
                mapOf("seconds" to seconds.toString())
            },
        ),
        IntentPattern(
            intentName = "podcast_skip_forward",
            regex = Regex(
                """\bforward\s+(\d+)\s*(second|sec|minute|min)s?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val amount = match.groupValues[1].toIntOrNull() ?: 30
                val unit = match.groupValues[2].lowercase()
                val seconds = if (unit.startsWith("min")) amount * 60 else amount
                mapOf("seconds" to seconds.toString())
            },
        ),
        IntentPattern(
            intentName = "podcast_skip_forward",
            regex = Regex(
                """\bskip\s+(?:the\s+)?intro\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ ->
                mapOf("seconds" to "30")  // default for "skip the intro"
            },
        ),
        IntentPattern(
            intentName = "podcast_skip_forward",
            regex = Regex(
                """\bskip\s+ahead\s+(\d+)\s*(second|sec|minute|min)s?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val amount = match.groupValues[1].toIntOrNull() ?: 30
                val unit = match.groupValues[2].lowercase()
                val seconds = if (unit.startsWith("min")) amount * 60 else amount
                mapOf("seconds" to seconds.toString())
            },
        ),
        // podcast_skip_back — rewind / back N seconds
        IntentPattern(
            intentName = "podcast_skip_back",
            regex = Regex(
                """\b(?:go\s+)?back\s+(\d+)\s*(second|sec|minute|min)s?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val amount = match.groupValues[1].toIntOrNull() ?: 15
                val unit = match.groupValues[2].lowercase()
                val seconds = if (unit.startsWith("min")) amount * 60 else amount
                mapOf("seconds" to seconds.toString())
            },
        ),
        IntentPattern(
            intentName = "podcast_skip_back",
            regex = Regex(
                """\brewind\s+(?:(\d+)\s*(second|sec|minute|min)s?)?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val amount = match.groupValues[1].toIntOrNull()
                val unit = match.groupValues.getOrNull(2)?.lowercase() ?: "second"
                val seconds = when {
                    amount == null -> 15  // default rewind
                    unit.startsWith("min") -> amount * 60
                    else -> amount
                }
                mapOf("seconds" to seconds.toString())
            },
        ),
        IntentPattern(
            intentName = "podcast_skip_back",
            regex = Regex(
                """\bback\s+(\d+)\s*(second|sec|minute|min)s?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val amount = match.groupValues[1].toIntOrNull() ?: 15
                val unit = match.groupValues[2].lowercase()
                val seconds = if (unit.startsWith("min")) amount * 60 else amount
                mapOf("seconds" to seconds.toString())
            },
        ),
        // podcast_speed — set playback rate
        IntentPattern(
            intentName = "podcast_speed",
            regex = Regex(
                """\bplay\s+at\s+([\d.]+)x\s*(?:speed)?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val speed = match.groupValues[1].toFloatOrNull() ?: 1.0f
                mapOf("rate" to speed.toString())
            },
        ),
        IntentPattern(
            intentName = "podcast_speed",
            regex = Regex(
                """\bset\s+(?:playback\s+)?speed\s+to\s+([\d.]+)x?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val speed = match.groupValues[1].toFloatOrNull() ?: 1.0f
                mapOf("rate" to speed.toString())
            },
        ),
        IntentPattern(
            intentName = "podcast_speed",
            regex = Regex(
                """\b(normal|regular)\s+speed\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ ->
                mapOf("rate" to "1.0")
            },
        ),
        IntentPattern(
            intentName = "podcast_speed",
            regex = Regex(
                """\bslow\s+down\s+(?:the\s+)?(?:podcast|playback|audio)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ ->
                mapOf("rate" to "0.75")
            },
        ),
        IntentPattern(
            intentName = "podcast_speed",
            regex = Regex(
                """\bspeed\s+up\s+(?:the\s+)?(?:podcast|playback|audio)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ ->
                mapOf("rate" to "1.5")
            },
        ),
        IntentPattern(
            intentName = "podcast_speed",
            regex = Regex(
                """\b(faster|slower)\s+(?:playback|speed|podcast)?\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val text = match.value.lowercase()
                val rate = if (text.contains("slower")) 0.75f else 1.5f
                mapOf("rate" to rate.toString())
            },
        ),
        // Album — matches before generic play
        // Explicit Android-compat: "play album X" without trailing $ anchor that can cause
        // lazy-quantifier backtracking issues on Android's regex engine
        IntentPattern(
            intentName = "play_media_album",
            regex = Regex(
                """play\s+(?:the\s+)?album\s+(.+?)(?:\s+(?:by|from)\s+(.+?))?(?:\s+on\s+\w+)?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf("album" to match.groupValues[1].trim())
                if (match.groupValues[2].isNotBlank()) params["artist"] = match.groupValues[2].trim()
                params
            },
        ),
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
        // Playlist — matches before generic play; also handles "put on"
        IntentPattern(
            intentName = "play_media_playlist",
            regex = Regex(
                """(?:play|put\s+on)\s+(?:(?:my|the)\s+)?(?:(.+?)\s+)?playlist(?:\s+(.+))?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val name = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                    ?: match.groupValues[2].trim()
                mapOf("playlist" to name)
            },
        ),
        // "play the next one/song/track" — must be before generic play_media
        IntentPattern(
            intentName = "next_track",
            regex = Regex(
                """(?i)\bplay\s+(?:the\s+)?next\s+(?:one|song|track|video|episode)\b""",
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // "play the previous one/track/song" — must be before generic play_media
        IntentPattern(
            intentName = "previous_track",
            regex = Regex(
                """(?i)\bplay\s+(?:the\s+)?(?:previous|last|prior)\s+(?:one|song|track|video|episode)\b""",
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // Generic play — catch-all fallback; only fires if no more-specific play_* pattern matched.
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
            isFallback = true,
        ),

        // ── Media Transport Controls ──
        // pause_media — pause [music/audio/etc] + colloquial "hold on"
        IntentPattern(
            intentName = "pause_media",
            regex = Regex(
                """\b(?:pause)\b(?:\s+(?:the\s+)?(?:music|song|audio|playback|podcast|video))?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "pause_media",
            regex = Regex(
                """(?i)^hold\s+on$""",
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // stop_media — stop/end playing [music/audio/etc]
        IntentPattern(
            intentName = "stop_media",
            regex = Regex(
                """\b(?:stop|end)\s+(?:the\s+)?(?:music|song|audio|playback|podcast|video|playing)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "stop_media",
            regex = Regex(
                """\bstop\s+playing\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // next_track — skip/next [song/track/one]
        IntentPattern(
            intentName = "next_track",
            regex = Regex(
                """\b(?:next|skip)\s+(?:(?:the\s+)?(?:song|track|one|video|episode))\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "next_track",
            regex = Regex(
                """\bskip\s+this\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "next_track",
            regex = Regex(
                """^(?:next\s+(?:song|track)|skip)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        // previous_track — previous/last/back [song/track]
        IntentPattern(
            intentName = "previous_track",
            regex = Regex(
                """\b(?:previous|last|back)\s+(?:song|track|one)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "previous_track",
            regex = Regex(
                """\bgo\s+back\s+(?:a\s+)?(?:song|track)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "previous_track",
            regex = Regex(
                """\bplay\s+(?:the\s+)?previous\s+(?:song|track)\b""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
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
        // "navigate" / "get directions" (no destination) → ask where
        IntentPattern(
            intentName = "navigate_to",
            regex = Regex(
                """^(?:navigate|directions?|get\s+directions?)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = mapOf(
                "destination" to com.kernel.ai.core.skills.slot.SlotSpec(
                    name = "destination",
                    promptTemplate = "Where would you like to go?",
                ),
            ),
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
                """(?:find|search\s+for|look\s+for|locate|show)\s+nearby\s+(.+)""",
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
        // "show me dog parks on the map" / "find cafes on the map"
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """(?:find|show\s+me|look\s+for|search\s+for|locate)\s+(.+?)\s+on\s+(?:the\s+)?map""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // "what restaurants are nearby" / "what's nearby"
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """what\s+(.+?)\s+(?:are|is)\s+(?:near(?:by|\s+me)|close\s+by|around\s+here)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // "where's the nearest ATM" / "where is the nearest X"
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """where(?:'s|\s+is)\s+(?:the\s+)?nearest\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // "is there a petrol station nearby" / "is there an ATM near me"
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """is\s+there\s+(?:a\s+|an\s+)?(.+?)\s+(?:near(?:by|\s+me)|close\s+by|around\s+here)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("query" to match.groupValues[1].trim()) },
        ),
        // "what's nearby" / "find nearby" (no query) → ask what they're looking for
        IntentPattern(
            intentName = "find_nearby",
            regex = Regex(
                """^(?:what(?:'s|\s+is)\s+nearby|find\s+nearby|show\s+(?:me\s+)?what(?:'s|\s+is)\s+nearby)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = mapOf(
                "query" to com.kernel.ai.core.skills.slot.SlotSpec(
                    name = "query",
                    promptTemplate = "What are you looking for?",
                ),
            ),
        ),

        // ── Communication ──
        // "make a call" / "call someone" / "ring anybody" (no concrete contact) → ask who
        IntentPattern(
            intentName = "make_call",
            regex = Regex(
                """^(?:make\s+(?:a\s+)?call|(?:call|ring|dial|phone)\s+(?:someone|somebody|anyone|anybody))$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = slotContract("make_call"),
        ),
        IntentPattern(
            intentName = "make_call",
            regex = Regex(
                """^(?:call|ring|dial|phone)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                normalizeOptionalContact(match.groupValues[1])?.let { mapOf("contact" to it) } ?: emptyMap()
            },
            requiredSlots = slotContract("make_call"),
        ),
        // "give Sarah a call" / "give mum a ring"
        IntentPattern(
            intentName = "make_call",
            regex = Regex(
                """^give\s+(.+?)\s+a\s+(?:call|ring|buzz)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                normalizeOptionalContact(match.groupValues[1])?.let { mapOf("contact" to it) } ?: emptyMap()
            },
            requiredSlots = slotContract("make_call"),
        ),
        // Self-send: "text myself [message]" / "text me [message]" — contact resolved to own number
        IntentPattern(
            intentName = "send_sms",
            regex = Regex(
                """^(?:text|sms)\s+(?:myself|me)\s+(.+)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                mapOf("contact" to "myself", "message" to match.groupValues[1].trim())
            },
            requiredSlots = slotContract("send_sms"),
        ),
        // "send a message" / "send a text" (no contact) → ask who to send to
        // Must come BEFORE the generic send_sms pattern below (which would capture "a" as contact)
        IntentPattern(
            intentName = "send_sms",
            regex = Regex(
                """^(?:send\s+(?:a\s+)?(?:text|sms|message)|text\s+someone)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = slotContract("send_sms"),
        ),
        // "send a text to John saying hello" / "text John hey" / "sms John meet at 5"
        IntentPattern(
            intentName = "send_sms",
            regex = Regex(
                """^(?:send\s+(?:a\s+)?(?:text|sms|message)|text|sms)\s+(?:(?:message\s+)?to\s+)?(.+?)(?:\s+(?:saying|that|with\s+message)\s+(.+))?$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf<String, String>()
                normalizeOptionalContact(match.groupValues[1])?.let { params["contact"] = it }
                val msg = match.groupValues[2].trim()
                if (msg.isNotBlank()) params["message"] = msg
                params
            },
            requiredSlots = slotContract("send_sms"),
        ),
        // "message mum that I'm on my way" / "message John saying..."
        IntentPattern(
            intentName = "send_sms",
            regex = Regex(
                """^message\s+(.+?)\s+(?:that|saying|to\s+say)\s+(.+)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf<String, String>()
                normalizeOptionalContact(match.groupValues[1])?.let { params["contact"] = it }
                params["message"] = match.groupValues[2].trim()
                params
            },
            requiredSlots = slotContract("send_sms"),
        ),
        // "send an email" / "email someone" (no concrete contact) → ask who
        // Must come BEFORE the contact-extraction pattern below
        IntentPattern(
            intentName = "send_email",
            regex = Regex(
                """^(?:send\s+(?:an?\s+)?email|email(?:\s+someone)?)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = slotContract("send_email"),
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
                val params = mutableMapOf<String, String>()
                normalizeOptionalContact(match.groupValues[1])?.let { params["contact"] = it }
                val subject = match.groupValues[2].trim()
                val body = match.groupValues[3].trim()
                if (subject.isNotBlank()) params["subject"] = subject
                if (body.isNotBlank()) params["body"] = body
                params
            },
            requiredSlots = slotContract("send_email"),
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
        // "how much storage/space/RAM do I have" / "what's my RAM usage"
        IntentPattern(
            intentName = "get_system_info",
            regex = Regex(
                """(?:how\s+much\s+(?:storage|space|memory|ram|disk\s+space)|what(?:'s|\s+is)\s+(?:my\s+)?(?:ram|memory|storage|cpu|disk)\s*(?:usage|left|remaining|level|status)?)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),

        // ── Date Diff ──
        // "how many days until Christmas" / "how long until my birthday on August 22"
        IntentPattern(
            intentName = "get_date_diff",
            regex = Regex(
                """(?:how\s+(?:many\s+(?:days?|weeks?|months?)\s+)?(?:long\s+)?(?:until|till|to|before))\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("target_date" to match.groupValues[1].trim()) },
        ),
        // "how many days since March 1" / "how long since Easter"
        IntentPattern(
            intentName = "get_date_diff",
            regex = Regex(
                """(?:how\s+(?:many\s+(?:days?|weeks?|months?)\s+)?(?:long\s+)?since)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("target_date" to match.groupValues[1].trim()) },
        ),
        // "days until Christmas" / "weeks until New Year"
        IntentPattern(
            intentName = "get_date_diff",
            regex = Regex(
                """(?:days?|weeks?)\s+(?:until|till|to)\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("target_date" to match.groupValues[1].trim()) },
        ),
        // "what day of the week is 22 August" / "what day is Christmas"  (not "what day is X this year")
        IntentPattern(
            intentName = "get_date_diff",
            regex = Regex(
                """what\s+day(?:\s+of\s+the\s+week)?\s+is\s+(?!.*\bthis\s+year\b)(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("target_date" to match.groupValues[1].trim()) },
        ),
        // "when is ANZAC Day" / "when is Easter"  (not "when is X this year" — that falls to E4B)
        IntentPattern(
            intentName = "get_date_diff",
            regex = Regex(
                """when\s+is\s+(?!.*\bthis\s+year\b)(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("target_date" to match.groupValues[1].trim()) },
        ),

        // ── Lists ──
        // "add milk to my list" / "put eggs on the list" (item present, list missing) → ask which list
        IntentPattern(
            intentName = "add_to_list",
            regex = Regex(
                """^(?:add\s+(.+?)\s+to|put\s+(.+?)\s+on)\s+(?:(?:my|the)\s+)?list$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val item = normalizeOptionalItem(match.groupValues[1].ifBlank { match.groupValues[2] })
                if (item != null) mapOf("item" to item) else emptyMap()
            },
            requiredSlots = slotContract("add_to_list"),
        ),
        IntentPattern(
            intentName = "add_to_list",
            regex = Regex(
                """add\s+(.+?)\s+to\s+(?:(?:my|the)\s+)?(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf<String, String>()
                normalizeOptionalItem(match.groupValues[1])?.let { params["item"] = it }
                params["list_name"] = match.groupValues[2].trim()
                params
            },
            requiredSlots = slotContract("add_to_list"),
        ),
        // "put milk on my shopping list" / "put eggs on the grocery list"
        IntentPattern(
            intentName = "add_to_list",
            regex = Regex(
                """put\s+(.+?)\s+on\s+(?:(?:my|the)\s+)?(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf<String, String>()
                normalizeOptionalItem(match.groupValues[1])?.let { params["item"] = it }
                params["list_name"] = match.groupValues[2].trim()
                params
            },
            requiredSlots = slotContract("add_to_list"),
        ),
        // "chuck milk on my list" / "stick eggs on the list" (item present, list missing) → ask which list
        IntentPattern(
            intentName = "add_to_list",
            regex = Regex(
                """^(?:chuck|stick|bung|pop|toss)\s+(.+?)\s+on\s+(?:(?:my|the)\s+)?list$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val item = normalizeOptionalItem(match.groupValues[1])
                if (item != null) mapOf("item" to item) else emptyMap()
            },
            requiredSlots = slotContract("add_to_list"),
        ),
        // "chuck milk on the shopping list" / "stick eggs on my grocery list" — NZ/AU/UK informal verbs
        IntentPattern(
            intentName = "add_to_list",
            regex = Regex(
                """(?:chuck|stick|bung|pop|toss)\s+(.+?)\s+on\s+(?:(?:my|the)\s+)?(?:(.+?)\s+)?list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val params = mutableMapOf<String, String>()
                normalizeOptionalItem(match.groupValues[1])?.let { params["item"] = it }
                val listName = match.groupValues[2].trim()
                if (listName.isNotBlank()) params["list_name"] = listName
                params
            },
            requiredSlots = slotContract("add_to_list"),
        ),
        // "add to my list" / "add something to my list" / "add to the shopping list" (no item) → ask what
        IntentPattern(
            intentName = "add_to_list",
            regex = Regex(
                """^(?:add|put)\s+(?:something\s+)?to\s+(?:(?:my|the)\s+)?(?:(.+?)\s+)?list$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ ->
                val listName = match.groupValues[1].trim()
                if (listName.isNotBlank()) mapOf("list_name" to listName) else emptyMap()
            },
            requiredSlots = slotContract("add_to_list"),
        ),
        // "create a list" / "make a new list" / "start my list" → ask for the list name
        IntentPattern(
            intentName = "create_list",
            regex = Regex(
                """^(?:create|make|start|new)\s+(?:a\s+|an\s+)?(?:new\s+)?(?:my\s+)?list$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = slotContract("create_list"),
        ),
        // "create a list called groceries" / "make a new list called holiday packing"
        // Must come BEFORE generic create_list to prevent lazy (.+?) capturing "a" or "new"
        IntentPattern(
            intentName = "create_list",
            regex = Regex(
                """(?:create|make|start)\s+(?:a\s+|an\s+)?(?:new\s+)?list\s+called\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("list_name" to match.groupValues[1].trim()) },
            requiredSlots = slotContract("create_list"),
        ),
        // "create a groceries list" / "make a new shopping list" / "start a meal plan list"
        // "make a new list called holiday packing" / "create a list called groceries"
        IntentPattern(
            intentName = "create_list",
            regex = Regex(
                """(?:create|make|start)\s+(?:a\s+|an\s+|my\s+)?(?:new\s+)?list\s+called\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("list_name" to match.groupValues[1].trim()) },
            requiredSlots = slotContract("create_list"),
        ),
        IntentPattern(
            intentName = "create_list",
            regex = Regex(
                """(?:create|make|start|new)\s+(?:a\s+|an\s+)?(?:new\s+)?(?:my\s+)?(?!list\b)(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("list_name" to match.groupValues[1].trim()) },
            requiredSlots = slotContract("create_list"),
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
        // "what's on my shopping list" / "what's in my shopping list" / "show me my grocery list"
        IntentPattern(
            intentName = "get_list_items",
            regex = Regex(
                """(?:what(?:'s|\s+is)\s+(?:on|in)|show(?:\s+me)?|read(?:\s+out)?|get|list)\s+(?:(?:my|the)\s+)?(.+?)\s+list""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("list_name" to match.groupValues[1].trim()) },
        ),
        // "display list called shopping" / "show list called groceries"
        IntentPattern(
            intentName = "get_list_items",
            regex = Regex(
                """(?:display|show|read|get)\s+(?:the\s+)?list\s+called\s+(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("list_name" to match.groupValues[1].trim()) },
        ),

        // ── Save Memory ──
        // Pattern: "remember something" / "save something to memory" / "make a note" → ask what to remember
        IntentPattern(
            intentName = "save_memory",
            regex = Regex(
                """^(?:remember\s+something|(?:save|store|keep)\s+something\s+(?:to|in)\s+memory|(?:(?:make|take|save)\s+(?:a\s+)?)note)$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
            requiredSlots = slotContract("save_memory"),
        ),
        // Pattern: "save [to/that/...] memory that X" / "save this to memory: X"
        IntentPattern(
            intentName = "save_memory",
            regex = Regex(
                """(?:save|store|keep)\s+(?:to\s+memory|in\s+memory|that|this|it)\s*[:\-–]?\s*(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("content" to match.groupValues[1].trim()) },
            requiredSlots = slotContract("save_memory"),
        ),
        // Pattern: "remember that X" / "remember: X"
        IntentPattern(
            intentName = "save_memory",
            regex = Regex(
                """remember\s+(?:that\s+)?[:\-–]?\s*(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("content" to match.groupValues[1].trim()) },
            requiredSlots = slotContract("save_memory"),
        ),
        // Pattern: "note that X" / "make a note that X" / "don't forget that X"
        IntentPattern(
            intentName = "save_memory",
            regex = Regex(
                """(?:(?:make\s+a\s+)?note|don't\s+forget)\s+(?:that\s+)?[:\-–]?\s*(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("content" to match.groupValues[1].trim()) },
            requiredSlots = slotContract("save_memory"),
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
            requiredSlots = slotContract("save_memory"),
        ),


        // ── Brightness ──
        // Explicit Android-compat: "increase brightness" — split from complex alternation
        IntentPattern(
            intentName = "set_brightness",
            regex = Regex(
                """increase\s+(?:the\s+)?(?:screen\s+)?brightness""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> mapOf("direction" to "up") },
        ),
        IntentPattern(
            intentName = "set_brightness",
            regex = Regex(
                """(?:raise|turn\s+up|brighten|max(?:imize)?)\s+(?:the\s+)?(?:screen\s+)?brightness""",
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
        // "dim the screen" / "dim the display" — no "brightness" keyword
        IntentPattern(
            intentName = "set_brightness",
            regex = Regex(
                """dim\s+(?:the\s+)?(?:screen|display|lights?)""",
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
        // Exclude media/computing devices and alarm/timer keywords that have dedicated intents.
        IntentPattern(
            intentName = "smart_home_on",
            regex = Regex(
                """(?:turn|switch)\s+on\s+(?!.*\b(?:tv|television|music|tunes|spotify|netflix|youtube|plex|alarm|alarms|timer)\b)(?:the\s+)?(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("device" to match.groupValues[1].trim()) },
        ),
        // Terse: "lights on" / "heater on" — object + on, no verb.
        // isFallback = true: only fires if no more-specific non-fallback pattern matched,
        // preventing ambiguous inputs like "hold on" from landing here.
        IntentPattern(
            intentName = "smart_home_on",
            regex = Regex(
                """^(?!(?:wifi|wi-fi|bluetooth|bt|hotspot|airplane|flight|dnd|torch|flashlight|hold|what|is|are|how|when|where|who|why|which|does|do|did|can|could|would|should|will|am|was|were)\b)(?:the\s+)?(.+?)\s+on$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("device" to match.groupValues[1].trim()) },
            isFallback = true,
        ),
        IntentPattern(
            intentName = "smart_home_off",
            regex = Regex(
                """(?:turn|switch)\s+off\s+(?!.*\b(?:tv|television|music|tunes|spotify|netflix|youtube|plex|alarm|alarms|timer)\b)(?:the\s+)?(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("device" to match.groupValues[1].trim()) },
        ),
        // Terse: "lights off" / "heater off" — object + off, no verb.
        // isFallback = true: only fires if no more-specific non-fallback pattern matched.
        IntentPattern(
            intentName = "smart_home_off",
            regex = Regex(
                """^(?!(?:wifi|wi-fi|bluetooth|bt|hotspot|airplane|flight|dnd|torch|flashlight)\b)(?:the\s+)?(.+?)\s+off$""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("device" to match.groupValues[1].trim()) },
            isFallback = true,
        ),
        // Colloquial: "kill the lights" / "kill the heater" — exclude flashlight/torch words (send to classifier)
        IntentPattern(
            intentName = "smart_home_off",
            regex = Regex(
                """kill\s+(?!(?:the\s+)?(?:flashlight|torch)\b)(?:the\s+)?(.+)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> mapOf("device" to match.groupValues[1].trim()) },
        ),
    )

    // ── Main routing ──────────────────────────────────────────────────────────

    /**
     * Attempts to match [trimmed] against [candidates] in order.
     * Returns the first [RouteResult.RegexMatch] or [RouteResult.NeedsSlot] found, or null if
     * no pattern matches.
     */
    private fun missingRequiredSlot(
        requiredSlots: Map<String, com.kernel.ai.core.skills.slot.SlotSpec>,
        params: Map<String, String>,
    ): com.kernel.ai.core.skills.slot.SlotSpec? =
        requiredSlots.entries.firstOrNull { (key, _) -> params[key].isNullOrBlank() }?.value

    fun nextMissingSlot(
        intentName: String,
        params: Map<String, String>,
    ): com.kernel.ai.core.skills.slot.SlotSpec? = missingRequiredSlot(slotContract(intentName), params)

    private fun tryMatchPatterns(trimmed: String, candidates: List<IntentPattern>): RouteResult? {
        for (pattern in candidates) {
            val match = pattern.regex.find(trimmed) ?: continue
            val params = pattern.paramExtractor(match, trimmed)
            val missingSlot = missingRequiredSlot(pattern.requiredSlots, params)
            val intent = MatchedIntent(
                intentName = pattern.intentName,
                params = params,
                source = "regex",
            )
            return if (missingSlot != null) {
                RouteResult.NeedsSlot(intent, missingSlot)
            } else {
                RouteResult.RegexMatch(intent)
            }
        }
        return null
    }


    fun route(input: String): RouteResult {
        val trimmed = INTENT_PREFIX_RE.replace(input.trim(), "")

        // Stage 1: Regex — two-pass to prevent catch-all patterns from stealing matches.
        //   Pass 1: specific patterns (isFallback = false) — tried in declaration order.
        //   Pass 2: fallback/catch-all patterns (isFallback = true) — only if Pass 1 misses.
        // This eliminates first-match-wins fragility without requiring manual ordering of every
        // pattern relative to every catch-all.
        val specificPatterns = patterns.filter { !it.isFallback }
        val fallbackPatterns = patterns.filter { it.isFallback }

        tryMatchPatterns(trimmed, specificPatterns)?.let { return it }
        tryMatchPatterns(trimmed, fallbackPatterns)?.let { return it }


        // Stage 2: BERT-tiny classifier (if available)
        classifier?.let { cls ->
            val result = cls.classify(trimmed)
            if (result != null && result.confidence >= similarityThreshold) {
                val isFastPath = result.intentName in FAST_PATH_INTENTS
                val fastPathOk = isFastPath && result.confidence >= FAST_PATH_THRESHOLD
                return RouteResult.ClassifierMatch(
                    intent = MatchedIntent(
                        intentName = result.intentName,
                        params = emptyMap(), // Classifier doesn't extract params — E4B does
                        source = "classifier",
                    ),
                    confidence = result.confidence,
                    needsConfirmation = !fastPathOk && result.confidence < 0.90f,
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
         * Intent names that carry no user-supplied parameters and are safe to execute
         * without confirmation once the classifier confidence is at or above [FAST_PATH_THRESHOLD].
         * These are the intents that RegexMatch handles with needsConfirmation = false —
         * the classifier path should behave the same way.
         *
         * Only include intents whose paramExtractor always returns emptyMap(). Intents that
         * require a user-supplied value (level, name, destination, etc.) must NOT appear here
         * — the LLM round-trip is needed to extract those params.
         *
         * Intentionally excluded (take required params):
         *   get_date_diff (date), get_list_items (list_name), cancel_timer_named (name),
         *   set_brightness (level), set_volume (level), podcast_speed (rate),
         *   smart_home_on/off (device name)
         *
         * See issue #620 for full rationale.
         */
        val FAST_PATH_INTENTS = setOf(
            // Flashlight
            "toggle_flashlight_on", "toggle_flashlight_off",
            // Do Not Disturb
            "toggle_dnd_on", "toggle_dnd_off",
            // Connectivity
            "toggle_wifi", "toggle_bluetooth", "toggle_airplane_mode", "toggle_hotspot",
            // Battery / System info
            "get_battery", "get_system_info",
            // Time
            "get_time",
            // Weather (uses device location — no user-supplied param)
            "get_weather",
            // Media transport (no query/title param)
            "pause_media", "stop_media", "next_track", "previous_track",
            // Podcast transport (skip only — podcast_speed takes a rate param)
            "podcast_skip_forward", "podcast_skip_back",
            // Timer / Alarm — query and cancel (no required params)
            "list_timers", "get_timer_remaining",
            "cancel_alarm", "cancel_timer",
        )

        /**
         * Minimum classifier confidence for fast-path execution. Intents in [FAST_PATH_INTENTS]
         * with confidence in [FAST_PATH_THRESHOLD, 0.90) execute directly without LLM confirmation.
         * Below this floor the intent falls through to E4B as normal.
         */
        const val FAST_PATH_THRESHOLD = 0.75f

        /**
         * Simple affirmations that confirm a pending intent without triggering a new QIR route
         * or LLM round-trip. Matched case-insensitively against the trimmed user input.
         *
         * See issue #621 for the multi-turn confirmation fast-path design.
         */
        val AFFIRMATIONS = setOf(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay",
            "go ahead", "go for it", "do it", "please", "please do",
            "aye", "absolutely", "definitely", "go on", "sounds good",
        )

        /** Returns true if [input] is a simple affirmation (case-insensitive, trims whitespace). */
        fun isAffirmation(input: String): Boolean = input.trim().lowercase() in AFFIRMATIONS

        /**
         * Builds calendar intent params from a raw user query. Always includes `raw_query`.
         * Attempts to pre-extract a `extracted_title` hint from "for a/an X" phrasing so the
         * LLM prompt can be made more specific (reducing "title is required" failures).
         */
        fun extractCalendarHints(raw: String): Map<String, String> {
            val params = mutableMapOf("raw_query" to raw)
            val lower = raw.lowercase()

            // ── Title ─────────────────────────────────────────────────────────────
            // Try "for a/an X" first (e.g. "create a meeting for tomorrow" → "Meeting").
            // Fall back to the noun phrase immediately after the verb+article, stopping
            // before any temporal keyword (e.g. "schedule a dentist appointment Friday").
            val titleFromFor = Regex(
                """(?:^|\s)for\s+(?:a\s+|an\s+)?([a-zA-Z][a-zA-Z\s]{1,40}?)(?=\s+(?:at|from|on|next|this|tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d)|$)""",
                RegexOption.IGNORE_CASE,
            ).find(raw)
            val titleFromVerb = Regex(
                """(?:add|create|schedule|put|book|set)\s+(?:a\s+|an\s+)?([a-zA-Z][a-zA-Z\s]{1,40}?)(?=\s+(?:for|at|from|on|next|this|tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d)|$)""",
                RegexOption.IGNORE_CASE,
            ).find(raw)
            val DATE_WORDS = setOf(
                "today", "tomorrow", "next", "this",
                "monday", "tuesday", "wednesday",
                "thursday", "friday", "saturday", "sunday",
            )
            val rawTitle = run {
                val fromFor = titleFromFor?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotBlank() && it.length >= 2 && !DATE_WORDS.contains(it.lowercase()) }
                fromFor ?: titleFromVerb?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotBlank() && it.length >= 2 }
            }
            if (rawTitle != null) {
                params["title"] = rawTitle.split(" ")
                    .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
            }

            // ── Date: relative terms and day names (fed directly to resolveDate()) ──
            val dateRegex = Regex(
                """\b(today|tomorrow|next\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|this\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
                RegexOption.IGNORE_CASE,
            )
            dateRegex.find(lower)?.value?.trim()?.let { params["date"] = it }

            // ── Time: "at 2pm", "at 10:30am", "at noon/midnight", "at 10" ─────────
            // Bare hours (no am/pm) are normalised to HH:00 so resolveTime() can parse.
            val timeRegex = Regex(
                """(?:at|@)\s+(noon|midnight|\d{1,2}(?::\d{2})?\s*(?:am|pm)|\d{1,2}(?::\d{2})?)(?!\s*(?:am|pm))""",
                RegexOption.IGNORE_CASE,
            )
            timeRegex.find(lower)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { t ->
                    params["time"] = when {
                        t.lowercase() == "noon" -> "12:00pm"
                        t.lowercase() == "midnight" -> "12:00am"
                        // bare hour like "10" → "10:00" for resolveTime()
                        t.matches(Regex("""\d{1,2}""")) -> "${t.padStart(2, '0')}:00"
                        else -> t
                    }
                }

            return params
        }

        fun parseAlarmTime(raw: String): Map<String, String> {
            val params = mutableMapOf<String, String>()
            val cleaned = normalizeCompactAlarmText(raw.lowercase().trim())
                .replace(Regex("""\s*(o'clock|oclock)"""), "")
                .trim()

            // Try patterns: "10pm", "10:30pm", "22:00", "9:05 am", "7"
            val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""")
            val match = timeRegex.find(cleaned) ?: return params

            val rawHours = match.groupValues[1].toIntOrNull() ?: return params
            val minutes = match.groupValues[2].toIntOrNull() ?: 0
            val meridiem = match.groupValues[3].replace(".", "").lowercase()
            if (minutes !in 0..59) return params
            if (meridiem.isNotBlank() && rawHours !in 1..12) return params
            if (meridiem.isBlank() && rawHours !in 0..23) return params

            var hours = rawHours

            // Convert to 24h
            if (meridiem == "pm" && hours < 12) hours += 12
            if (meridiem == "am" && hours == 12) hours = 0
            if (hours !in 0..23) return params

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

        private fun normalizeCompactAlarmText(raw: String): String {
            var normalized = raw
            normalized = Regex(
                """\b(\d{1,2})\s+(\d{2})\b""",
                RegexOption.IGNORE_CASE,
            ).replace(normalized) { match ->
                val hour = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                val minute = match.groupValues[2].toIntOrNull() ?: return@replace match.value
                if (hour !in 0..23 || minute !in 0..59) return@replace match.value
                "$hour:${minute.toString().padStart(2, '0')}"
            }
            normalized = Regex(
                """\b(?:to|too)\s+30\b""",
                RegexOption.IGNORE_CASE,
            ).replace(normalized, "2:30")
            normalized = Regex(
                """\b(\d{1,2})\s+(\d{2})\s*(am|pm|a\.m\.|p\.m\.)\b""",
                RegexOption.IGNORE_CASE,
            ).replace(normalized) { match ->
                val hour = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                val minute = match.groupValues[2].toIntOrNull() ?: return@replace match.value
                val meridiem = match.groupValues[3].lowercase()
                if (hour !in 1..12 || minute !in 0..59) return@replace match.value
                "$hour:${minute.toString().padStart(2, '0')} $meridiem"
            }
            normalized = Regex(
                """\b(\d{3,4})\s*(am|pm|a\.m\.|p\.m\.)\b""",
                RegexOption.IGNORE_CASE,
            ).replace(normalized) { match ->
                val digits = match.groupValues[1]
                val meridiem = match.groupValues[2].lowercase()
                val (hour, minute) = when (digits.length) {
                    3 -> digits.take(1).toInt() to digits.drop(1).toInt()
                    4 -> digits.take(2).toInt() to digits.drop(2).toInt()
                    else -> return@replace match.value
                }
                if (hour !in 1..12 || minute !in 0..59) return@replace match.value
                "$hour:${minute.toString().padStart(2, '0')} $meridiem"
            }
            normalized = Regex(
                """\b(3[1-9]|4[0-2])\s*(am|pm|a\.m\.|p\.m\.)\b""",
                RegexOption.IGNORE_CASE,
            ).replace(normalized) { match ->
                val flattened = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                val hour = flattened - 30
                val meridiem = match.groupValues[2].lowercase()
                if (hour !in 1..12) return@replace match.value
                "$hour:30 $meridiem"
            }
            normalized = Regex(
                """\b([1-9]|1[0-2])0(?:'clock|o'clock|oclock)\b""",
                RegexOption.IGNORE_CASE,
            ).replace(normalized) { match ->
                match.groupValues[1]
            }
            return normalized
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
                // NeedsSlot is still a successful regex match — intent is known, slot fill pending.
                is RouteResult.NeedsSlot -> QuickIntent(result.intent.intentName, result.intent.params)
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
