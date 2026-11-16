package com.kernel.ai.core.voice

import kotlinx.coroutines.Deferred

/** Why the app stopped speech; logged as the `speak_end` reason in the support export (#1942). */
enum class SpeechStopReason {
    /** A user stop: stop button, speak-button toggle, volume key or media Stop. */
    USER,

    /** The screen went to the background (Home or an app switch; screen off keeps speaking, #1918). */
    BACKGROUND,

    /** A new command or reply took over before this one finished. */
    NEW_REPLY,
}

/** A controller that records [SpeechStopReason]; see [stopFor]. */
interface StopReasonAwareVoiceOutput {
    fun stop(reason: SpeechStopReason)
}

/**
 * Stops speech and records [reason] when the controller can log it. Any other controller (and test
 * doubles) gets a plain [VoiceOutputController.stop].
 */
fun VoiceOutputController.stopFor(reason: SpeechStopReason) {
    if (this is StopReasonAwareVoiceOutput) stop(reason) else stop()
}

/** How one streamed reply's playback ended, as seen by the engine that played it (#1942). */
internal enum class StreamPlaybackOutcome {
    /** Every queued chunk played to the end. */
    COMPLETED,

    /** Stopped or superseded before the end. */
    CANCELLED,

    /** Synthesis failed; the rest of the reply was not played. */
    FAILED,
}

/** A streaming session whose engine reports [StreamPlaybackOutcome] once playback has ended. */
internal interface ReportsStreamPlaybackOutcome {
    val playbackOutcome: Deferred<StreamPlaybackOutcome>
}
