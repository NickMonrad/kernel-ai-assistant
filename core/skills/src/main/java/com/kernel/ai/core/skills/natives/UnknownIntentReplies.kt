package com.kernel.ai.core.skills.natives

/**
 * User-facing replies for `run_intent` names that are not registered in [NativeIntentHandler].
 *
 * The model sometimes invents intent names (e.g. `get_calendar` before #942 exists). Those must
 * never surface as raw "Unknown intent: X" text in chat or TTS. This table only chooses wording;
 * it never maps an unknown name onto a real intent, so nothing with side effects runs by guess.
 */
object UnknownIntentReplies {

    const val GENERIC_REPLY = "I can't do that one yet."

    private data class Area(val keywords: Set<String>, val reply: String)

    /** First matching area wins. Keywords are matched against whole `_`-separated name tokens. */
    private val AREAS = listOf(
        Area(
            keywords = setOf("calendar", "agenda"),
            reply = "I can't read your calendar yet, but I can add events to it. Want to add one?",
        ),
        Area(
            keywords = setOf("email", "mail", "inbox"),
            reply = "I can't read your email yet, but I can send one.",
        ),
    )

    /** Returns the friendly reply for an unregistered intent name. Never includes [intentName]. */
    fun replyFor(intentName: String): String {
        val tokens = intentName.lowercase().split('_', ' ', '-').filter { it.isNotBlank() }.toSet()
        return AREAS.firstOrNull { area -> area.keywords.any { it in tokens } }?.reply ?: GENERIC_REPLY
    }

    /**
     * Log-safe form of an LLM-supplied intent name. Keeps only `[a-z0-9_]` and truncates, so
     * stray user text echoed into the name cannot reach logcat verbatim.
     */
    fun loggableName(intentName: String): String =
        intentName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(40)
}
