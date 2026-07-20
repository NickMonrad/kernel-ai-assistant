package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioDeviceInfo
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
private const val CUE_POLICY_VERSION = "2026-07-cue-v1"

/** Pure mapping: cue context → Android stream constant. Extracted for local unit testing. */
fun streamForCueContext(context: StartListeningCueContext): Int = when (context) {
    StartListeningCueContext.FOREGROUND -> AudioManager.STREAM_MUSIC
    StartListeningCueContext.WAKE_WORD -> AudioManager.STREAM_ALARM
    StartListeningCueContext.CLOCK_ALERT -> AudioManager.STREAM_ALARM
}

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
class ToneStartListeningCuePlayer @Inject constructor(
    private val appContext: Context,
) : StartListeningCuePlayer {

    private var defaultToneGeneratorInstance: ToneGenerator? = null
    private var audibleToneGeneratorInstance: ToneGenerator? = null

    private val audioManager: AudioManager by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun streamForContext(context: StartListeningCueContext): Int = streamForCueContext(context)

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

    private fun currentRouteClassification(): String {
        return try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val active = devices.firstOrNull()
            when (active?.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built_in_speaker"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_a2dp"
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headset"
                else -> "unknown"
            }
        } catch (e: Exception) {
            Log.w(TAG, "ToneStartListeningCuePlayer: route classification unavailable", e)
            "unknown"
        }
    }

    private fun buildResult(
        started: Boolean,
        context: StartListeningCueContext,
        failureCategory: String? = null,
    ): StartListeningCueResult {
        val stream = streamForContext(context)
        val currentVolume = try {
            audioManager.getStreamVolume(stream)
        } catch (e: Exception) { null }
        val maxVolume = try {
            audioManager.getStreamMaxVolume(stream)
        } catch (e: Exception) { null }
        return StartListeningCueResult(
            started = started,
            context = context,
            policyVersion = CUE_POLICY_VERSION,
            selectedStream = stream,
            currentVolume = currentVolume,
            maxVolume = maxVolume,
            routeClassification = currentRouteClassification(),
            failureCategory = failureCategory,
        )
    }

    override fun playCue(context: StartListeningCueContext): StartListeningCueResult {
        val generator = toneGenerator(context)
        if (generator == null) {
            return buildResult(started = false, context = context, failureCategory = "tone_generator_unavailable")
        }
        return try {
            val started = generator.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_DURATION_MS)
            if (started) {
                buildResult(started = true, context = context)
            } else {
                buildResult(started = false, context = context, failureCategory = "playback_start_failed")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneStartListeningCuePlayer: failed to play cue", e)
            buildResult(started = false, context = context, failureCategory = "playback_exception")
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
