package com.kernel.ai.core.voice.kokoro

import android.util.Log
import com.kernel.ai.core.voice.VoicePcmSink
import com.kernel.ai.core.voice.VoicePcmTrack

/**
 * Stage timings for one Kokoro reply (#2117, time to first sound). One `VoiceTiming` line per stage. `epochMs` matches
 * the chat-side `firstChunkQueued` line, so tap-to-sound can be read off one logcat; `atMs` counts from reply open.
 */
internal class KokoroReplyTiming(private val replyId: Long) {
    private val startMs = System.currentTimeMillis()

    fun mark(stage: String, detail: String = "") {
        val now = System.currentTimeMillis()
        val suffix = if (detail.isEmpty()) "" else " $detail"
        Log.i(TAG, "kokoro reply=$replyId stage=$stage atMs=${now - startMs} epochMs=$now$suffix")
    }

    /** Wraps [sink] so the track open is marked, separately from the playback-starting hook. */
    fun timed(sink: VoicePcmSink): VoicePcmSink = object : VoicePcmSink {
        override fun open(sampleRate: Int, pitch: Float): VoicePcmTrack {
            val track = sink.open(sampleRate, pitch)
            mark("trackOpened")
            return track
        }

        override fun silenceActive() = sink.silenceActive()
    }

    private companion object {
        const val TAG = "VoiceTiming"
    }
}
