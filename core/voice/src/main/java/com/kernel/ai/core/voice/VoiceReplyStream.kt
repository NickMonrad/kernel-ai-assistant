package com.kernel.ai.core.voice

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Plays one reply's waveforms. [AudioTrackVoicePcmSink] implements it over AudioTrack; tests and debug recorders fake it. */
interface VoicePcmSink {
    /** Opens and starts a track for one reply at [sampleRate]. Must release anything it allocated before it throws. */
    fun open(sampleRate: Int, pitch: Float): VoicePcmTrack

    /** Silences the track of the reply currently playing, if any. Called by the owning controller's stop(). */
    fun silenceActive()
}

interface VoicePcmTrack {
    fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean)
    fun drain(isCurrent: () -> Boolean)
    /** Fades out what is queued in the track and drops it; used when the reply is superseded or aborted. */
    fun discard()
    /** Hardware underruns (buffer starvation) since the track was opened; 0 when the track does not report them. */
    fun underrunCount(): Int = 0
    fun release()
}

/** Focus and speaking-event side effects that belong to the controller, not the reply stream. */
interface VoiceReplyHooks {
    fun onPlaybackStarting(generation: Long, text: String)
    fun onPlaybackEnded(generation: Long)
}

/**
 * One reply's streaming pipeline, shared by every engine. [chunker] splits the appended text into synthesis units
 * [T] lazily, in order; [synthesize] returns the waveform for one unit. Waveforms go through a one-slot channel to a
 * single track; playback starts with the first queued waveform.
 *
 * [engine] labels log lines and error messages. [sampleRate] is the rate of every waveform [synthesize] returns.
 *
 * Failure contract: a unit that cannot be chunked, phonemized or synthesized is reported to the chunker's `onSkip`
 * or logged and skipped; the rest of the append still plays. A preparation failure is reported on the final append
 * only, so the Android TTS fallback speaks the buffered text once. Once audio is queued, no append returns
 * Unavailable, so the caller cannot restart a reply that already played. [playbackResult] reports Unavailable only
 * when playback failed before any audio was written.
 *
 * Cancellation contract: [cancel] (caller cancelled) finishes queued audio and synthesizes nothing more, like
 * Sherpa. [abort] (the controller's stop, which bumps the generation first) silences the reply at once.
 */
