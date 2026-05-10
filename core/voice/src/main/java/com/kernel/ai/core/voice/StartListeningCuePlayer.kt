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

    /**
     * Releases any native audio resources held by this player.
     *
     * Implementations that hold a [android.media.ToneGenerator] or similar native resource
     * should free it here. The default no-op keeps existing callers that don't hold native
     * resources from having to override this method.
     *
     * For process-lifetime singletons this is a recovery hook — it is not called automatically
     * on ViewModel teardown; callers must invoke it explicitly when a bad audio state is detected.
     */
    fun release() { /* no-op by default */ }
}
