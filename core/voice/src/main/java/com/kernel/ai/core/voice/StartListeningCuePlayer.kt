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
 * Bounded, non-sensitive evidence from one cue-playback attempt.
 *
 * All fields carry stable, privacy-safe values suitable for structured journal
 * metadata.  [currentVolume] and [maxVolume] are the Android stream volumes at
 * playback time — the player never modifies user volume.
 *
 * [routeClassification] is a best-effort Android output-route label
 * ("built_in_speaker", "bluetooth_a2dp", "wired_headset", "unknown") derived
 * from AudioManager at playback time.  It is absent when the platform API
 * cannot provide it reliably.
 */
data class StartListeningCueResult(
    val started: Boolean,
    val context: StartListeningCueContext,
    val policyVersion: String = "2026-07-cue-v1",
    val selectedStream: Int? = null,
    val currentVolume: Int? = null,
    val maxVolume: Int? = null,
    val routeClassification: String? = null,
    val failureCategory: String? = null,
) {
    companion object {
        fun failed(
            context: StartListeningCueContext,
            failureCategory: String,
        ) = StartListeningCueResult(
            started = false,
            context = context,
            failureCategory = failureCategory,
        )
    }

}
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
