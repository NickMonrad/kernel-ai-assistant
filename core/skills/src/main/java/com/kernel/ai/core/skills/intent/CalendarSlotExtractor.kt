package com.kernel.ai.core.skills.intent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic slot extractor for the `create_calendar_event` intent.
 *
 * Mirrors the extraction logic from [QuickIntentRouter.extractCalendarHints] but as a
 * standalone, injectable extractor. The original [QuickIntentRouter.extractCalendarHints]
 * is kept for backward compatibility and delegates here during migration.
 */
@Singleton
class CalendarSlotExtractor @Inject constructor() : IntentSlotExtractor {

    override fun supports(intentName: String): Boolean =
        intentName == "create_calendar_event" || intentName == "create_event"

    private val CAPABILITY_PHRASES = listOf(
        "do you know how to",
        "can you",
        "are you able to",
        "how do i",
        "how to",
        "what is",
        "explain",
    )

    override fun extract(input: String, contract: IntentContract): ExtractionResult {
        val lower = input.lowercase().trim()
        val params = mutableMapOf<String, String>()

        // ── Title ─────────────────────────────────────────────────────────────
        val titleFromFor = REGEX_TITLE_FOR.find(input)
        val titleFromVerb = REGEX_TITLE_VERB.find(input)

        val rawTitle = run {
            val fromFor = titleFromFor?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && it.length >= 2 && !DATE_WORDS.contains(it.lowercase()) }
            fromFor ?: titleFromVerb?.groupValues?.get(1)?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        it.length >= 2 &&
                        !GENERIC_CALENDAR_TITLES.contains(it.lowercase())
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

        // P1 guard: suppress extraction when input looks like a pure capability
        // question with no actionable evidence (no date, time, or non-generic title).
        val isCapabilityQuery = CAPABILITY_PHRASES.any { phrase -> lower.startsWith(phrase) }
        if (isCapabilityQuery && params.isEmpty()) {
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
        // P2: only `at` or `@`, not `for`, to avoid "for 9th" → time=09:00
        private val REGEX_TIME = Regex(
            """(?:at|@)\s+(noon|midnight|\d{1,2}(?::\d{2})?\s*(?:am|pm|a\.m\.|p\.m\.)|\d{1,2}(?::\d{2})?)(?!\s*(?:am|pm|a\.m\.|p\.m\.))""",
            RegexOption.IGNORE_CASE,
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
    }
}
