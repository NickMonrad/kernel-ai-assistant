package com.kernel.ai.feature.chat

import android.util.Log
import com.kernel.ai.core.skills.SpeechPause
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceSpeakRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val VOICE_FIRST_CHUNK_MIN_LENGTH = 40

internal const val VOICE_MIN_CHUNK_LENGTH = 72
internal const val VOICE_MIN_FINAL_CHUNK_LENGTH = 20

/**
 * Minimum length of the first chunk after a [SpeechPause]. The part before the pause is usually one short
 * sentence, so the next chunk is kept to about one sentence too: it is synthesised while the short part and
 * the pause play, and a longer chunk would not be ready in time (#1942).
 */
internal const val VOICE_AFTER_PAUSE_CHUNK_MIN_LENGTH = VOICE_MIN_FINAL_CHUNK_LENGTH

internal const val VOICE_TIMING_TAG = "VoiceTiming"

internal fun logFirstChunkQueued(chars: Int) {
    if (BuildConfig.DEBUG) Log.d(VOICE_TIMING_TAG, "firstChunkQueued epochMs=${System.currentTimeMillis()} chars=$chars")
}

/**
 * Entry point for the debug build's speech probe (`SpeechProbeReceiver`, #1942): speaks [text] through the
 * production chunked path. Not for app code, which calls [speakInSentenceChunks] inside this module.
 */
suspend fun speakForDebugSpeechProbe(controller: VoiceOutputController, text: String): VoiceOutputResult =
    speakInSentenceChunks(controller, text)

/**
 * Speaks [text] through a streaming session, one sentence-sized chunk at a time, so the first
 * chunk starts playing while the next is synthesised. Slow local voices (Piper/Cori) otherwise
 * synthesise a whole multi-sentence reply before any sound plays.
 *
 * The text is normalised before chunking, so a list marker such as "3." that a newline left behind
 * does not end a chunk. Returns once the chunks are queued, not when playback ends; callers that
 * need to know when speech finishes listen for [com.kernel.ai.core.voice.VoiceOutputEvent.SpeakingStopped].
 *
 * Splits at sentence ends only (never at a comma or word boundary), so a chunk is never cut mid-sentence.
 * A short first chunk lets playback start while the rest is synthesised (#1872). A tail under ~20
 * characters ("Thanks.") would be a separate, barely audible chunk, so it is folded into the previous chunk.
 * A [SpeechPause.MARKER] always ends a chunk and becomes [SpeechPause.DURATION_MS] of silence.
 */
internal suspend fun speakInSentenceChunks(
    controller: VoiceOutputController,
    text: String,
): VoiceOutputResult {
    val segments = text.split(SpeechPause.MARKER).map(::normalizeChatTextForSpeech).filter { it.isNotBlank() }
    val session = controller.openStreamingSession(VoiceSpeakRequest(text = segments.joinToString(" ")))
    try {
        var isFirst = true
        for ((index, segment) in segments.withIndex()) {
            val isLastSegment = index == segments.lastIndex
            val buffer = StringBuilder(segment)
            var isFirstInSegment = true
            while (buffer.isNotBlank()) {
                val minLength = when {
                    isFirst -> VOICE_FIRST_CHUNK_MIN_LENGTH
                    isFirstInSegment -> VOICE_AFTER_PAUSE_CHUNK_MIN_LENGTH
                    else -> VOICE_MIN_CHUNK_LENGTH
                }
                var chunk = popNextStreamingSpeechChunk(
                    buffer = buffer,
                    minChunkLength = minLength,
                    preferredChunkLength = Int.MAX_VALUE,
                    force = buffer.length < minLength,
                ) ?: break
                // The whole segment is known here, so a short tail is final and is folded into this chunk.
                val tail = buffer.toString().trim()
                if (tail.isNotEmpty() && tail.length < VOICE_MIN_FINAL_CHUNK_LENGTH) {
                    buffer.setLength(0)
                    chunk = "$chunk ${finalizeChatTextForSpeech(tail)}"
                }
                if (isFirst) logFirstChunkQueued(chunk.length)
                isFirst = false
                isFirstInSegment = false
                val isLast = isLastSegment && buffer.isBlank()
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
            if (buffer.isNotBlank()) {
                if (isFirst) logFirstChunkQueued(buffer.length)
                isFirst = false
                val residual = finalizeChatTextForSpeech(buffer.toString())
                if (isLastSegment) return session.append(residual, isFinal = true)
                val result = session.append(residual, isFinal = false)
                if (result is VoiceOutputResult.Unavailable) {
                    session.append("", isFinal = true)
                    return result
                }
            }
            if (!isLastSegment) session.appendPause(SpeechPause.DURATION_MS)
        }
        // Nothing to say: close the session so it still reports SpeakingStopped.
        if (isFirst) logFirstChunkQueued(0)
        return session.append(finalizeChatTextForSpeech(""), isFinal = true)
    } catch (e: CancellationException) {
        // Close the streaming session so the controller doesn't wait forever for a final
        // chunk (stuck Speaking state / unclosed chunk channel).
        withContext(NonCancellable) { runCatching { session.cancel() } }
        throw e
    }
}
