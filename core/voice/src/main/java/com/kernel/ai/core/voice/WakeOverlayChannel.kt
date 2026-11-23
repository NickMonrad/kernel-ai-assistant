package com.kernel.ai.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** #2081: notification ID of the Hey Jandal overlay notification, shared by the service and the overlay. */
const val WAKE_OVERLAY_NOTIFICATION_ID = 9_501

/** #2081: how long a notification-held wake transcript stays deliverable by tap. */
const val WAKE_OVERLAY_NOTIFICATION_TIMEOUT_MS = 15_000L

/** #2081: silent channel for the overlay notification. A new ID: channel sound cannot change once created. */
const val WAKE_OVERLAY_CHANNEL_ID = "kernel_wake_overlay_silent"

/** Phase of the Hey Jandal overlay (#1930). The UI maps this onto its own phase labels. */
enum class WakeOverlayPhase { Listening, Processing }

/**
 * State of the Hey Jandal overlay, published by [WakeWordService] and observed by the overlay
 * activity. Sticky ([StateFlow]) so an activity that starts late still sees the current phase.
 */
sealed interface WakeOverlayState {
    /** No wake capture is in progress; the overlay must not be shown. */
    data object Idle : WakeOverlayState

    /** A wake capture is live: [partialText] is the latest partial transcript, possibly empty. */
    data class Active(val phase: WakeOverlayPhase, val partialText: String) : WakeOverlayState

    /**
     * The wake capture ended. [routed] is true when a transcript was handed to the overlay
     * activity (which then shows its result flash); false means cancel, timeout or failure, and
     * the overlay must close.
     */
    data class Ended(val routed: Boolean) : WakeOverlayState
}

/**
 * Phase after [event] for an in-flight wake capture. Only [WakeOverlayState.Active] reacts.
 * Partial transcripts never move the phase: [WakeOverlayPhase.Processing] stays set after
 * speech ends, so the overlay keeps its phase label while partial text keeps arriving (#1930).
 */
internal fun WakeOverlayState.onWakeEvent(event: VoiceInputEvent): WakeOverlayState {
    if (this !is WakeOverlayState.Active) return this
    return when (event) {
        is VoiceInputEvent.ListeningStarted -> copy(phase = WakeOverlayPhase.Listening, partialText = "")
        is VoiceInputEvent.PartialTranscript -> copy(partialText = event.text)
        is VoiceInputEvent.SpeechEnded -> copy(phase = WakeOverlayPhase.Processing)
        // The service retries once; the retry's ListeningStarted restores the Listening phase.
        is VoiceInputEvent.Error -> WakeOverlayState.Active(WakeOverlayPhase.Listening, "")
        is VoiceInputEvent.Transcript,
        is VoiceInputEvent.SpeechDetected,
        is VoiceInputEvent.ListeningStopped,
        is VoiceInputEvent.ListeningRequested,
        -> this
    }
}

/**
 * In-process bridge between the wake capture in [WakeWordService] and the overlay (#1930, #2081).
 *
 * A final transcript reaches the overlay only through [deliverTranscript], and only while an
 * overlay is [attachOverlay]ed for the same live capture. The overlay takes it with [awaitTranscript],
 * which records ownership under the same lock that [withdrawTranscript] uses. The transcript never
 * travels through an activity start, so a screen-off or locked device needs no background launch.
 */
object WakeOverlayChannel {
    private val _state = MutableStateFlow<WakeOverlayState>(WakeOverlayState.Idle)
    val state: StateFlow<WakeOverlayState> = _state.asStateFlow()
    private val _cancelRequested = MutableStateFlow(0L)

    private class PendingTranscript(val token: Long, val text: String)

    private val lock = Any()
    private val pending = MutableStateFlow<PendingTranscript?>(null)

    @Volatile
    private var currentToken = 0L

    /** Token of the overlay attached for the live capture, or 0 when none is attached. Guarded by [lock]. */
    private var attachedToken = 0L

