package com.kernel.ai.core.voice

/**
 * A wake transcript the overlay could not receive (#2081). It stays in memory until the user taps
 * the Hey Jandal notification for [wakeToken], or until it expires undelivered.
 */
data class HeldWakeTranscript(val wakeToken: Long, val text: String)

/**
 * In-process handoff channel between [WakeWordService] (`:app`) and
 * [VoiceCommandActivity] (`:feature:widget`).
 *
 * **Security:** [VoiceCommandActivity] is `exported=true` (required for Android assistant
 * eligibility). To prevent external apps from injecting arbitrary transcripts via
 * [EXTRA_PREFILLED_TRANSCRIPT], [WakeWordService] sets [pendingTranscript] in JVM memory
 * immediately before calling `startActivity`. [VoiceCommandActivity] reads and clears it,
 * and only trusts the intent extra when the in-process value matches. External callers
 * cannot write this field.
 */
object WakeWordHandoff {
    @Volatile
    var pendingTranscript: String? = null

    @Volatile
    private var held: HeldWakeTranscript? = null

    /** The transcript held for a notification tap, or null. */
    val heldTranscript: HeldWakeTranscript? get() = held

    @Synchronized
    fun hold(transcript: HeldWakeTranscript) {
        held = transcript
    }

    /** Returns the held text when the tap is for [wakeToken] and clears it; null otherwise. */
    @Synchronized
    fun takeHeld(wakeToken: Long): String? {
        val current = held?.takeIf { it.wakeToken == wakeToken } ?: return null
        held = null
        return current.text
    }

    /**
     * Drops the held transcript for [wakeToken] (and its pending token) because it was never
     * delivered. Returns false when nothing is held for that token, for example because the
     * overlay already consumed it.
     */
    @Synchronized
    fun expireHeld(wakeToken: Long): Boolean {
        val current = held?.takeIf { it.wakeToken == wakeToken } ?: return false
        held = null
        if (pendingTranscript == current.text) pendingTranscript = null
        return true
    }

    /** The overlay consumed the transcript: nothing remains held. */
    @Synchronized
    fun clearHeld() {
        held = null
    }
}
