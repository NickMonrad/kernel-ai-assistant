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
    val minBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT,
    )
    require(minBuffer > 0) { "AudioTrack buffer size unavailable" }
    val track = AudioTrack.Builder()
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
    onTrackCreated(track)

    val scratch = if (gain != 1.0f) FloatArray(AUDIO_CHUNK_FLOATS) else null
    try {
        track.play()
        if (pitch != 1.0f) {
            track.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1.0f)
        }
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

        if (shouldContinue()) {
            // MODE_STREAM stop() is non-blocking. Padding with the hardware latency lets the real
            // speech tail drain before release() instead of being discarded by the output HAL.
            val latencyFrames =
                (sampleRate.toLong() * audioTrackLatencyMs(track).coerceIn(0, 400) / 1000L).toInt()
            if (latencyFrames > 0 && shouldContinue()) {
                track.write(FloatArray(latencyFrames), 0, latencyFrames, AudioTrack.WRITE_BLOCKING)
            }
            track.stop()
            while (shouldContinue() && track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                Thread.sleep(10)
            }
        } else {
            track.pause()
        }
    } finally {
        onTrackReleased(track)
        track.release()
    }
}

private const val AUDIO_CHUNK_FLOATS = 2048

private fun audioTrackLatencyMs(track: AudioTrack): Int = try {
    AudioTrack::class.java.getMethod("getLatency").invoke(track) as? Int ?: 0
} catch (_: Exception) {
    0
}
