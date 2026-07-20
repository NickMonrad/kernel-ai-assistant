package com.kernel.ai.core.voice

/** Semantic context for the start-listening cue — never raw Android stream constants. */
enum class StartListeningCueContext {
    /** In-app foreground capture (Chat, Actions, widget, side-key, assistant). */
    FOREGROUND,
    /** Screen-off wake-word-initiated capture. */
    WAKE_WORD,
    /** Clock alert/timer voice control while the alert ringtone is playing. */
    CLOCK_ALERT,
}

/**
 * Bounded result returned by [StartListeningCuePlayer.playCue].
 *
 * [started] is true only when the underlying playback API confirmed a successful start.
 * [failureCategory] is non-null only when [started] is false.
 */
data class StartListeningCueResult(
    val started: Boolean,
    val failureCategory: String? = null,
)

/**
 * Plays a short audio cue to signal that voice capture is ready for speech.
 *
 * The cue is played when [VoiceInputEvent.ListeningStarted] fires — i.e. when
 * `onReadyForSpeech` is received from the recognizer (or the Vosk settle delay
 * has elapsed). This ensures the user hears the cue only after the microphone
 * is truly open, not on button-press alone.
 *
 * Call sites must not select Android stream constants; every context-to-policy
 * mapping is centralised in the implementation.
 */
interface StartListeningCuePlayer {
    fun playCue(context: StartListeningCueContext): StartListeningCueResult

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
