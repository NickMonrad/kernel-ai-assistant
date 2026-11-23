package com.kernel.ai.assistant

import com.kernel.ai.core.voice.HeldWakeTranscript
import com.kernel.ai.core.voice.WakeWordHandoff
import kotlinx.coroutines.delay

/** How long a routed transcript may take to reach the overlay before it counts as undelivered (#2081). */
internal const val WAKE_TRANSCRIPT_CONSUME_WAIT_MS = 2_000L

internal sealed interface WakeTranscriptHandoffResult {
    /** The overlay activity took the transcript and is now responsible for it. */
    data object Consumed : WakeTranscriptHandoffResult

    /**
     * The overlay did not take it, so the transcript is held for a tap on the notification.
     * [reason] is `start_skipped` (no start was attempted, because the screen is off or locked),
     * `start_refused` (the start threw) or `overlay_not_shown` (the start returned but the overlay
     * never consumed it, as with a background-activity-launch block, which is silent).
     */
    data class Held(val reason: String) : WakeTranscriptHandoffResult
}

/**
 * Whether to attempt a direct overlay start for a transcript (#2081). A screen-off or locked device
 * blocks a background activity start, and each blocked attempt logs a BAL wtf at E level. Those
 * attempts are skipped: the transcript is held for the tap notification instead.
 */
internal fun shouldStartOverlayForTranscript(interactive: Boolean, keyguardLocked: Boolean): Boolean =
    interactive && !keyguardLocked

/**
 * Hands [transcript] to the overlay activity and checks that it was consumed (#2081).
 *
 * The overlay consumes the transcript by clearing [WakeWordHandoff.pendingTranscript]. A blocked
 * activity start does not throw, so the only signal is that the token stays set. When it does,
 * the transcript is held for [wakeToken] rather than dropped, and the notification tap delivers it.
 * A null [startOverlay] means no start is attempted, and the transcript is held straight away.
 */
internal suspend fun handOffWakeTranscript(
    transcript: String,
    wakeToken: Long,
    startOverlay: (() -> Unit)?,
    consumeWaitMs: Long = WAKE_TRANSCRIPT_CONSUME_WAIT_MS,
    pollMs: Long = 50L,
): WakeTranscriptHandoffResult {
    if (startOverlay == null) {
        WakeWordHandoff.pendingTranscript = null
        WakeWordHandoff.hold(HeldWakeTranscript(wakeToken, transcript))
        return WakeTranscriptHandoffResult.Held("start_skipped")
    }
    WakeWordHandoff.pendingTranscript = transcript
    val started = runCatching(startOverlay).isSuccess
    var waited = 0L
    while (started && WakeWordHandoff.pendingTranscript == transcript && waited < consumeWaitMs) {
        delay(pollMs)
        waited += pollMs
    }
    if (WakeWordHandoff.pendingTranscript != transcript) return WakeTranscriptHandoffResult.Consumed
    // Clear the in-process authorization: a held transcript is delivered by tap, not by the overlay.
    WakeWordHandoff.pendingTranscript = null
    WakeWordHandoff.hold(HeldWakeTranscript(wakeToken, transcript))
    return WakeTranscriptHandoffResult.Held(if (started) "overlay_not_shown" else "start_refused")
}
