package com.kernel.ai.core.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams

/**
 * Shared mono Float32 AudioTrack path for local voice-output controllers.
 *
 * Controllers own cancellation, generation and active-track state; this helper owns the common
 * streaming, gain, pitch and hardware-tail handling so experimental backends cannot drift into a
 * second playback implementation.
 */
internal fun playVoicePcmOnAudioTrack(
    samples: FloatArray,
    sampleRate: Int,
    gain: Float = 1.0f,
    pitch: Float = 1.0f,
    shouldContinue: () -> Boolean,
    onTrackCreated: (AudioTrack) -> Unit = {},
    onTrackReleased: (AudioTrack) -> Unit = {},
) {
    val track = createVoicePcmTrack(sampleRate)
    onTrackCreated(track)
    try {
        startVoicePcmTrack(track, pitch)
        writeVoicePcm(track, samples, gain, shouldContinue)
        if (shouldContinue()) {
            drainVoicePcmTrack(track, shouldContinue)
        } else {
            fadeOutAndDiscard(track)
        }
    } finally {
        onTrackReleased(track)
        track.release()
    }
}

/**
 * Builds the normal-latency mono Float32 streaming track shared by the local voice controllers. The caller owns the
 * lifecycle: [startVoicePcmTrack], [writeVoicePcm], then [drainVoicePcmTrack] or [fadeOutAndDiscard], then release.
 */
internal fun createVoicePcmTrack(sampleRate: Int): AudioTrack {
    val minBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT,
    )
    require(minBuffer > 0) { "AudioTrack buffer size unavailable" }
    val track = AudioTrack.Builder()
        // Normal-latency output. A low-latency (fast) track has a tiny callback period; the Honor logged
        // "mCbf call back interval is abnormal for low latency track" during the crackling stops (#1942).
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minBuffer, AUDIO_CHUNK_FLOATS * Float.SIZE_BYTES * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
    return track
}

internal fun startVoicePcmTrack(track: AudioTrack, pitch: Float) {
    track.play()
    if (pitch != 1.0f) {
        track.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1.0f)
    }
}

/** Writes [samples] in blocks and stops early once [shouldContinue] is false. [gain] is applied with clamping. */
internal fun writeVoicePcm(track: AudioTrack, samples: FloatArray, gain: Float, shouldContinue: () -> Boolean) {
    val scratch = if (gain != 1.0f) FloatArray(AUDIO_CHUNK_FLOATS) else null
    var offset = 0
    while (offset < samples.size && shouldContinue()) {
        val end = minOf(offset + AUDIO_CHUNK_FLOATS, samples.size)
        val length = end - offset
        if (scratch != null) {
            for (index in 0 until length) {
                scratch[index] = (samples[offset + index] * gain).coerceIn(-1.0f, 1.0f)
            }
            track.write(scratch, 0, length, AudioTrack.WRITE_BLOCKING)
        } else {
            track.write(samples, offset, length, AudioTrack.WRITE_BLOCKING)
        }
        offset = end
    }
}

/**
 * Lets the queued audio finish: pads the hardware latency with silence, then stops and waits for the track to drain.
 * Call only while playback should continue; a cut-short track goes to [fadeOutAndDiscard] instead.
 */
internal fun drainVoicePcmTrack(track: AudioTrack, shouldContinue: () -> Boolean) {
    // MODE_STREAM stop() is non-blocking. Padding with the hardware latency lets the real
    // speech tail drain before release() instead of being discarded by the output HAL.
    val latencyFrames =
        (track.sampleRate.toLong() * audioTrackLatencyMs(track).coerceIn(0, 400) / 1000L).toInt()
    if (latencyFrames > 0 && shouldContinue()) {
        track.write(FloatArray(latencyFrames), 0, latencyFrames, AudioTrack.WRITE_BLOCKING)
    }
    track.stop()
    while (shouldContinue() && track.playState != AudioTrack.PLAYSTATE_STOPPED) {
        Thread.sleep(10)
    }
}

/**
 * Stops a cut-short track without a click. Pausing and flushing at full volume leaves the waveform
 * at a non-zero level, which the output hears as a crackle. The mixer ramps volume changes, so the
 * queued audio fades to silence over [FADE_OUT_MS] before the buffer is paused and discarded.
 */
internal fun fadeOutAndDiscard(track: AudioTrack) {
    try {
        track.setVolume(0.0f)
        Thread.sleep(FADE_OUT_MS)
    } catch (_: Exception) {
        // A track the framework already tore down: nothing left to fade.
    }
    track.pause()
    track.flush()
}

private const val FADE_OUT_MS = 30L
private const val AUDIO_CHUNK_FLOATS = 2048

private fun audioTrackLatencyMs(track: AudioTrack): Int = try {
    AudioTrack::class.java.getMethod("getLatency").invoke(track) as? Int ?: 0
} catch (_: Exception) {
    0
}
