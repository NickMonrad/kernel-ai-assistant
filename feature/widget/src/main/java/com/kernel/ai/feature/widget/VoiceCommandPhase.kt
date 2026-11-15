package com.kernel.ai.feature.widget

import com.kernel.ai.core.voice.VoiceInputEvent

/** Visible phase of the voice overlay (#1850). Starting shows until the recognizer reports Listening. */
internal enum class VoiceCommandPhase { Starting, Listening, Processing }

/**
 * Phase after [event]. Only the events that move the overlay forward change it; Transcript,
 * Error and ListeningStopped end or retry the session in the activity, so the phase is kept.
 */
internal fun phaseAfter(current: VoiceCommandPhase, event: VoiceInputEvent): VoiceCommandPhase = when (event) {
    is VoiceInputEvent.ListeningStarted -> VoiceCommandPhase.Listening
    is VoiceInputEvent.SpeechEnded -> VoiceCommandPhase.Processing
    else -> current
}
