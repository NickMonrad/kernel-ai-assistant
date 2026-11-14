package com.kernel.ai.core.skills.natives

/**
 * Calendar titles, locations, descriptions and attendee names are personal data. Every log line that
 * can carry a calendar call's params, request JSON or reply text goes through this object, so release
 * logcat shows the intent name and sizes only. Non-calendar intents keep their existing logging.
 *
 * Decide calendar-ness from the resolved intent name (or from a tool request that names one), never
 * from the skill name or the user's text: `run_intent` results would otherwise slip through.
 */
object CalendarLogRedaction {

    const val REDACTED = "<calendar content redacted>"

    private val CALENDAR_INTENTS = setOf("create_calendar_event", "read_calendar_events")

    /** True when [intentName] is a calendar intent (after alias resolution). */
    fun isCalendarIntent(intentName: String?): Boolean =
        intentName != null && intentName in CALENDAR_INTENTS

    /** True when any of [texts] (tool name, request JSON, resolved intent) names a calendar intent. */
    fun mentionsCalendarIntent(vararg texts: String?): Boolean =
        texts.any { text -> text != null && CALENDAR_INTENTS.any { text.contains(it) } }

    /** Params for a log line: keys only for calendar intents, unchanged otherwise. */
    fun params(intentName: String?, params: Map<String, *>): String =
        if (isCalendarIntent(intentName)) {
            "{keys=${params.keys.sorted()}, values=$REDACTED}"
        } else {
            params.toString()
        }

    /** Raw params JSON for a log line: fully redacted for calendar intents, unchanged otherwise. */
    fun params(intentName: String?, rawParams: String): String =
        if (isCalendarIntent(intentName)) "{values=$REDACTED}" else rawParams

    /** Text for a log line: the redaction marker when [calendar] is true, else [text] clipped to [maxChars]. */
    fun text(calendar: Boolean, text: String, maxChars: Int): String =
        if (calendar) "$REDACTED (${text.length} chars)" else text.take(maxChars)
}
