package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.executorch.ExecuTorchModel
import com.kernel.ai.core.executorch.ExecuTorchTensor
import com.kernel.ai.core.executorch.MethodSignature
import com.kernel.ai.core.executorch.TensorDType
import com.kernel.ai.core.executorch.TensorDim
import com.kernel.ai.core.executorch.TensorSpec
import java.io.Closeable
import java.io.File
import kotlin.math.abs

/**
 * Kokoro-82M on ExecuTorch (XNNPACK CPU, fp32) for one voice. Runs the duration predictor, then the synthesizer, per
 * chunk, at the chunk's exact token length. Port of `synthesizeChunkWorklet` in react-native-executorch's
 * `kokoroTextToSpeech.ts` (MIT), minus word alignment (#1557, #2117).
 *
 * Not thread-safe: the caller serialises [splitText] and [synthesizeChunk]. A native run cannot be interrupted, so
 * [close] must not run while a chunk is synthesizing.
 */
internal class KokoroEngine(
    private val durationFile: File,
    private val synthesizerFile: File,
    private val phonemizer: KokoroPhonemizer,
    private val threads: Int = DEFAULT_THREADS,
) : Closeable {
    private var durationModel: ExecuTorchModel? = null
    private var synthesizerModel: ExecuTorchModel? = null
    private var voice: FloatArray? = null

    /** Loads both programs and the voice embedding. Call once before [synthesizeChunk]. */
    fun initialize(voiceFile: File) {
        check(durationModel == null && synthesizerModel == null) { "Kokoro engine already initialized" }
        durationModel = ExecuTorchModel.load(durationFile, threads)
        synthesizerModel = ExecuTorchModel.load(synthesizerFile, threads)
        loadVoice(voiceFile)
    }

    /** Swaps the voice embedding. Programs and phonemiser stay loaded, so a same-locale voice switch skips the reload. */
    fun loadVoice(voiceFile: File) {
        voice = KokoroTts.parseVoice(voiceFile.readBytes())
    }

    /** Phonemizes [phonemizerInput] (already normalised text) and partitions it within the duration token limit. */
    fun splitText(phonemizerInput: String): List<String> {
        require(phonemizerInput.isNotBlank()) { "Kokoro input text is blank" }
        val phonemes = phonemizer.phonemize(phonemizerInput)
        val chunks = KokoroTts.partition(phonemes, KokoroTts.MAX_TOKENS - 2)
        require(chunks.isNotEmpty()) { "Kokoro phonemization produced no chunks" }
        return chunks
    }

    /** Waveform for one chunk from [splitText], 24 kHz mono, with the chunk's trailing pause appended. */
    fun synthesizeChunk(chunk: String): FloatArray {
        val duration = checkNotNull(durationModel) { "Kokoro engine not initialized" }
        val synthesizer = checkNotNull(synthesizerModel) { "Kokoro engine not initialized" }
        val voice = checkNotNull(voice) { "Kokoro engine not initialized" }
        return synthesizeChunk(duration, synthesizer, chunk, voice)
    }

    private fun synthesizeChunk(
        duration: ExecuTorchModel,
        synthesizer: ExecuTorchModel,
        chunk: String,
        voice: FloatArray,
    ): FloatArray {
        // Exact length: L = phonemes + 2 real tokens (clamped to [MIN, MAX]), no padding. The mask is all true.
        val phonemes = chunk.trim().codePoints().toArray()
        val numTokens = (phonemes.size + 2).coerceIn(KokoroTts.MIN_TOKENS, KokoroTts.MAX_TOKENS)
        val tokens = KokoroTts.tokenize(phonemes, numTokens)

        val voiceRows = voice.size / KokoroTts.VOICE_REF_SIZE
        val voiceRow = minOf(phonemes.size - 1, numTokens - 1, voiceRows - 1)
        val voiceOffset = maxOf(0, voiceRow) * KokoroTts.VOICE_REF_SIZE
        val voiceRef = voice.copyOfRange(voiceOffset, voiceOffset + KokoroTts.VOICE_REF_SIZE)
        val voiceRefHalf = voice.copyOfRange(
            voiceOffset + KokoroTts.VOICE_REF_HALF_SIZE,
            voiceOffset + KokoroTts.VOICE_REF_SIZE,
        )

        val tokensTensor = ExecuTorchTensor.Int64(tokens, longArrayOf(1, numTokens.toLong()))
        val maskTensor = ExecuTorchTensor.Bool.allTrue(longArrayOf(1, numTokens.toLong()))
        val durationOutputs = duration.run(
            DURATION_SIGNATURE,
            listOf(
                tokensTensor,
                maskTensor,
                ExecuTorchTensor.Float32(voiceRefHalf, longArrayOf(1, KokoroTts.VOICE_REF_HALF_SIZE.toLong())),
                ExecuTorchTensor.Float32(floatArrayOf(SPEED), longArrayOf(1)),
            ),
        )
        val predicted = (durationOutputs[0] as ExecuTorchTensor.Int64).data
        val features = durationOutputs[1] as ExecuTorchTensor.Float32
        val tokenDurations = IntArray(numTokens) { predicted[it].toInt() }
        val totalDuration = tokenDurations.sum()
        val clampedDuration = minOf(
            maxOf(totalDuration, KokoroTts.MIN_DURATION_TICKS),
            KokoroTts.MAX_DURATION_TICKS,
        )
        if (clampedDuration != totalDuration) KokoroTts.scaleDurations(tokenDurations, clampedDuration)

        // Index tensor length = sum of the scaled durations (<= 296). Audio beyond the effective duration is cut below.
        val indices = LongArray(tokenDurations.sum())
        var write = 0
        for (token in 0 until numTokens) {
            repeat(tokenDurations[token]) { indices[write++] = token.toLong() }
        }
        if (write == 0) return FloatArray(0)

        val synthOutputs = synthesizer.run(
            SYNTH_SIGNATURE,
            listOf(
                tokensTensor,
                maskTensor,
                ExecuTorchTensor.Int64(indices, longArrayOf(indices.size.toLong())),
                features,
                ExecuTorchTensor.Float32(voiceRef, longArrayOf(1, KokoroTts.VOICE_REF_SIZE.toLong())),
            ),
        )
        var audio = (synthOutputs[0] as ExecuTorchTensor.Float32).data

        // Post-processing, as in kokoroTextToSpeech.ts step 4.
        var padIndex = numTokens
        for (i in 1 until numTokens) {
            if (tokens[i] == 0L) {
                padIndex = i
                break
            }
        }
        var effectiveDuration = 0
        for (i in 0..minOf(padIndex, numTokens - 1)) effectiveDuration += tokenDurations[i]
        audio = audio.copyOf(minOf(effectiveDuration * KokoroTts.TICKS_PER_DURATION, audio.size))

        val lastPhoneme = phonemes.lastOrNull()?.let { String(Character.toChars(it)) } ?: ""
        if (numTokens > 2) {
            val lastTokenIndex = if (KokoroTts.isLetter(lastPhoneme)) numTokens - 2 else numTokens - 3
            var lastTimestamp = 0
            for (i in 0..lastTokenIndex) lastTimestamp += tokenDurations[i]
            audio = audio.copyOf(minOf(lastTimestamp * KokoroTts.TICKS_PER_DURATION, audio.size))
        }
        val stripped = KokoroTts.stripAudio(audio, KokoroTts.silencePaddingSamples)
        val pauseSamples = KokoroTts.pauseMs(lastPhoneme) * KokoroTts.SAMPLE_RATE_HZ / 1000
        return stripped.copyOf(stripped.size + pauseSamples)
    }

    override fun close() {
        durationModel?.close()
        synthesizerModel?.close()
        phonemizer.close()
        durationModel = null
        synthesizerModel = null
        voice = null
    }

    companion object {
        const val DEFAULT_THREADS = 4
        const val SPEED = 1.0f

        // Software Mansion's programs (ExecuTorch 1.4.x export, loads on 1.5.1). Dynamic token length up to 128;
        // the text mask is a BOOL tensor, all true at the real length.
        private val DURATION_SIGNATURE = MethodSignature(
            method = "forward",
            inputs = listOf(
                TensorSpec("tokens", TensorDType.INT64, TensorDim.exact(1), TensorDim.between(2, 128)),
                TensorSpec("textMask", TensorDType.BOOL, TensorDim.exact(1), TensorDim.between(2, 128)),
                TensorSpec("voiceRefHalf", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.exact(128)),
                TensorSpec("speed", TensorDType.FLOAT32, TensorDim.exact(1)),
            ),
            outputs = listOf(
                TensorSpec("predictedDurations", TensorDType.INT64, TensorDim.between(2, 128)),
                TensorSpec(
                    "durationFeatures",
                    TensorDType.FLOAT32,
                    TensorDim.exact(1),
                    TensorDim.between(2, 128),
                    TensorDim.exact(640),
                ),
            ),
        )

        private val SYNTH_SIGNATURE = MethodSignature(
            method = "forward",
            inputs = listOf(
                TensorSpec("tokens", TensorDType.INT64, TensorDim.exact(1), TensorDim.between(2, 128)),
                TensorSpec("textMask", TensorDType.BOOL, TensorDim.exact(1), TensorDim.between(2, 128)),
                TensorSpec("indices", TensorDType.INT64, TensorDim.between(2, 296)),
                TensorSpec(
                    "durationFeatures",
                    TensorDType.FLOAT32,
                    TensorDim.exact(1),
                    TensorDim.between(2, 128),
                    TensorDim.exact(640),
                ),
                TensorSpec("voiceRef", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.exact(256)),
            ),
            outputs = listOf(
                TensorSpec(
                    "audio",
                    TensorDType.FLOAT32,
                    TensorDim.exact(1),
                    TensorDim.exact(1),
                    TensorDim.between(1200, 177_600),
                ),
            ),
        )
    }
}
