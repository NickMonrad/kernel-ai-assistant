package com.kernel.ai.feature.chat

import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceOutputStreamingSession
import com.kernel.ai.core.voice.VoiceSpeakRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val VOICE_FIRST_CHUNK_MIN_LENGTH = 40

/** Streamed first chunk ends at its first full stop from this length (#1892); shorter sentences merge forward. */
internal const val VOICE_STREAM_FIRST_CHUNK_MIN_LENGTH = 20
internal const val VOICE_MIN_CHUNK_LENGTH = 72
internal const val VOICE_MIN_FINAL_CHUNK_LENGTH = 20

/**
 * Speaks [text] through a streaming session, one sentence-sized chunk at a time, so the first
 * chunk starts playing while the next is synthesised. Slow local voices (Piper/Cori) otherwise
 * synthesise a whole multi-sentence reply before any sound plays.
 *
 * Returns once the chunks are queued, not when playback ends; callers that need to know when
 * speech finishes listen for [com.kernel.ai.core.voice.VoiceOutputEvent.SpeakingStopped].
 */
internal suspend fun speakInSentenceChunks(
    controller: VoiceOutputController,
    text: String,
): VoiceOutputResult {
    val session = controller.openStreamingSession(VoiceSpeakRequest(text = text))
    return appendSentenceChunks(session, text)
}

/**
 * Queues [text] on [session] as sentence-sized chunks; the last chunk is final. Shared by the
 * Actions/speaker-button path and the chat voice reply path so both use the same first-chunk and
 * tail rules.
 *
 * Splits at sentence ends only (never at a comma or word boundary), so a chunk is never cut mid-sentence.
 */
internal suspend fun appendSentenceChunks(
    session: VoiceOutputStreamingSession,
    text: String,
): VoiceOutputResult {
    try {
        val buffer = StringBuilder(text)
        var isFirst = true
        while (buffer.isNotBlank()) {
            // A short first chunk lets playback start while the rest is synthesised (#1872).
            val minLength = if (isFirst) VOICE_FIRST_CHUNK_MIN_LENGTH else VOICE_MIN_CHUNK_LENGTH
            var chunk = popNextStreamingSpeechChunk(
                buffer = buffer,
                minChunkLength = minLength,
                preferredChunkLength = Int.MAX_VALUE,
                force = buffer.length < minLength,
            ) ?: break
            // A tail under ~20 characters ("Thanks.") would be a separate, barely audible chunk;
            // fold it into this one. The whole reply is known here, so the tail is final.
            val tail = buffer.toString().trim()
            if (tail.isNotEmpty() && tail.length < VOICE_MIN_FINAL_CHUNK_LENGTH) {
                buffer.setLength(0)
                chunk = "$chunk ${finalizeChatTextForSpeech(tail)}"
            }
            isFirst = false
            val isLast = buffer.isBlank()
            val result = session.append(chunk, isFinal = isLast)
            if (result is VoiceOutputResult.Unavailable) {
                // Close the session so the engine finishes what it already queued and still
                // reports SpeakingStopped; otherwise listeners wait for a final chunk forever.
                if (!isLast) session.append("", isFinal = true)
                return result
            }
            if (isLast) return VoiceOutputResult.Spoken
        }
        // Residual text that didn't form a clean chunk boundary.
        return session.append(finalizeChatTextForSpeech(buffer.toString()), isFinal = true)
    } catch (e: CancellationException) {
        // Close the streaming session so the controller doesn't wait forever for a final
        // chunk (stuck Speaking state / unclosed chunk channel).
        withContext(NonCancellable) { runCatching { session.cancel() } }
        throw e
    }
}