class VoiceReplyStream<T>(
    private val engine: String,
    private val spokenText: String,
    private val generation: Long,
    private val isCurrent: () -> Boolean,
    private val unavailable: VoiceOutputResult.Unavailable?,
    /** Splits [text] into units in order. Calls [onSkip] for a part it cannot prepare and yields the rest. */
    private val chunker: (text: String, onSkip: (Exception) -> Unit) -> Sequence<T>,
    /** Returns the raw waveform for one unit, or null when the reply was superseded before synthesis ran. */
    private val synthesize: suspend (T) -> FloatArray?,
    private val sink: VoicePcmSink,
    private val sampleRate: Int,
    private val hooks: VoiceReplyHooks,
    private val gain: Float,
    private val pitch: Float,
    private val scope: CoroutineScope,
) : VoiceOutputStreamingSession {
    private val audio = Channel<FloatArray>(capacity = 1)
    private val closed = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)
    private val lock = Any()
    private var playback: Job? = null
    private var queuedChunks = 0
    @Volatile private var lastSynthesisError: String? = null
    @Volatile private var audioWritten = false
    @Volatile private var playbackFailure: VoiceOutputResult.Unavailable? = null
    @Volatile private var underruns = 0

    override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult = withContext(Dispatchers.IO) {
        appendText(text)
        if (!isFinal) return@withContext VoiceOutputResult.Spoken
        closeAudio()
        if (queuedChunks > 0 || !isCurrent()) return@withContext VoiceOutputResult.Spoken
        unavailable
            ?: VoiceOutputResult.Unavailable(lastSynthesisError ?: "$engine normalization produced no synthesis chunks.")
    }

    /** Caller-cancel path: finishes queued audio, synthesizes nothing more. */
    override suspend fun cancel() {
        closeAudio()
    }

    /** Stop path: cancels the channel so a blocked append or the playback loop unwinds at once. */
    fun abort() {
        aborted.set(true)
        closeAudio()
        audio.cancel()
    }

    /** Waits for playback if it was started; returns at once for a reply that never queued audio. */
    suspend fun awaitPlayback() {
        val job = synchronized(lock) { playback }
        job?.join()
    }

    /**
     * Speak-path result once playback has ended. Unavailable only when playback failed before any audio was
     * written, so the caller's Android TTS fallback cannot repeat audio that already played.
     */
    fun playbackResult(): VoiceOutputResult = playbackFailure ?: VoiceOutputResult.Spoken

    /** Hardware underruns of the reply's track, read once playback has ended; 0 when none played. */
    val playbackUnderruns: Int get() = underruns

    private fun closeAudio() {
        if (closed.compareAndSet(false, true)) audio.close()
    }

    private suspend fun appendText(text: String) {
        // A preparation failure stays silent here; the final append reports it (see append).
        if (unavailable != null || closed.get()) return
        if (!isCurrent()) {
            closeAudio()
            return
        }
        val units = chunker(text) { error -> skipChunk(error) }.iterator()
        while (!closed.get() && units.hasNext()) {
            val unit = units.next()
            if (closed.get()) return
            val waveform = try {
                synthesize(unit) ?: return
            } catch (error: CancellationException) {
                // A superseded or aborted reply unwinds quietly; a cancelled caller propagates.
                if (isCurrent()) throw error
                return
            } catch (error: Exception) {
                skipChunk(error)
                continue
            }
            if (!enqueue(finishChunkEdges(waveform, sampleRate))) return
            queuedChunks++
        }
    }

    /** Logs one failed unit; the reply continues with the next one. */
    private fun skipChunk(error: Exception) {
        Log.e(TAG, "$engine chunk failed; skipping it and continuing the reply", error)
        lastSynthesisError = "$engine synthesis failed: ${error.message}"
    }

    /** Sends one waveform and starts playback. False when the reply was aborted or its playback failed. */
    private suspend fun enqueue(waveform: FloatArray): Boolean {
        try {
            audio.send(waveform)
        } catch (error: ClosedSendChannelException) {
            return false
        } catch (error: CancellationException) {
            // The channel was cancelled by abort or a playback failure, not by the caller: the reply is closed.
            if (currentCoroutineContext().isActive) return false
            throw error
        }
        ensurePlayback()
        return true
    }

    private fun ensurePlayback() {
        synchronized(lock) {
            if (playback != null || aborted.get()) return
            playback = scope.launch { playAudio() }
        }
    }

    private suspend fun playAudio() {
        var track: VoicePcmTrack? = null
        try {
            for (waveform in audio) {
                if (!isCurrent()) break
                val active = track ?: openTrack().also { track = it }
                active.write(waveform, gain, isCurrent)
                audioWritten = true
            }
            track?.let { active ->
                if (isCurrent()) active.drain(isCurrent) else active.discard()
            }
        } catch (error: CancellationException) {
            track?.discard()
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "$engine playback failed; the rest of this reply is dropped", error)
            if (!audioWritten) playbackFailure = VoiceOutputResult.Unavailable("$engine playback failed: ${error.message}")
            track?.discard()
            audio.cancel()
        } finally {
            track?.let { active ->
                underruns = active.underrunCount()
                active.release()
            }
            hooks.onPlaybackEnded(generation)
        }
    }

    private fun openTrack(): VoicePcmTrack {
        hooks.onPlaybackStarting(generation, spokenText)
        return sink.open(sampleRate, pitch)
    }

    private companion object {
        const val TAG = "KernelAI"
    }
}

private const val CHUNK_EDGE_FADE_MS = 5
private const val CHUNK_TAIL_PAD_MS = 50

/**
 * Finishes one synthesized chunk for joining (#2096): appends [CHUNK_TAIL_PAD_MS] of silence, then fades the head
 * in over [CHUNK_EDGE_FADE_MS]. The tail is never faded: the model's last samples can be speech (a chunk that ends
 * without trailing silence), and fading them clipped the final phoneme decay. Returns a new array; the runner's
 * output stays unprocessed.
 */
internal fun finishChunkEdges(samples: FloatArray, sampleRate: Int): FloatArray {
    val padded = samples.copyOf(samples.size + sampleRate * CHUNK_TAIL_PAD_MS / 1000)
    val fadeFrames = minOf(padded.size / 2, sampleRate * CHUNK_EDGE_FADE_MS / 1000)
    for (index in 0 until fadeFrames) padded[index] *= index.toFloat() / fadeFrames
    return padded
}
