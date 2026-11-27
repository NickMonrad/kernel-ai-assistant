package com.kernel.ai.debug.tts

import com.kernel.ai.core.voice.VoicePcmSink
import com.kernel.ai.core.voice.VoicePcmTrack
import com.kernel.ai.debug.sample.SampleWav

/**
 * Debug-only (#2096, #2114) [VoicePcmSink] that keeps the waveforms the reply stream would send to AudioTrack, in
 * order, instead of playing them. Gain is applied as `writeVoicePcm` does (multiply, clamp). Every write is one chunk;
 * each chunk's `[startFrame, endFrame)` is recorded in the output. No silence is inserted between chunks, because the
 * production path inserts none (the 5 ms edge fades are already in the waveform).
 */
internal class ChunkWavRecorder : VoicePcmSink {
    private val waveforms = ArrayList<FloatArray>()
    val replies = ArrayList<ReplyRecord>()
    var frames = 0
        private set

    override fun open(sampleRate: Int, pitch: Float): VoicePcmTrack {
        val reply = ReplyRecord(startFrame = frames)
        replies += reply
        return RecordingTrack(reply)
    }

    override fun silenceActive() = Unit

    /** Concatenates every recorded waveform, in write order. */
    fun samples(): FloatArray {
        val out = FloatArray(frames)
        var offset = 0
        for (waveform in waveforms) {
            waveform.copyInto(out, offset)
            offset += waveform.size
        }
        return out
    }

    class ReplyRecord(val startFrame: Int) {
        var endFrame: Int = startFrame
        val chunks = ArrayList<ChunkRecord>()
    }

    data class ChunkRecord(val index: Int, val startFrame: Int, val endFrame: Int)

    private inner class RecordingTrack(private val reply: ReplyRecord) : VoicePcmTrack {
        override fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean) {
            val start = frames
            val gained = SampleWav.applyGain(samples, gain)
            waveforms += gained
            frames += gained.size
            reply.chunks += ChunkRecord(index = reply.chunks.size, startFrame = start, endFrame = frames)
            reply.endFrame = frames
        }

        override fun drain(isCurrent: () -> Boolean) = Unit

        override fun discard() = Unit

        override fun release() {
            reply.endFrame = frames
        }
    }
}
