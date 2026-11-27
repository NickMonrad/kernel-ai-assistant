package com.kernel.ai.debug.tts.kokoro

import com.kernel.ai.core.executorch.ExecuTorchModel
import com.kernel.ai.core.executorch.ExecuTorchTensor
import com.kernel.ai.core.executorch.MethodSignature
import com.kernel.ai.core.executorch.TensorDType
import com.kernel.ai.core.executorch.TensorDim
import com.kernel.ai.core.executorch.TensorSpec
import com.kernel.ai.debug.tts.DebugTtsLane
import com.kernel.ai.debug.tts.sha256Of
import com.kernel.ai.debug.tts.DebugTtsLanes
import com.kernel.ai.debug.tts.LaneChunk
import com.kernel.ai.debug.tts.LaneUtterance
import java.io.File
import java.util.concurrent.CancellationException
import org.json.JSONObject

/**
 * Kokoro-82M on ExecuTorch (XNNPACK CPU, fp32). Debug-only lane for #1557.
 *
 * Runs the two shipped programs (duration predictor, then synthesizer) per chunk with the exact token length and
 * following the chunk loop in Software Mansition's `kokoroTextToSpeech.ts` (MIT). Phonemes come from
 * [phonemizer]. Stops before AudioTrack, so playback latency is excluded. Word timing is not computed.
 *
 * Cancellation is checked between chunks. A chunk already inside a native `run` finishes first, so the cancel
 * latency is up to one chunk.
 */
