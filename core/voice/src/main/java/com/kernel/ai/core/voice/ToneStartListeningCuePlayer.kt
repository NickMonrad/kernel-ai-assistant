package com.kernel.ai.core.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"
private const val CUE_VOLUME_PERCENT = 60
private const val CUE_DURATION_MS = 100

/**
 * Plays a brief, non-jarring beep via [ToneGenerator] when the microphone
 * becomes ready for speech.
 *
 * Uses [ToneGenerator.TONE_PROP_BEEP] on [AudioManager.STREAM_MUSIC] at 60%
 * volume for a 100 ms duration — a short, clean UI earcon that mirrors the
 * convention used by [LoopListeningCue].
 */
@Singleton
class ToneStartListeningCuePlayer @Inject constructor() : StartListeningCuePlayer {

    private val toneGenerator by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME_PERCENT)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneStartListeningCuePlayer: failed to create ToneGenerator", e)
            null
        }
    }

    override fun playCue() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_DURATION_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneStartListeningCuePlayer: failed to play cue", e)
        }
    }
}
