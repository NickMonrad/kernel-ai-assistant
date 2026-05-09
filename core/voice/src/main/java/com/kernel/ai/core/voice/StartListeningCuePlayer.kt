package com.kernel.ai.core.voice

/**
 * Plays a short audio cue to signal that voice capture is ready for speech.
 *
 * The cue is played when [VoiceInputEvent.ListeningStarted] fires — i.e. when
 * `onReadyForSpeech` is received from the recognizer (or the Vosk settle delay
 * has elapsed). This ensures the user hears the cue only after the microphone
 * is truly open, not on button-press alone.
 */
interface StartListeningCuePlayer {
    fun playCue()
}
