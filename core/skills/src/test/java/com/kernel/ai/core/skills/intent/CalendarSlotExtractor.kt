package com.kernel.ai.core.skills.intent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic slot extractor for the `create_calendar_event` intent.
 *
 * This is the canonical implementation of calendar slot extraction.
 * [QuickIntentRouter.extractCalendarHints] delegates here, and the
 * [IntentRecoveryOrchestrator] uses this via the [IntentSlotExtractor]
 * interface for recovery from FallThrough.
 *
 * Title extraction (#1100): when both a verb-title (e.g. "set up a meeting")
 * and a for-title (e.g. "for marketing") match, the verb-title wins because
 * "for X" may be metadata (attendee/context/category), not the event title.
 * When only one matches, it is used; generic verb titles (meeting, appointment)
 * are filtered out unless they conflict with a for-title.
 */
@Singleton
class CalendarSlotExtractor @Inject constructor() : IntentSlotExtractor {

    override fun supports(intentName: String): Boolean =
        intentName == "create_calendar_event" || intentName == "create_event"

    // Phrases that are always capability questions, never calendar requests.
    // Expanded per #1103 false-positive sweep: weather queries, search requests,
    // and general knowledge questions commonly trigger false extractions.
    private val PURE_QUESTION_PHRASES = listOf(
        "do you know how to",
        "how do i",
        "how to",
        "what is",
        "what's",
        "whats",
        "what will",
        "what would",
        "what does",
        "will it",
        "is it going",
        "tell me the",
        "tell me about",
        "explain",
    )

    // Phrases at the start of input that indicate a non-calendar intent
    // even though they aren't explicitly question phrases.
    private val NON_CALENDAR_STARTS_WITH = listOf(
        "weather",
        "search",
        "find",
        "look up",
        "navigate",
        "call ",
        "phone ",
        "text ",
        "email ",
        "message ",
    )

    // Phrases that can be either capability questions or polite requests.
    // Only blocked when there's no actionable evidence.
    private val AMBIGUOUS_CAPABILITY_PHRASES = listOf(
        "can you",
        "are you able to",
    )

    override fun extract(input: String, contract: IntentContract): ExtractionResult {
        val lower = input.lowercase().trim()
        val params = mutableMapOf<String, String>()

        // ── Title ─────────────────────────────────────────────────────────────
        val titleFromFor = REGEX_TITLE_FOR.find(input)
        val titleFromVerb = REGEX_TITLE_VERB.find(input)
        val hasCalendarEvidence = titleFromFor != null || titleFromVerb != null

        val rawTitle = run {
            val fromFor = titleFromFor?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && it.length >= 2 && !DATE_WORDS.contains(it.lowercase()) }
            val fromVerb = titleFromVerb?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && it.length >= 2 }

            if (titleFromVerb != null && fromFor != null) {
                // Both match (#1100). Verb-title wins — the user explicitly named an
                // action (e.g. "set up a meeting"), so "for X" is metadata (context/
                // attendee/category), not the event title.
                // Exceptions: titles in BLOCKED_ALWAYS_TITLES (calendar event, session,
                // booking, etc.) are so generic they add no value even in context.
                val blockedAlways = BLOCKED_ALWAYS_TITLES.contains(fromVerb?.lowercase() ?: "")
                if (!blockedAlways) fromVerb else fromFor
            } else {
                fromVerb?.takeIf { !GENERIC_CALENDAR_TITLES.contains(it.lowercase()) } ?: fromFor
            }
        }
        val normalizedTitle = rawTitle?.let { candidate ->
            val trimmed = candidate.trim()
            val strippedTrailingUp = trimmed.replace(REGEX_TRAILING_UP, "").trim()
            when {
                strippedTrailingUp != trimmed && GENERIC_CALENDAR_TITLES.contains(strippedTrailingUp.lowercase()) -> null
                else -> trimmed
            }
        }
        if (normalizedTitle != null) {
            params["title"] = normalizedTitle.split(" ")
                .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        }

        // ── Date: relative terms and day names ────────────────────────────────
        REGEX_DATE_RELATIVE.find(lower)?.value?.trim()?.let { params["date"] = it }

        if (!params.containsKey("date")) {
            REGEX_DATE_ORDINAL.find(input)?.let { match ->
                val day = match.groupValues[1]
                val month = match.groupValues[2].lowercase()
                    .replaceFirstChar { c -> c.uppercase() }
                params["date"] = "$day $month"
            }
        }
        if (!params.containsKey("date")) {
            REGEX_DATE_MONTH_FIRST.find(input)?.let { match ->
                val month = match.groupValues[1].lowercase()
                    .replaceFirstChar { c -> c.uppercase() }
                val day = match.groupValues[2]
                params["date"] = "$day $month"
            }
        }

        // ── Time ──
        REGEX_TIME.find(lower)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { t ->
                params["time"] = when {
                    t.lowercase() == "noon" -> "12:00pm"
                    t.lowercase() == "midnight" -> "12:00am"
                    t.matches(REGEX_BARE_HOUR) -> "${t.padStart(2, '0')}:00"
                    else -> t
                }
            }

        // P1 guard: suppress extraction for capability questions.
        // Pure question phrases (what's, how to, explain, etc.) are NEVER calendar intents.
        val isPureQuestion = PURE_QUESTION_PHRASES.any { phrase -> lower.startsWith(phrase) }
        if (isPureQuestion) return ExtractionResult.NotActionable

        // #1103 guard: non-calendar starting phrases (weather, search, find, etc.)
        // without calendar verb evidence are not calendar events.
        val isNonCalendar = NON_CALENDAR_STARTS_WITH.any { phrase -> lower.startsWith(phrase) }
        if (isNonCalendar && !hasCalendarEvidence) {
            return ExtractionResult.NotActionable
        }

        // Ambiguous phrases (can you, are you able to) may be polite requests.
        // Require calendar evidence (title match or calendar verb match) — a bare
        // date/time without a title/verb is not sufficient (e.g. "can you tell me
        // the weather tomorrow?" has date but no calendar event evidence).
        val isAmbiguousQuery = AMBIGUOUS_CAPABILITY_PHRASES.any { phrase -> lower.startsWith(phrase) }
        if (isAmbiguousQuery && !hasCalendarEvidence) {
            return ExtractionResult.NotActionable
        }

        return ExtractionResult.Extracted(params)
    }

    companion object {
        private val REGEX_TITLE_FOR = Regex(
            """(?:^|\s)for\s+(?:a\s+|an\s+)?([a-zA-Z][a-zA-Z\s]{1,40}?)(?=\s+(?:at|from|on|to|in|into|next|this|tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d)|$)""",
            RegexOption.IGNORE_CASE,
        )
        private val REGEX_TITLE_VERB = Regex(
            """(?:add|create|schedule|put|book|set(?:\s+up)?)\s+(?:a\s+|an\s+)?([a-zA-Z][a-zA-Z\s]{1,40}?)(?=\s+(?:for|at|from|on|to|in|into|next|this|tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d)|$)""",
            RegexOption.IGNORE_CASE,
        )
        private val REGEX_TRAILING_UP = Regex("""\s+up$""", RegexOption.IGNORE_CASE)
        private val REGEX_DATE_RELATIVE = Regex(
            """\b(today|tomorrow|next\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|this\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val REGEX_DATE_ORDINAL = Regex(
            """\b(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val REGEX_DATE_MONTH_FIRST = Regex(
            """\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+(\d{1,2})(?:st|nd|rd|th)?\b""",
            RegexOption.IGNORE_CASE,
        )
        // Supports `at/for/@ <time>` (e.g. "for 3pm", "at 10:30am").
        // The bare-digit alternative guards against ordinals ("for 9th" → time=09:00)
        // by rejecting numbers followed by st/nd/rd/th or additional digits.
        private val REGEX_TIME = Regex(
            """(?:at|@|for)\s+(noon|midnight|\d{1,2}(?::\d{2})?\s*(?:am|pm|a\.m\.|p\.m\.)|\d{1,2}(?::\d{2})?(?!\s*(?:st|nd|rd|th|\d)))(?!\s*(?:am|pm|a\.m\.|p\.m\.))""",
        )
        private val REGEX_BARE_HOUR = Regex("""\d{1,2}""")

        private val DATE_WORDS = setOf(
            "today", "tomorrow", "next", "this",
            "monday", "tuesday", "wednesday",
            "thursday", "friday", "saturday", "sunday",
        )
        private val GENERIC_CALENDAR_TITLES = setOf(
            "appointment", "meeting", "event", "session",
            "booking", "invite", "entry", "something",
            "calendar event", "calendar events",
        )
        // Titles so generic that even paired with a for-title they add no value.
        // Subset of GENERIC_CALENDAR_TITLES that are rejected even in both-match mode.
        private val BLOCKED_ALWAYS_TITLES = setOf(
            "calendar event", "calendar events", "event", "session",
            "booking", "entry", "something",
        )
    }
}
