package com.kernel.ai.core.skills

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

        // ── Timer ──
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """(?:set|start|create)\s+(?:a\s+)?(?:timer|countdown)\s+(?:for\s+)?(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)(?:\s+(?:and\s+)?(\d+)\s*(minutes?|mins?|seconds?|secs?|m|s))?""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseTimerDuration(match) },
        ),
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """(?:timer|countdown)\s+(?:for\s+)?(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseTimerDuration(match) },
        ),
        // "5 minute timer"
        IntentPattern(
            intentName = "set_timer",
            regex = Regex(
                """(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|h|m|s)\s+timer""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { match, _ -> parseTimerDuration(match) },
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

        // ── Time / Date ──
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what(?:'s| is)\s+(?:the\s+)?(?:current\s+)?(?:time|date|day)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what\s+time\s+is\s+it""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """(?:tell|give)\s+me\s+(?:the\s+)?(?:time|date)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
        ),
        IntentPattern(
            intentName = "get_time",
            regex = Regex(
                """what\s+(?:day|date)\s+is\s+(?:it|today)""",
                RegexOption.IGNORE_CASE,
            ),
            paramExtractor = { _, _ -> emptyMap() },
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

            // Check for "tomorrow" in surrounding text
            if (cleaned.contains("tomorrow")) {
                params["label"] = "TOMORROW"
            }

            return params
        }

        fun parseTimerDuration(match: MatchResult): Map<String, String> {
            val amount1 = match.groupValues[1].toIntOrNull() ?: 0
            val unit1 = normalizeTimeUnit(match.groupValues[2])

            var totalSeconds = toSeconds(amount1, unit1)

            // Optional second component (e.g., "5 minutes and 30 seconds")
            if (match.groupValues.size > 4 && match.groupValues[3].isNotEmpty()) {
                val amount2 = match.groupValues[3].toIntOrNull() ?: 0
                val unit2 = normalizeTimeUnit(match.groupValues[4])
                totalSeconds += toSeconds(amount2, unit2)
            }

            return mapOf("duration_seconds" to totalSeconds.toString())
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
    }
}
