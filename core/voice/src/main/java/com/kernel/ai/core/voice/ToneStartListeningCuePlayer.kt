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
 * Context-to-stream mapping:
 * - [StartListeningCueContext.FOREGROUND] → [AudioManager.STREAM_MUSIC] — respects the
 *   user's media/silent settings for in-app (Chat, Actions, widget, side-key) capture.
 * - [StartListeningCueContext.WAKE_WORD] → [AudioManager.STREAM_ALARM] — keeps the earcon
 *   audible during screen-off wake capture even in silent/vibrate mode.
 * - [StartListeningCueContext.CLOCK_ALERT] → [AudioManager.STREAM_ALARM] — audible while
 *   the alert ringtone is ducked for voice control.
 *
 * Production code never modifies user volume.
 */
@Singleton
class ToneStartListeningCuePlayer @Inject constructor() : StartListeningCuePlayer {

    private var defaultToneGeneratorInstance: ToneGenerator? = null
    private var audibleToneGeneratorInstance: ToneGenerator? = null

    private fun streamForContext(context: StartListeningCueContext): Int = when (context) {
        StartListeningCueContext.FOREGROUND -> DEFAULT_CUE_STREAM
        StartListeningCueContext.WAKE_WORD -> AUDIBLE_CUE_STREAM
        StartListeningCueContext.CLOCK_ALERT -> AUDIBLE_CUE_STREAM
    }

    private fun toneGenerator(context: StartListeningCueContext): ToneGenerator? {
        val isAudible = context != StartListeningCueContext.FOREGROUND
        val existing = if (isAudible) audibleToneGeneratorInstance else defaultToneGeneratorInstance
        if (existing != null) return existing

        val stream = streamForContext(context)
        val created = try {
            ToneGenerator(stream, CUE_VOLUME_PERCENT)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneStartListeningCuePlayer: failed to create ToneGenerator", e)
            null
        }
        if (isAudible) {
            audibleToneGeneratorInstance = created
        } else {
            defaultToneGeneratorInstance = created
        }
        return created
    }

    override fun playCue(context: StartListeningCueContext): StartListeningCueResult {
        val generator = toneGenerator(context)
        if (generator == null) {
            return StartListeningCueResult(started = false, failureCategory = "tone_generator_unavailable")
        }
        return try {
            val started = generator.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_DURATION_MS)
            if (started) {
                StartListeningCueResult(started = true)
            } else {
                StartListeningCueResult(started = false, failureCategory = "playback_start_failed")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneStartListeningCuePlayer: failed to play cue", e)
            StartListeningCueResult(started = false, failureCategory = "playback_exception")
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