class KokoroEtDebugLane(
    private val modelDirectory: File,
    private val voiceName: String,
    private val phonemizer: KokoroPhonemizer,
    private val threads: Int = DEFAULT_THREADS,
) : DebugTtsLane {
    private var durationModel: ExecuTorchModel? = null
    private var synthesizerModel: ExecuTorchModel? = null
    private var voice: FloatArray? = null

    @Volatile
    private var cancelRequested = false

    override val laneId: String = DebugTtsLanes.KOKORO_ET

    override val backend: String
        get() = "executorch ${EXECUTORCH_VERSION} XNNPACK CPU, fp32, $threads threads"

    override fun initialize(): Long {
        check(durationModel == null && synthesizerModel == null) { "Kokoro ExecuTorch lane already initialized" }
        val startedAt = System.nanoTime()
        val voiceFile = File(modelDirectory, "voices/$voiceName.bin")
        check(voiceFile.isFile) { "missing voice ${voiceFile.path}" }
        voice = KokoroTts.parseVoice(voiceFile.readBytes())
        durationModel = ExecuTorchModel.load(File(modelDirectory, DURATION_FILE), threads)
        synthesizerModel = ExecuTorchModel.load(File(modelDirectory, SYNTH_FILE), threads)
        return elapsedMs(startedAt)
    }

    override fun synthesize(text: String, onChunkReady: (index: Int, elapsedMs: Long) -> Unit): LaneUtterance {
        val duration = checkNotNull(durationModel) { "Kokoro ExecuTorch lane not initialized" }
        val synthesizer = checkNotNull(synthesizerModel) { "Kokoro ExecuTorch lane not initialized" }
        require(text.isNotBlank()) { "Kokoro input text is blank" }
        val startedAt = System.nanoTime()
        val chunks = splitText(text)
        val frontendMs = elapsedMs(startedAt)

        val laneChunks = chunks.mapIndexed { index, chunk ->
            if (cancelRequested) throw CancellationException("Kokoro ExecuTorch synthesis cancelled")
            val chunkStarted = System.nanoTime()
            val audio = synthesizeChunk(duration, synthesizer, chunk, voice!!)
            val synthMs = elapsedMs(chunkStarted)
            val laneChunk = LaneChunk(
                index = index,
                phonemeChars = chunk.codePointCount(0, chunk.length),
                synthMs = synthMs,
                audioMs = audio.size * 1000.0 / KokoroTts.SAMPLE_RATE_HZ,
                peakAbs = audio.maxOfOrNull { kotlin.math.abs(it) } ?: 0f,
                finite = audio.all { it.isFinite() },
            )
            onChunkReady(index, elapsedMs(startedAt))
            laneChunk
        }
        return LaneUtterance(frontendMs = frontendMs, chunks = laneChunks)
    }

    /** Phonemizes [text] and partitions it into chunks within the duration model's token limit, in order. */
    fun splitText(text: String): List<String> {
        require(text.isNotBlank()) { "Kokoro input text is blank" }
        val phonemized = phonemizer.phonemize(text)
        val chunks = KokoroTts.partition(phonemized.phonemes, KokoroTts.MAX_TOKENS - 2)
        require(chunks.isNotEmpty()) { "Kokoro phonemization produced no chunks" }
        return chunks
    }

    /**
     * Synthesizes one chunk from [splitText] on the loaded models. Unlike [synthesize] it does not check
     * cancellation: the shared reply stream stops calling it once the reply is aborted, and a native run in
     * progress finishes first.
     */
    fun synthesizeChunkAudio(chunk: String): FloatArray {
        val duration = checkNotNull(durationModel) { "Kokoro ExecuTorch lane not initialized" }
        val synthesizer = checkNotNull(synthesizerModel) { "Kokoro ExecuTorch lane not initialized" }
        return synthesizeChunk(duration, synthesizer, chunk, checkNotNull(voice) { "Kokoro ExecuTorch lane not initialized" })
    }

    /** Port of `synthesizeChunkWorklet` in kokoroTextToSpeech.ts, minus word alignment. */
    private fun synthesizeChunk(
        duration: ExecuTorchModel,
        synthesizer: ExecuTorchModel,
        chunk: String,
        voice: FloatArray,
    ): FloatArray {
        // Exact length: L = phonemes + 2 real tokens (clamped to [MIN, MAX]), no padding. The mask is all true,
        // built with ExecuTorchTensor.Bool.allTrue (passed as BoolInputTensor). RNE's numTokens is L.
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

    override fun cancel() {
        cancelRequested = true
    }

    override fun resetCancellation() {
        cancelRequested = false
    }

    override fun metadata(): JSONObject {
        val models = JSONObject()
            .put("duration_predictor", File(modelDirectory, DURATION_FILE).absolutePath)
            .put("duration_predictor_sha256", sha256Of(File(modelDirectory, DURATION_FILE)))
            .put("synthesizer", File(modelDirectory, SYNTH_FILE).absolutePath)
            .put("synthesizer_sha256", sha256Of(File(modelDirectory, SYNTH_FILE)))
        return JSONObject()
            .put("executorch_version", EXECUTORCH_VERSION)
            .put("delegate", "XNNPACK")
            .put("precision", "fp32")
            .put("threads", threads)
            .put("available_processors", Runtime.getRuntime().availableProcessors())
            .put("voice", voiceName)
            .put("max_tokens", KokoroTts.MAX_TOKENS)
            .put("max_duration_ticks", KokoroTts.MAX_DURATION_TICKS)
            .put("speed", SPEED)
            .put("phonemizer", phonemizer.javaClass.simpleName)
            .put("models", models)
    }

    override fun close() {
        durationModel?.close()
        synthesizerModel?.close()
        durationModel = null
        synthesizerModel = null
        voice = null
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        const val EXECUTORCH_VERSION = "1.5.1"
        const val DEFAULT_THREADS = 4
        const val DURATION_FILE = "duration_predictor_std_xnnpack_fp32.pte"
        const val SYNTH_FILE = "synthesizer_std_xnnpack_fp32.pte"
        const val SPEED = 1.0f

        // Shipped software-mansion programs (ExecuTorch 1.4.x export, loads on 1.5.1). Dynamic token length up to
        // 128; the text mask is a BOOL tensor, all true at the real length.
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
                TensorSpec("durationFeatures", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.between(2, 128), TensorDim.exact(640)),
            ),
        )

        private val SYNTH_SIGNATURE = MethodSignature(
            method = "forward",
            inputs = listOf(
                TensorSpec("tokens", TensorDType.INT64, TensorDim.exact(1), TensorDim.between(2, 128)),
                TensorSpec("textMask", TensorDType.BOOL, TensorDim.exact(1), TensorDim.between(2, 128)),
                TensorSpec("indices", TensorDType.INT64, TensorDim.between(2, 296)),
                TensorSpec("durationFeatures", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.between(2, 128), TensorDim.exact(640)),
                TensorSpec("voiceRef", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.exact(256)),
            ),
            outputs = listOf(
                TensorSpec("audio", TensorDType.FLOAT32, TensorDim.exact(1), TensorDim.exact(1), TensorDim.between(1200, 177_600)),
            ),
        )
    }
}