    /** Token whose transcript an overlay took (owns it). Guarded by [lock]. */
    private var takenToken = 0L

    /**
     * Called when a wake capture starts (single writer: the service's serviceScope). Returns the
     * token that identifies this capture; a closed overlay holding an older token cannot cancel a
     * newer capture.
     */
    fun begin(): Long {
        currentToken += 1
        _state.value = WakeOverlayState.Active(WakeOverlayPhase.Listening, "")
        return currentToken
    }

    /** Token of the latest wake capture; a held transcript is keyed by it (#2081). */
    fun currentToken(): Long = currentToken

    /** True while [token] names the capture that is currently [WakeOverlayState.Active]. */
    fun isLive(token: Long): Boolean = token == currentToken && _state.value is WakeOverlayState.Active

    /** The overlay activity for [token] is on screen and reading this channel (#2081). */
    fun attachOverlay(token: Long) = synchronized(lock) {
        attachedToken = token
        pending.value = null
    }

    /** The overlay for [token] closed; it no longer receives transcripts (#2081). */
    fun detachOverlay(token: Long) = synchronized(lock) {
        if (attachedToken == token) {
            attachedToken = 0L
            pending.value = null
        }
    }

    /** True when an overlay is attached for [token] and that capture is still live (#2081). */
    fun isOverlayAttached(token: Long): Boolean = synchronized(lock) {
        attachedToken == token && isLive(token)
    }

    /**
     * Offers [text] to the overlay attached for [token]. Returns false, and offers nothing, when no
     * overlay is attached for that live capture (#2081).
     */
    fun deliverTranscript(token: Long, text: String): Boolean = synchronized(lock) {
        if (!isOverlayAttached(token)) return false
        pending.value = PendingTranscript(token, text)
        true
    }

    /**
     * Overlay side: suspends until a transcript for [token] is offered, then takes it and records that
     * the overlay owns it. Ownership is recorded under the lock, so a service withdrawal cannot race it.
     */
    suspend fun awaitTranscript(token: Long): String {
        while (true) {
            pending.first { it?.token == token }
            synchronized(lock) {
                val offered = pending.value
                if (offered?.token == token) {
                    pending.value = null
                    takenToken = token
                    return offered.text
                }
            }
        }
    }

    /** True once the overlay took the transcript for [token]. */
    fun isTranscriptTaken(token: Long): Boolean = synchronized(lock) { takenToken == token }

    /**
     * Ends an untaken delivery for [token]. Returns true when the overlay already owns the transcript
     * (nothing to hold); otherwise withdraws the offer so no overlay can take it later, and returns false.
     */
    fun withdrawTranscript(token: Long): Boolean = synchronized(lock) {
        if (takenToken == token) return true
        if (pending.value?.token == token) pending.value = null
        false
    }

    /** The user closed the overlay for capture [token]. Ignored unless that capture is live. */
    fun requestCancel(token: Long) {
        if (isLive(token)) _cancelRequested.value = token
    }

    /** Suspends until [requestCancel] is called for capture [token]. */
    suspend fun awaitCancelRequest(token: Long) {
        _cancelRequested.first { it == token }
    }

    /** True once [requestCancel] has been honoured for capture [token]. */
    fun isCancelRequested(token: Long): Boolean = _cancelRequested.value == token

    fun onEvent(event: VoiceInputEvent) {
        _state.update { it.onWakeEvent(event) }
    }

    /** A transcript was handed to the overlay activity, which owns the rest of the flow. */
    fun markRouted() {
        _state.update { if (it is WakeOverlayState.Active) WakeOverlayState.Ended(routed = true) else it }
    }

    /** The wake capture reached a terminal state (result, cancel, timeout or failure). */
    fun end() {
        _state.update { if (it is WakeOverlayState.Active) WakeOverlayState.Ended(routed = false) else it }
    }
}
