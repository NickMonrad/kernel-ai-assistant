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

/** Plays one reply's waveforms. The controller implements it over AudioTrack; tests fake it. */
internal interface InflectPcmSink {
    /** Opens and starts a track for one reply. Must release anything it allocated before it throws. */
    fun open(pitch: Float): InflectPcmTrack

    /** Silences the track of the reply currently playing, if any. Called by the controller's stop(). */
    fun silenceActive()
}

internal interface InflectPcmTrack {
    fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean)
    fun drain(isCurrent: () -> Boolean)
    /** Fades out what is queued in the track and drops it; used when the reply is superseded or aborted. */
    fun discard()
    fun release()
}

/** Focus and speaking-event side effects that belong to the controller, not the reply stream. */
internal interface InflectReplyHooks {
    fun onPlaybackStarting(generation: Long, text: String)
    fun onPlaybackEnded(generation: Long)
}

/**
 * One reply's streaming pipeline. Text is normalized, split into speech chunks, phonemized and synthesized in order.
 * Waveforms go through a one-slot channel to a single track; playback starts with the first queued chunk.
 *
 * Failure contract: a speech chunk that fails to phonemize or synthesize is logged and skipped; the rest of the
 * append still plays. A preparation failure is reported on the final append only, so the Android TTS fallback speaks
 * the buffered text once. Once audio is queued, no append returns Unavailable, so the caller cannot restart a reply
 * that already played. [playbackResult] reports Unavailable only when playback failed before any audio was written.
 *
 * Cancellation contract: [cancel] (caller cancelled) finishes queued audio and synthesizes nothing more, like
 * Sherpa. [abort] (the controller's stop, which bumps the generation first) silences the reply at once.
 */
internal class InflectReplyStream(
    private val spokenText: String,
    private val generation: Long,
    private val isCurrent: () -> Boolean,
    private val unavailable: VoiceOutputResult.Unavailable?,
    /** Returns the raw waveform for one chunk, or null when the reply was superseded before synthesis ran. */
    private val synthesize: suspend (phonemes: String) -> FloatArray?,
    private val phonemizeChunk: (String) -> String,
    private val sink: InflectPcmSink,
    private val hooks: InflectReplyHooks,
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

    override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult = withContext(Dispatchers.IO) {
        appendText(text)
        if (!isFinal) return@withContext VoiceOutputResult.Spoken
        closeAudio()
        if (queuedChunks > 0 || !isCurrent()) return@withContext VoiceOutputResult.Spoken
        unavailable
            ?: VoiceOutputResult.Unavailable(lastSynthesisError ?: "Inflect normalization produced no synthesis chunks.")
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
        val normalized = try {
            InflectMicroTextFrontend.normalize(SpeechCardText.speakable(text))
        } catch (error: Exception) {
            skipChunk(error)
            return
        }
        if (normalized.isBlank()) return
        for (speechChunk in InflectMicroTextFrontend.speechChunks(normalized)) {
            if (closed.get()) return
            val pieces = try {
                InflectMicroTextFrontend.phonemizeChunks(speechChunk, phonemize = phonemizeChunk)
            } catch (error: Exception) {
                skipChunk(error)
                continue
            }
            for (piece in pieces) {
                if (closed.get()) return
                val waveform = try {
                    synthesize(piece.phonemes) ?: return
                } catch (error: CancellationException) {
                    // A superseded or aborted reply unwinds quietly; a cancelled caller propagates.
                    if (isCurrent()) throw error
                    return
                } catch (error: Exception) {
                    skipChunk(error)
                    continue
                }
                if (!enqueue(fadeChunkEdges(waveform))) return
                queuedChunks++
            }
        }
    }

    /** Logs one failed speech chunk; the reply continues with the next chunk. */
    private fun skipChunk(error: Exception) {
        Log.e(TAG, "Inflect chunk failed; skipping it and continuing the reply", error)
        lastSynthesisError = "Inflect synthesis failed: ${error.message}"
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
        var track: InflectPcmTrack? = null
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
            Log.e(TAG, "Inflect playback failed; the rest of this reply is dropped", error)
            if (!audioWritten) playbackFailure = VoiceOutputResult.Unavailable("Inflect playback failed: ${error.message}")
            track?.discard()
            audio.cancel()
        } finally {
            track?.release()
            hooks.onPlaybackEnded(generation)
        }
    }

    private fun openTrack(): InflectPcmTrack {
        hooks.onPlaybackStarting(generation, spokenText)
        return sink.open(pitch)
    }

    private companion object {
        const val TAG = "KernelAI"
    }
}
