package com.kernel.ai.feature.widget

import com.kernel.ai.core.voice.NO_SPEECH_WINDOW_EXHAUSTED
import com.kernel.ai.core.voice.VoiceInputEvent

/** Visible phase of the voice overlay (#1850). Starting shows until the recognizer reports Listening. */
internal enum class VoiceCommandPhase { Starting, Listening, Processing }

/**
 * Whether an overlay Error restarts the capture. A silent window is not retried, matching chat and
 * Actions (#2051). Other errors get the single retry that [mayRetry] allows.
 */
internal fun restartsAfterError(event: VoiceInputEvent.Error, mayRetry: Boolean): Boolean =
    mayRetry && event.category != NO_SPEECH_WINDOW_EXHAUSTED

/**
 * Phase after [event]. Listening and speech end move the overlay forward. Error resets it to
 * Starting because the activity retries once (or shows the unavailable notice) and the recognizer
 * is not live again until ListeningStarted. Transcript and ListeningStopped keep the phase: the
 * activity finishes on a transcript.
 */
internal fun phaseAfter(current: VoiceCommandPhase, event: VoiceInputEvent): VoiceCommandPhase = when (event) {
    is VoiceInputEvent.ListeningStarted -> VoiceCommandPhase.Listening
    is VoiceInputEvent.SpeechEnded -> VoiceCommandPhase.Processing
    is VoiceInputEvent.Error -> VoiceCommandPhase.Starting
    else -> current
}

/**
 * Main line and optional phase caption for the voice overlay. Partial text is the main line, but
 * the phase label stays visible beneath it so "Processing…" is not replaced by partials (#1930, O2).
 * [failed] shows the error text alone.
 */
internal fun voiceOverlayLabels(
    phase: VoiceCommandPhase,
    partialText: String,
    failed: Boolean,
): Pair<String, String?> {
    val label = when (phase) {
        VoiceCommandPhase.Starting -> "Starting…"
        VoiceCommandPhase.Listening -> "Listening…"
        VoiceCommandPhase.Processing -> "Processing…"
    }
    return when {
        failed -> partialText to null
        partialText.isEmpty() -> label to null
        else -> partialText to label
    }
}
