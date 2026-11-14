package com.kernel.ai.core.skills.natives

/**
 * Calendar titles, locations, descriptions and attendee names are personal data. Every log line that
 * can carry a calendar intent's params or reply text goes through this object, so release logcat
 * shows the intent name and sizes only. Non-calendar intents keep their existing logging.
 */
object CalendarLogRedaction {

    const val REDACTED = "<calendar content redacted>"

    private val CALENDAR_INTENTS = setOf("create_calendar_event", "read_calendar_events")

    /** True when [text] names a calendar intent (a tool call, intent name or skill name). */
    fun mentionsCalendarIntent(text: String?): Boolean =
        text != null && CALENDAR_INTENTS.any { text.contains(it) }

    /** Params for a log line: keys only for calendar intents, unchanged otherwise. */
    fun params(intentName: String?, params: Map<String, *>): String =
        if (intentName != null && intentName in CALENDAR_INTENTS) {
            "{keys=${params.keys.sorted()}, values=$REDACTED}"
        } else {
            params.toString()
        }

    /** Raw params JSON for a log line: fully redacted for calendar intents, unchanged otherwise. */
    fun params(intentName: String?, rawParams: String): String =
        if (intentName != null && intentName in CALENDAR_INTENTS) {
            "{values=$REDACTED}"
        } else {
            rawParams
        }

    /** Text for a log line: the redaction marker when [context] names a calendar intent. */
    fun text(context: String?, text: String, maxChars: Int): String =
        if (mentionsCalendarIntent(context)) {
            "$REDACTED (${text.length} chars)"
        } else {
            text.take(maxChars)
        }
}
