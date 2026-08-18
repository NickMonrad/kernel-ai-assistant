package com.kernel.ai.core.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct ONNX Runtime probe for the official Inflect Micro v2 export.
 *
 * This is deliberately not a [VoiceOutputController]. It proves the two official graphs can be
 * executed with Jandal's existing ONNX Runtime before any text frontend or playback integration is
 * attempted. The caller supplies precomputed phoneme symbols for this isolation step.
 */
class InflectMicroOnnxRunner(
    modelDirectory: java.io.File,
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : Closeable {

    private val durationSession: OrtSession
    private val decodeSession: OrtSession
    private val cancelled = AtomicBoolean(false)

    val durationInputNames: List<String>
    val durationOutputNames: List<String>
    val decodeInputNames: List<String>
    val decodeOutputNames: List<String>
    val durationInputInfo: Map<String, String>
    val durationOutputInfo: Map<String, String>
    val decodeInputInfo: Map<String, String>
    val decodeOutputInfo: Map<String, String>

    init {
        val duration = java.io.File(modelDirectory, DURATION_MODEL_FILE)
        val decode = java.io.File(modelDirectory, DECODE_MODEL_FILE)
        require(duration.isFile) { "Missing $DURATION_MODEL_FILE in ${modelDirectory.path}" }
        require(decode.isFile) { "Missing $DECODE_MODEL_FILE in ${modelDirectory.path}" }

        durationSession = environment.createSession(duration.readBytes(), OrtSession.SessionOptions())
        try {
            decodeSession = environment.createSession(decode.readBytes(), OrtSession.SessionOptions())
        } catch (error: Throwable) {
            durationSession.close()
            throw error
        }

        durationInputNames = durationSession.inputNames.toList()
        durationOutputNames = durationSession.outputNames.toList()
        decodeInputNames = decodeSession.inputNames.toList()
        decodeOutputNames = decodeSession.outputNames.toList()
        durationInputInfo = durationSession.inputInfo.mapValues { it.value.info.toString() }
        durationOutputInfo = durationSession.outputInfo.mapValues { it.value.info.toString() }
        decodeInputInfo = decodeSession.inputInfo.mapValues { it.value.info.toString() }
        decodeOutputInfo = decodeSession.outputInfo.mapValues { it.value.info.toString() }
    }

    /**
     * Runs duration then decode using the exact tensor names and sequence from the official
     * `inference_onnx.py` wrapper. No gain, clipping, resampling, or playback processing is applied.
     */
    fun synthesize(
        phonemeText: String,
        speed: Float = 1.0f,
        variation: Float = 0.667f,
        seed: Long = 0L,
    ): InflectOnnxSynthesis {
        require(speed in 0.5f..2.0f) { "speed must be between 0.5 and 2.0" }
        require(variation in 0.0f..1.0f) { "variation must be between 0.0 and 1.0" }
        check(!cancelled.get()) { "Inflect synthesis cancelled" }

        val tokenIds = phonemesToTokenIds(phonemeText)
        val startedAt = System.nanoTime()
        val durationOutputs: InflectDurationOutputs
        val tokenTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(tokenIds),
            longArrayOf(1L, tokenIds.size.toLong()),
        )
        val lengthsTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(tokenIds.size.toLong())),
            longArrayOf(1L),
        )
        val lengthScaleTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(floatArrayOf(1.0f / speed)),
            longArrayOf(1L),
        )
        try {
            val result = durationSession.run(
                mapOf(
                    "tokens" to tokenTensor,
                    "lengths" to lengthsTensor,
                    "length_scale" to lengthScaleTensor,
                ),
                setOf("m_p_exp", "logs_p_exp", "y_mask"),
            )
            try {
                durationOutputs = InflectDurationOutputs(
                    mean = copyTensor(result, "m_p_exp"),
                    logScale = copyTensor(result, "logs_p_exp"),
                    mask = copyTensor(result, "y_mask"),
                )
            } finally {
                result.close()
            }
        } finally {
            tokenTensor.close()
            lengthsTensor.close()
            lengthScaleTensor.close()
        }

        check(!cancelled.get()) { "Inflect synthesis cancelled after duration inference" }
        val latentNoise = FloatArray(durationOutputs.mean.values.size)
        val random = Random(seed)
        for (index in latentNoise.indices) latentNoise[index] = random.nextGaussian().toFloat()
        val meanTensor = tensorFrom(durationOutputs.mean)
        val logScaleTensor = tensorFrom(durationOutputs.logScale)
        val maskTensor = tensorFrom(durationOutputs.mask)
        val noiseTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(latentNoise),
            durationOutputs.mean.shape,
        )
        val noiseScaleTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(floatArrayOf(variation)),
            longArrayOf(1L),
        )
        val waveform: InflectTensorData
        try {
            val result = decodeSession.run(
                mapOf(
                    "m_p_exp" to meanTensor,
                    "logs_p_exp" to logScaleTensor,
                    "y_mask" to maskTensor,
                    "zp_noise" to noiseTensor,
                    "noise_scale" to noiseScaleTensor,
                ),
                setOf("waveform"),
            )
            try {
                waveform = copyTensor(result, "waveform")
            } finally {
                result.close()
            }
        } finally {
            meanTensor.close()
            logScaleTensor.close()
            maskTensor.close()
            noiseTensor.close()
            noiseScaleTensor.close()
        }

        check(!cancelled.get()) { "Inflect synthesis cancelled after decode inference" }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        return InflectOnnxSynthesis(
            phonemeText = phonemeText,
            tokenCount = tokenIds.size,
            durationMeanShape = durationOutputs.mean.shape,
            durationMaskShape = durationOutputs.mask.shape,
            waveform = waveform.values,
            waveformShape = waveform.shape,
            synthesisMs = elapsedMs,
        )
    }

    fun cancel() {
        cancelled.set(true)
    }

    override fun close() {
        durationSession.close()
        decodeSession.close()
    }

    private fun tensorFrom(data: InflectTensorData): OnnxTensor = OnnxTensor.createTensor(
        environment,
        FloatBuffer.wrap(data.values),
        data.shape,
    )

    private fun copyTensor(result: OrtSession.Result, name: String): InflectTensorData {
        val tensor = result.get(name).get() as OnnxTensor
        val shape = tensor.info.shape
        val values = flattenFloats(tensor.value, product(shape))
        return InflectTensorData(shape, values)
    }

    private fun flattenFloats(value: Any?, expectedSize: Int): FloatArray {
        val output = FloatArray(expectedSize)
        var offset = 0

        fun visit(item: Any?) {
            when (item) {
                is FloatArray -> {
                    item.copyInto(output, destinationOffset = offset)
                    offset += item.size
                }
                is Array<*> -> item.forEach(::visit)
                is Number -> output[offset++] = item.toFloat()
                null -> error("ONNX tensor contained null data")
                else -> error("Unsupported ONNX tensor value ${item::class.java.name}")
            }
        }

        visit(value)
        check(offset == expectedSize) {
            "ONNX tensor size mismatch: expected $expectedSize values, copied $offset"
        }
        return output
    }

    companion object {
        const val SAMPLE_RATE_HZ = 24_000
        const val DURATION_MODEL_FILE = "duration.onnx"
        const val DECODE_MODEL_FILE = "decode.onnx"

        // The official runtime/text/symbols.py list, in order. A blank is inserted between symbols
        // exactly as in the upstream `phonemes_to_tokens` helper. This is not a text frontend.
        private const val SYMBOLS =
            "_" +
                ";:,.!?¡¿—…\"«»“” " +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩ᵻ"

        /** Converts already-phonemised IPA text into Inflect's blank-interleaved token IDs. */
        fun phonemesToTokenIds(phonemeText: String): LongArray {
            require(phonemeText.isNotEmpty()) { "Phoneme text must not be empty" }
            val symbolIds = HashMap<Char, Int>(SYMBOLS.length)
            SYMBOLS.forEachIndexed { index, symbol -> symbolIds.putIfAbsent(symbol, index) }
            val ids = LongArray(phonemeText.length * 2 + 1)
            phonemeText.forEachIndexed { index, symbol ->
                val id = symbolIds[symbol]
                    ?: throw IllegalArgumentException("Unsupported Inflect symbol U+${symbol.code.toString(16)}")
                ids[index * 2 + 1] = id.toLong()
            }
            return ids
        }

        private fun product(shape: LongArray): Int = shape.fold(1L) { total, dimension ->
            total * dimension
        }.also { require(it <= Int.MAX_VALUE) }.toInt()
    }
}

data class InflectOnnxSynthesis(
    val phonemeText: String,
    val tokenCount: Int,
    val durationMeanShape: LongArray,
    val durationMaskShape: LongArray,
    val waveform: FloatArray,
    val waveformShape: LongArray,
    val synthesisMs: Long,
) {
    val audioDurationMs: Long
        get() = waveform.size.toLong() * 1_000L / InflectMicroOnnxRunner.SAMPLE_RATE_HZ

    val peakAbs: Float
        get() = waveform.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0f

    val finite: Boolean
        get() = waveform.all(Float::isFinite)
}

private data class InflectDurationOutputs(
    val mean: InflectTensorData,
    val logScale: InflectTensorData,
    val mask: InflectTensorData,
)

private data class InflectTensorData(
    val shape: LongArray,
    val values: FloatArray,
)
