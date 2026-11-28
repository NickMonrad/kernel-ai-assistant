package com.kernel.ai.core.voice.kokoro

import android.util.Log
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceOutputStreamingSession
import com.kernel.ai.core.voice.VoiceReplyStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One Kokoro reply as a streaming session (#2117). A chunk that fails after audio has queued hands the unspoken rest
 * of the reply to [speakRemainder] (Inflect Micro) once Kokoro's audio has ended, so no text is spoken twice. With
 * nothing queued the reply is Unavailable and the caller speaks all of it. Stop abandons the remainder. Logs one
 * summary line per reply.
 */
internal class KokoroReplySession(
    private val stream: VoiceReplyStream<KokoroUnit>,
    private val handoff: KokoroHandoff,
    private val timing: KokoroReplyTiming,
    private val voice: String,
    private val isCurrent: () -> Boolean,
    private val speakRemainder: (suspend (String) -> VoiceOutputResult)?,
    private val scope: CoroutineScope,
) : VoiceOutputStreamingSession {
    private val summaryLogged = AtomicBoolean(false)

    override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
        val result = stream.append(text, isFinal)
        return if (isFinal) finish(result) else result
    }

    override suspend fun cancel() {
        stream.cancel()
        logSummaryWhenPlayed(handoff.reason)
    }

    /** Stop path: silences the reply at once and abandons any remainder. */
    fun abort() {
        stream.abort()
        logSummaryWhenPlayed(handoff.reason)
    }

    /** Speak path: waits for Kokoro's audio to end. */
    suspend fun awaitPlayback() = stream.awaitPlayback()

    fun playbackResult(): VoiceOutputResult = stream.playbackResult()

    private suspend fun finish(result: VoiceOutputResult): VoiceOutputResult {
        if (result is VoiceOutputResult.Unavailable) {
            // Nothing queued: the caller speaks the whole reply, so there is no remainder to hand over.
            val reason = handoff.reason ?: result.message
            logSummaryWhenPlayed(reason)
            return if (handoff.active) VoiceOutputResult.Unavailable(reason) else result
        }
        if (!handoff.active) {
            logSummaryWhenPlayed(null)
            return result
        }
        // Kokoro's queued audio ends before the remainder starts, so the two never overlap.
        stream.awaitPlayback()
        val reason = checkNotNull(handoff.reason)
        if (!isCurrent()) {
            logSummary("stopped after: $reason")
            return VoiceOutputResult.Spoken
        }
        val speak = speakRemainder ?: run {
            logSummary(reason)
            return VoiceOutputResult.Unavailable(reason)
        }
        val remainder = handoff.remainder()
        Log.w(TAG, "Kokoro failed mid-reply; handing ${remainder.length} chars to Inflect Micro: $reason")
        logSummary(reason)
        return speak(remainder)
    }

    private fun logSummaryWhenPlayed(fallback: String?) {
        scope.launch {
            stream.awaitPlayback()
            logSummary(fallback)
        }
    }

    private fun logSummary(fallback: String?) {
        if (summaryLogged.compareAndSet(false, true)) timing.logSummary(voice, stream.playbackUnderruns, fallback)
    }

    private companion object {
        const val TAG = "KernelAI"
    }
}
