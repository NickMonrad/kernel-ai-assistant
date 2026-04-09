package com.kernel.ai.core.inference

import android.content.Context
import android.os.Build
import android.util.Log
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.isDownloaded
import com.kernel.ai.core.inference.download.localFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EmbeddingEngine backed by EmbeddingGemma-300M running via the TFLite Interpreter.
 *
 * Model selection priority:
 *   1. SM8550-optimised model (Snapdragon 8 Gen 2 / S23 Ultra) when [Build.BOARD] == "kalama"
 *   2. Generic mixed-precision model (GPU delegate, all devices)
 *
 * Tokenisation: pure-Kotlin [SentencePieceTokenizer] parsing the bundled `sentencepiece.model`.
 *
 * Returns [FloatArray] of length 0 gracefully while models are not yet on-device.
 */
@Singleton
class LiteRtEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : EmbeddingEngine {

    private data class State(
        val interpreter: Interpreter,
        val tokenizer: SentencePieceTokenizer,
        val gpuDelegate: GpuDelegate?,
        val seqLen: Int,
        override val dimensions: Int,
    ) : EmbeddingEngine {
        override suspend fun embed(text: String) = throw UnsupportedOperationException()
        override fun close() = throw UnsupportedOperationException()
    }

    private var _state: State? = null
    private val lock = Any()

    override val dimensions: Int get() = synchronized(lock) { _state?.dimensions ?: 0 }

    private fun ensureState(): State? {
        synchronized(lock) {
            _state?.let { return it }

            val modelFile = selectModelFile() ?: return null
            val spFile = KernelModel.EMBEDDING_GEMMA_SP_MODEL.localFile(context)
            if (!spFile.exists()) {
                Log.w(TAG, "sentencepiece.model not found at ${spFile.absolutePath}")
                return null
            }

            return try {
                val tokenizer = SentencePieceTokenizer(spFile)

                val gpuDelegate = try {
                    GpuDelegate()
                } catch (e: Throwable) {
                    Log.w(TAG, "GPU delegate unavailable — using CPU", e)
                    null
                }

                val options = Interpreter.Options().apply {
                    gpuDelegate?.let { addDelegate(it) }
                    numThreads = 4
                }

                val buffer = mapModelFile(modelFile)
                val interpreter = Interpreter(buffer, options)

                // Read actual sequence length and embedding dimension from the model.
                val inputShape = interpreter.getInputTensor(0).shape()   // [1, seqLen]
                val outputShape = interpreter.getOutputTensor(0).shape() // [1, dim] or [1, seqLen, dim]
                val seqLen = inputShape.getOrElse(1) { 512 }
                val dim = outputShape.last()

                Log.i(TAG, "EmbeddingGemma ready: model=${modelFile.name}, dim=$dim, seq=$seqLen")
                State(interpreter, tokenizer, gpuDelegate, seqLen, dim).also { _state = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialise EmbeddingGemma", e)
                null
            }
        }
    }

    /** Prefer SM8550 hardware model on matching device, fall back to generic. */
    private fun selectModelFile(): File? {
        if (isSm8550()) {
            val sm8550 = KernelModel.EMBEDDING_GEMMA_300M_SM8550.localFile(context)
            if (sm8550.exists()) {
                Log.i(TAG, "Using SM8550-optimised EmbeddingGemma model")
                return sm8550
            }
        }
        val generic = KernelModel.EMBEDDING_GEMMA_300M.localFile(context)
        if (generic.exists()) return generic

        Log.w(TAG, "EmbeddingGemma model files not found — embeddings unavailable")
        return null
    }

    /**
     * Returns true when running on Snapdragon 8 Gen 2 (SM8550).
     * Board code: "kalama" (Qualcomm's internal name for SM8550).
     */
    private fun isSm8550(): Boolean = Build.BOARD.equals("kalama", ignoreCase = true)

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val state = ensureState() ?: return@withContext FloatArray(0)

        synchronized(lock) {
            val tokenIds = state.tokenizer.encode(text, maxLen = state.seqLen)
            val seqLen = state.seqLen

            // Input 0: token IDs padded to seqLen
            val inputIds = Array(1) { IntArray(seqLen) { i -> tokenIds.getOrElse(i) { 0 } } }
            // Input 1 (optional): attention mask — 1 for real tokens, 0 for padding
            val mask = Array(1) { IntArray(seqLen) { i -> if (i < tokenIds.size) 1 else 0 } }

            // Output: embedding vector [1, dim]
            val output = Array(1) { FloatArray(state.dimensions) }

            if (state.interpreter.inputTensorCount >= 2) {
                state.interpreter.runForMultipleInputsOutputs(
                    arrayOf<Any>(inputIds, mask),
                    mapOf(0 to output),
                )
            } else {
                state.interpreter.run(inputIds, output)
            }

            output[0]
        }
    }

    override fun close() {
        synchronized(lock) {
            _state?.interpreter?.close()
            _state?.gpuDelegate?.close()
            _state = null
        }
    }

    private fun mapModelFile(file: File): MappedByteBuffer =
        FileInputStream(file).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }

    companion object {
        private const val TAG = "LiteRtEmbeddingEngine"

        /** Board codes for other supported Qualcomm chipsets (for future hardware detection). */
        @Suppress("unused")
        val BOARD_SM8650 = "pineapple"  // Snapdragon 8 Gen 3 / S24 Ultra
        @Suppress("unused")
        val BOARD_SM8750 = "sun"        // Snapdragon 8 Elite / S25 Ultra
    }
}
