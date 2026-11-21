package com.kernel.ai.feature.chat

import android.util.Log
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceOutputStreamingSession
import com.kernel.ai.core.voice.VoiceSpeakRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Synthesises the first chunk of a voice reply while it is still streaming (#1923), so that playback can start as
 * soon as the reply is confirmed. The chunk is held unplayed until [take] hands it to the confirmed reply, which
 * releases it. One hold per turn: a tool call, a retry, a stop or a new turn discards it before anything plays.
 */
internal class VoicePresynthesis(
    private val controller: VoiceOutputController,
    private val scope: CoroutineScope,
) {
    private class Hold(val chunk: String) {
        var session: VoiceOutputStreamingSession? = null
        var opening: Job? = null
        var discarded = false

        /** True once [chunk] is queued in [session]; a hold that failed to queue it is never played. */
        var queued = false
    }

    private var hold: Hold? = null
    private var attempted = false
    private var disabled = false

    /** Starts a new turn. A hold from an earlier turn is discarded, and this turn may hold once. */
    fun startTurn() {
        discard()
        attempted = false
        disabled = false
    }

    /**
     * Called with the reply text streamed so far. Starts the one hold of the turn once the first chunk is decided.
     * A tool call, or a reply containing a raw special-token marker, ends holding for the turn.
     */
    fun onStreamedText(text: String, eligible: Boolean, toolCalled: Boolean) {
        if (disabled || attempted) return
        if (toolCalled || text.contains("<|")) {
            disableForTurn()
            return
        }
        if (!eligible) return
        val chunk = firstStreamedSpeechChunk(text) ?: return
        attempted = true
        val started = Hold(chunk)
        hold = started
        started.opening = scope.launch {
            try {
                val session = controller.openPreSynthesisedSession(VoiceSpeakRequest(text = "")) ?: return@launch
                if (started.discarded) {
                    session.cancel()
                    return@launch
                }
                started.session = session
                started.queued = session.append(chunk, isFinal = false) !is VoiceOutputResult.Unavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pre-synthesis failed; the reply is spoken on confirmation", e)
            }
        }
    }

    /** The reply will be generated again (a retry pass), so the streamed first sentence no longer matches what is spoken. */
    fun retry() = disableForTurn()

    /** Drops the hold: the reply is not spoken as streamed (a tool call, a stop, or a new turn). */
    fun discard() {
        val dropped = hold ?: return
        hold = null
        dropped.discarded = true
        val session = dropped.session ?: return
        scope.launch { runCatching { session.cancel() } }
    }

    /**
     * Gives the turn's hold to the confirmed reply once it has finished opening. Null when there is none, or when
     * it cannot play. The caller owns the returned session: it must release or cancel it.
     */
    suspend fun take(): PresynthesisedSpeech? {
        val taken = hold ?: return null
        hold = null
        try {
            taken.opening?.join()
        } catch (e: CancellationException) {
            taken.discarded = true
            withContext(NonCancellable) { runCatching { taken.session?.cancel() } }
            throw e
        }
        val session = taken.session ?: return null
        if (taken.discarded) return null
        if (!taken.queued) {
            session.cancel()
            return null
        }
        return PresynthesisedSpeech(session, taken.chunk)
    }

    private fun disableForTurn() {
        discard()
        attempted = true
        disabled = true
    }

    private companion object {
        const val TAG = "VoicePresynthesis"
    }
}
