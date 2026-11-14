package com.kernel.ai.core.skills.natives

/**
 * User-facing replies for `run_intent` names that are not registered in [NativeIntentHandler].
 *
 * The model sometimes invents intent names (e.g. `get_calendar` before #942 exists). Those must
 * never surface as raw "Unknown intent: X" text in chat or TTS. This object only chooses wording.
 * It never maps an unknown name onto a real intent, so nothing with side effects runs by guess.
 */
object UnknownIntentReplies {

    /** Neutral reply for unregistered names that are not a calendar/email read. */
    const val GENERIC_REPLY = "I couldn't do that one. Try asking another way."

    const val CALENDAR_READ_REPLY =
        "I can't read your calendar yet. I can add events to it if you like."

    const val EMAIL_READ_REPLY = "I can't read your email yet, but I can send one."

    private val CALENDAR_NOUNS = setOf(
        "calendar", "calendars", "agenda", "agendas", "schedule", "schedules",
        "event", "events", "appointment", "appointments", "meeting", "meetings", "diary", "diaries",
    )
    private val EMAIL_NOUNS = setOf("email", "emails", "mail", "mails", "inbox", "inboxes")

    /**
     * Verbs that mark a write-style request. A name containing one never gets a read reply.
     * Names without one are reads when they contain a calendar or email noun, which covers
     * "get_calendar", "list_emails" and bare "calendar_events" / "todays_schedule".
     */
    private val WRITE_VERBS = setOf(
        "add", "create", "delete", "remove", "send", "compose", "update", "edit", "book", "cancel",
        "set", "make", "reply", "forward", "move", "modify", "reschedule", "invite", "accept",
        "decline", "rsvp", "new", "change", "plan", "arrange", "organise", "organize", "setup",
        "block", "save", "insert", "post", "draft", "write", "archive", "mark", "clear",
    )

    /** Returns the friendly reply for an unregistered intent name. Never includes [intentName]. */
    fun replyFor(intentName: String): String {
        val words = intentName.lowercase()
            .split('_', ' ', '-', '\'', '’')
            .filter { it.isNotBlank() }
        // "schedule" is a verb only in leading position ("schedule_meeting"); otherwise a noun
        // ("check_schedule", "todays_schedule").
        val isWrite = words.any { it in WRITE_VERBS } || words.firstOrNull() == "schedule"
        val tokens = words.toSet()
        return when {
            isWrite -> GENERIC_REPLY
            tokens.any { it in CALENDAR_NOUNS } -> CALENDAR_READ_REPLY
            tokens.any { it in EMAIL_NOUNS } -> EMAIL_READ_REPLY
            else -> GENERIC_REPLY
        }
    }

    /**
     * Log-safe form of an LLM-supplied intent name. Keeps only `[a-z0-9_]` and truncates, so
     * stray user text echoed into the name cannot reach logcat verbatim.
     */
    fun loggableName(intentName: String): String =
        intentName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(40)
}
