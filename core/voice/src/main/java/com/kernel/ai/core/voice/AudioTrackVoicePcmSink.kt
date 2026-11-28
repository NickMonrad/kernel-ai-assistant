package com.kernel.ai.core.voice

import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicReference

/**
 * [VoicePcmSink] over AudioTrack, using the shared voice playback helpers. Each owning controller holds one instance;
 * [silenceActive] silences only the track this instance opened.
 */
class AudioTrackVoicePcmSink : VoicePcmSink {
    private val activeTrack = AtomicReference<AudioTrack?>(null)

    override fun open(sampleRate: Int, pitch: Float): VoicePcmTrack {
        val created = createVoicePcmTrack(sampleRate)
        try {
            startVoicePcmTrack(created, pitch)
        } catch (error: Throwable) {
            created.release()
            throw error
        }
        activeTrack.set(created)
        return AudioPcmTrack(created)
    }

    override fun silenceActive() {
        activeTrack.getAndSet(null)?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {
                // AudioTrack may already have completed on the playback thread.
            }
        }
    }

    private inner class AudioPcmTrack(private val track: AudioTrack) : VoicePcmTrack {
        override fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean) =
            writeVoicePcm(track, samples, gain, isCurrent)

        override fun drain(isCurrent: () -> Boolean) = drainVoicePcmTrack(track, isCurrent)

        override fun discard() = fadeOutAndDiscard(track)

        override fun underrunCount(): Int = track.underrunCount

        override fun release() {
            activeTrack.compareAndSet(track, null)
            track.release()
        }
    }
}
