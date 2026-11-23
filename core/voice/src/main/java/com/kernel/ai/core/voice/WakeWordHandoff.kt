package com.kernel.ai.core.voice

/**
 * A wake transcript the overlay could not receive (#2081). It stays in memory until the user taps
 * the Hey Jandal notification for [wakeToken], or until it expires undelivered. [heldAtMs] is
 * `SystemClock.elapsedRealtime()` when it was held, so expiry survives a sleeping CPU.
 */
data class HeldWakeTranscript(val wakeToken: Long, val text: String, val heldAtMs: Long)

/** Result of [WakeWordHandoff.takeHeld]. */
sealed interface HeldTake {
    /** The tap is in time: deliver [text] to the overlay. */
    data class Delivered(val text: String) : HeldTake

    /** The transcript outlived its window; it was dropped and the caller must journal the expiry. */
    data class Expired(val held: HeldWakeTranscript) : HeldTake

    /** Nothing is held for that token (already delivered or expired). */
    data object Absent : HeldTake
}

/**
 * In-process store for a transcript held for a notification tap (#2081). The overlay activity
 * ([VoiceCommandActivity], `:feature:widget`) and [WakeWordService] (`:app`) share it.
 */
object WakeWordHandoff {
    @Volatile
    private var held: HeldWakeTranscript? = null

    /** The transcript held for a notification tap, or null. */
    val heldTranscript: HeldWakeTranscript? get() = held

    /** Holds [transcript]. Returns the earlier held transcript it displaces, or null. */
    @Synchronized
    fun hold(transcript: HeldWakeTranscript): HeldWakeTranscript? {
        val displaced = held
        held = transcript
        return displaced
    }

    /**
     * Takes the held transcript for [wakeToken]. It is delivered only while younger than [maxAgeMs]
     * on the elapsed-realtime clock [nowMs]; an older one is dropped and returned as [HeldTake.Expired].
     */
    @Synchronized
    fun takeHeld(wakeToken: Long, nowMs: Long, maxAgeMs: Long): HeldTake {
        val current = held?.takeIf { it.wakeToken == wakeToken } ?: return HeldTake.Absent
        held = null
        return if (nowMs - current.heldAtMs < maxAgeMs) HeldTake.Delivered(current.text) else HeldTake.Expired(current)
    }

    /**
     * Drops the held transcript for [wakeToken] because its window closed. Returns the dropped entry, or
     * null when nothing is held for that token (for example because the overlay already took it).
     */
    @Synchronized
    fun expireHeld(wakeToken: Long): HeldWakeTranscript? {
        val current = held?.takeIf { it.wakeToken == wakeToken } ?: return null
        held = null
        return current
    }

    /** Drops every held transcript, for service teardown. Returns the dropped entry, or null. */
    @Synchronized
    fun dropAll(): HeldWakeTranscript? {
        val current = held
        held = null
        return current
    }
}
