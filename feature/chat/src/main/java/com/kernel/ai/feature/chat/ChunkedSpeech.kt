package com.kernel.ai.feature.chat

import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceSpeakRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val VOICE_MIN_CHUNK_LENGTH = 72
internal const val VOICE_PREFERRED_CHUNK_LENGTH = 180

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
    try {
        val buffer = StringBuilder(text)
        while (buffer.isNotBlank()) {
            val chunk = popNextStreamingSpeechChunk(
                buffer = buffer,
                minChunkLength = VOICE_MIN_CHUNK_LENGTH,
                preferredChunkLength = VOICE_PREFERRED_CHUNK_LENGTH,
                force = buffer.length < VOICE_MIN_CHUNK_LENGTH,
            ) ?: break
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
