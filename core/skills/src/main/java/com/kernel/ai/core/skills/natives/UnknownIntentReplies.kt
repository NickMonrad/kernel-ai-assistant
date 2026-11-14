package com.kernel.ai.core.skills.natives

/**
 * User-facing replies for `run_intent` names that are not registered in [NativeIntentHandler].
 *
 * The model sometimes invents intent names (e.g. `get_calendar` before #942 exists). Those must
 * never surface as raw "Unknown intent: X" text in chat or TTS. This object only chooses wording.
 * It never maps an unknown name onto a real intent, so nothing with side effects runs by guess.
 */
object UnknownIntentReplies {

    const val GENERIC_REPLY = "I can't do that one yet."

    const val CALENDAR_READ_REPLY =
        "I can't read your calendar yet. I can add events to it if you like."

    const val EMAIL_READ_REPLY = "I can't read your email yet, but I can send one."

    private val CALENDAR_NOUNS = setOf(
        "calendar", "calendars", "agenda", "agendas", "schedule", "schedules",
        "event", "events", "appointment", "appointments",
    )
    private val EMAIL_NOUNS = setOf("email", "emails", "mail", "mails", "inbox", "inboxes")

    /** Only these verbs mark a read-style request. Write-style names get [GENERIC_REPLY]. */
    private val READ_VERBS = setOf(
        "get", "read", "list", "check", "show", "view", "fetch", "search", "find",
        "what", "whats", "lookup", "query", "display", "tell",
    )

    /** Returns the friendly reply for an unregistered intent name. Never includes [intentName]. */
    fun replyFor(intentName: String): String {
        val tokens = intentName.lowercase()
            .split('_', ' ', '-', '\'', '’')
            .filter { it.isNotBlank() }
            .toSet()
        val isRead = tokens.any { it in READ_VERBS }
        return when {
            isRead && tokens.any { it in CALENDAR_NOUNS } -> CALENDAR_READ_REPLY
            isRead && tokens.any { it in EMAIL_NOUNS } -> EMAIL_READ_REPLY
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
