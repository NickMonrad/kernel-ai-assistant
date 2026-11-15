package com.kernel.ai.feature.widget

import com.kernel.ai.core.voice.VoiceInputEvent

/** Visible phase of the voice overlay (#1850). Starting shows until the recognizer reports Listening. */
internal enum class VoiceCommandPhase { Starting, Listening, Processing }

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
