package com.kernel.ai.core.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"
private const val CUE_VOLUME_PERCENT = 60
private const val DEFAULT_CUE_STREAM = AudioManager.STREAM_MUSIC
private const val AUDIBLE_CUE_STREAM = AudioManager.STREAM_ALARM
private const val CUE_DURATION_MS = 100

/**
 * Plays a brief, non-jarring beep when the microphone becomes ready for speech.
 *
 * Default playback uses [AudioManager.STREAM_MUSIC] so in-app manual mic cues respect the
 * user's normal media/silent settings. Wake-word capture can request [forceAudible] to route
 * the cue through [AudioManager.STREAM_ALARM], keeping the earcon audible even when the device
 * is in silent or vibrate mode.
 */
@Singleton
class ToneStartListeningCuePlayer @Inject constructor() : StartListeningCuePlayer {

    private var defaultToneGeneratorInstance: ToneGenerator? = null
    private var audibleToneGeneratorInstance: ToneGenerator? = null

    private fun toneGenerator(forceAudible: Boolean): ToneGenerator? {
        val existing = if (forceAudible) audibleToneGeneratorInstance else defaultToneGeneratorInstance
        if (existing != null) return existing

        val stream = if (forceAudible) AUDIBLE_CUE_STREAM else DEFAULT_CUE_STREAM
        val created = try {
            ToneGenerator(stream, CUE_VOLUME_PERCENT)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneStartListeningCuePlayer: failed to create ToneGenerator", e)
            null
        }
        if (forceAudible) {
            audibleToneGeneratorInstance = created
        } else {
            defaultToneGeneratorInstance = created
        }
        return created
    }

    override fun playCue(forceAudible: Boolean) {
        try {
            toneGenerator(forceAudible)?.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_DURATION_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneStartListeningCuePlayer: failed to play cue", e)
        }
    }

    override fun release() {
        fun releaseInstance(instance: ToneGenerator?) {
            try {
                instance?.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "ToneStartListeningCuePlayer: failed to release ToneGenerator", e)
            }
        }

        releaseInstance(defaultToneGeneratorInstance)
        releaseInstance(audibleToneGeneratorInstance)
        defaultToneGeneratorInstance = null
        audibleToneGeneratorInstance = null
    }
}
