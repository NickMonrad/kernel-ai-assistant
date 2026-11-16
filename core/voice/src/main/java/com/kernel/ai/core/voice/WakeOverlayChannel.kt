package com.kernel.ai.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

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
        -> this
    }
}

/** In-process bridge between the wake capture in [WakeWordService] and the overlay (#1930). */
object WakeOverlayChannel {
    private val _state = MutableStateFlow<WakeOverlayState>(WakeOverlayState.Idle)
    val state: StateFlow<WakeOverlayState> = _state.asStateFlow()
    private val _cancelRequested = MutableStateFlow(0L)

    @Volatile
    private var currentToken = 0L

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

    /** True while [token] names the capture that is currently [WakeOverlayState.Active]. */
    fun isLive(token: Long): Boolean = token == currentToken && _state.value is WakeOverlayState.Active

    fun onEvent(event: VoiceInputEvent) {
        _state.update { it.onWakeEvent(event) }
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

    /** A transcript was handed to the overlay activity, which owns the rest of the flow. */
    fun markRouted() {
        _state.update { if (it is WakeOverlayState.Active) WakeOverlayState.Ended(routed = true) else it }
    }

    /** The wake capture reached a terminal state (result, cancel, timeout or failure). */
    fun end() {
        _state.update { if (it is WakeOverlayState.Active) WakeOverlayState.Ended(routed = false) else it }
    }
}

