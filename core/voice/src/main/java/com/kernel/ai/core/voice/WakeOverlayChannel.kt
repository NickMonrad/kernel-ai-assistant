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
    private val _cancelRequested = MutableStateFlow(false)

    /** Called before the overlay is launched and the wake capture starts. */
    fun begin() {
        _cancelRequested.value = false
        _state.value = WakeOverlayState.Active(WakeOverlayPhase.Listening, "")
    }

    fun onEvent(event: VoiceInputEvent) {
        _state.update { it.onWakeEvent(event) }
    }

    /**
     * The user closed the overlay (X, backdrop, Back, Home). Only meaningful while a wake capture
     * is live; the service cancels the capture when it sees this.
     */
    fun requestCancel() {
        if (_state.value is WakeOverlayState.Active) _cancelRequested.value = true
    }

    /** Suspends until [requestCancel] is called for the current wake capture. */
    suspend fun awaitCancelRequest() {
        _cancelRequested.first { it }
    }

    /** True once [requestCancel] has been honoured for the current wake capture. */
    fun isCancelRequested(): Boolean = _cancelRequested.value

    /** A transcript was handed to the overlay activity, which owns the rest of the flow. */
    fun markRouted() {
        _state.update { if (it is WakeOverlayState.Active) WakeOverlayState.Ended(routed = true) else it }
    }

    /** The wake capture reached a terminal state (result, cancel, timeout or failure). */
    fun end() {
        _state.update { if (it is WakeOverlayState.Active) WakeOverlayState.Ended(routed = false) else it }
    }
}

