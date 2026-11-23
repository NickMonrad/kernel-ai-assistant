package com.kernel.ai.assistant

import android.os.SystemClock
import com.kernel.ai.core.voice.HeldWakeTranscript
import com.kernel.ai.core.voice.WakeOverlayChannel
import com.kernel.ai.core.voice.WakeWordHandoff
import kotlinx.coroutines.delay

/** How long a delivered transcript may take to be taken by the overlay before it is held (#2081). */
internal const val WAKE_TRANSCRIPT_CONSUME_WAIT_MS = 2_000L

internal sealed interface WakeTranscriptHandoffResult {
    /** The attached overlay took the transcript and now owns it. */
    data object Consumed : WakeTranscriptHandoffResult

    /**
     * The transcript is held for a tap on the overlay notification. [reason] is `overlay_not_attached`
     * (no overlay is showing this capture) or `overlay_not_taken` (an overlay was attached but did not
     * take it in time). [displaced] is the earlier held transcript this one replaced, if any.
     */
    data class Held(val reason: String, val displaced: HeldWakeTranscript? = null) : WakeTranscriptHandoffResult
}

/**
 * Hands [transcript] to the overlay attached for [wakeToken] (#2081). The decision depends only on
 * whether that overlay is attached and live, not on the keyguard or screen state: an occluded
 * keyguard keeps the device "locked" while the overlay is visible, so a keyguard check would hold a
 * transcript the overlay can show. When nothing takes the transcript it is held for the tap, and
 * [clock] is read at the moment of holding, so the tap window starts when the transcript is held.
 */
internal suspend fun handOffWakeTranscript(
    transcript: String,
    wakeToken: Long,
    clock: () -> Long = SystemClock::elapsedRealtime,
    consumeWaitMs: Long = WAKE_TRANSCRIPT_CONSUME_WAIT_MS,
    pollMs: Long = 50L,
): WakeTranscriptHandoffResult {
    if (!WakeOverlayChannel.deliverTranscript(wakeToken, transcript)) {
        return hold(transcript, wakeToken, clock, "overlay_not_attached")
    }
    var waited = 0L
    while (!WakeOverlayChannel.isTranscriptTaken(wakeToken) && waited < consumeWaitMs) {
        delay(pollMs)
        waited += pollMs
    }
    if (WakeOverlayChannel.withdrawTranscript(wakeToken)) return WakeTranscriptHandoffResult.Consumed
    return hold(transcript, wakeToken, clock, "overlay_not_taken")
}

private fun hold(
    transcript: String,
    wakeToken: Long,
    clock: () -> Long,
    reason: String,
): WakeTranscriptHandoffResult.Held =
    WakeTranscriptHandoffResult.Held(
        reason = reason,
        displaced = WakeWordHandoff.hold(HeldWakeTranscript(wakeToken, transcript, clock())),
    )
