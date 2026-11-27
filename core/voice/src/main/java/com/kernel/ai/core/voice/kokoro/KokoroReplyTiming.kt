package com.kernel.ai.core.voice.kokoro

import android.util.Log
import com.kernel.ai.core.voice.VoicePcmSink
import com.kernel.ai.core.voice.VoicePcmTrack
import java.util.Locale

/**
 * Timings for one Kokoro reply (#2117). Stage lines are `Log.d`; [logSummary] writes the one `Log.i` line per reply.
 * All lines use the `VoiceTiming` tag. `epochMs` matches the chat-side `firstChunkQueued` line, so tap-to-sound can be
 * read off one logcat; `atMs` counts from reply open.
 */
internal class KokoroReplyTiming(private val replyId: Long) {
    private val startMs = System.currentTimeMillis()
    private val rtfs = mutableListOf<Double>()
    private var firstSoundAtMs: Long? = null

    fun mark(stage: String, detail: String = "") {
        val now = System.currentTimeMillis()
        val suffix = if (detail.isEmpty()) "" else " $detail"
        Log.d(TAG, "kokoro reply=$replyId stage=$stage atMs=${now - startMs} epochMs=$now$suffix")
    }

    /** Playback started: records time to first sound once per reply. */
    @Synchronized
    fun playbackStarting() {
        if (firstSoundAtMs == null) firstSoundAtMs = System.currentTimeMillis() - startMs
        mark("playbackStarting")
    }

    @Synchronized
    fun recordRtf(rtf: Double) {
        rtfs += rtf
    }

    /**
     * The reply's one `Log.i` line: engine, voice, time to first sound, mean synthesis RTF, hardware underruns and the
     * fallback reason (null: none). Called once per reply, after its playback has ended.
     */
    fun logSummary(voice: String, underruns: Int, fallback: String?) {
        val (firstSound, meanRtf) = synchronized(this) {
            firstSoundAtMs to rtfs.takeIf { it.isNotEmpty() }?.average()
        }
        val rtfText = meanRtf?.let { "%.2f".format(Locale.ROOT, it) } ?: "none"
        Log.i(
            TAG,
            "kokoro reply=$replyId summary engine=Kokoro voice=$voice timeToFirstSoundMs=${firstSound ?: "none"} " +
                "meanRtf=$rtfText underruns=$underruns fallback=${fallback ?: "none"}",
        )
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
