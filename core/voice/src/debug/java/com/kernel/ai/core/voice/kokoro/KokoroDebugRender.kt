package com.kernel.ai.core.voice.kokoro

import android.content.Context
import com.kernel.ai.core.voice.PronunciationLexicon
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoicePcmSink
import com.kernel.ai.core.voice.VoiceReplyHooks
import com.kernel.ai.core.voice.VoiceReplyStream
import kotlinx.coroutines.coroutineScope

/**
 * Debug-only (#2175): renders [text] through Kokoro's text path (pronunciation lexicon, phonemiser input, OOV
 * fallback, chunk split, synthesis) into [sink] for device listening evidence. No audio focus, no handoff to
 * Inflect and no settings reads: the caller supplies [gain] and [pitch]. The app's reply path does not use this.
 */
object KokoroDebugRender {
    const val SAMPLE_RATE_HZ = KokoroTts.SAMPLE_RATE_HZ

    /** Returns the phoneme chunks in order. Throws when the models are missing or the reply fails. */
    suspend fun render(
        context: Context,
        voice: KokoroVoice,
        text: String,
        sink: VoicePcmSink,
        gain: Float,
        pitch: Float,
    ): List<String> {
        KokoroModelFiles.firstMissing(context, voice)?.let { missing ->
            error("Kokoro model missing: ${missing.displayName}")
        }
        PronunciationLexicon.ensureLoaded(context)
        val phonemizer = NativeKokoroPhonemizer(
            locale = voice.locale,
            lexiconFile = KokoroModelFiles.lexiconFile(context, voice),
            taggerFile = KokoroModelFiles.tagsFile(context),
        )
        val engine = KokoroEngine(
            durationFile = KokoroModelFiles.durationFile(context),
            synthesizerFile = KokoroModelFiles.synthesizerFile(context),
            phonemizer = phonemizer,
        )
        try {
            engine.initialize(KokoroModelFiles.voiceFile(context, voice))
            val chunks = mutableListOf<String>()
            coroutineScope {
                val stream = VoiceReplyStream(
                    engine = "Kokoro",
                    spokenText = text,
                    generation = 0L,
                    isCurrent = { true },
                    unavailable = null,
                    chunker = { raw, _ ->
                        val split = engine.splitText(KokoroTextFrontend.phonemizerInput(raw, voice.locale))
                        chunks += split
                        split.asSequence()
                    },
                    synthesize = { chunk -> engine.synthesizeChunk(chunk) },
                    sink = sink,
                    sampleRate = SAMPLE_RATE_HZ,
                    hooks = NoHooks,
                    gain = gain,
                    pitch = pitch,
                    scope = this,
                )
                val appended = stream.append(text, isFinal = true)
                stream.awaitPlayback()
                check(appended !is VoiceOutputResult.Unavailable) { "Kokoro reply unavailable: $appended" }
                check(stream.playbackResult() !is VoiceOutputResult.Unavailable) {
                    "Kokoro playback failed: ${stream.playbackResult()}"
                }
            }
            return chunks
        } finally {
            engine.close()
            phonemizer.close()
        }
    }

    private object NoHooks : VoiceReplyHooks {
        override fun onPlaybackStarting(generation: Long, text: String) = Unit
        override fun onPlaybackEnded(generation: Long) = Unit
    }
}
