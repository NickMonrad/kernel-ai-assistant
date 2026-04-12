package com.kernel.ai.core.inference

import android.content.Context
import android.util.Log
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.isDownloaded
import com.kernel.ai.core.inference.download.localFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EmbeddingEngine backed by EmbeddingGemma-300M running via the TFLite Interpreter.
 *
 * Model selection: generic mixed-precision model (GPU delegate, all devices).
 * The SM8550-optimised variant was disabled due to LiteRT format incompatibility
 * (DISPATCH_OP custom op requires Qualcomm AI Engine delegate which is not bundled).
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
        val seqLen: Int,
        override val dimensions: Int,
    ) : EmbeddingEngine {
        override suspend fun embed(text: String) = throw UnsupportedOperationException()
        override fun close() = throw UnsupportedOperationException()
    }

    private var _state: State? = null
    private val lock = Any()

    init {
        // Clean up the SM8550 variant — disabled due to LiteRT format incompatibility.
        // Remove this once the upstream issue is resolved and the model is re-enabled.
        KernelModel.EMBEDDING_GEMMA_300M_SM8550.localFile(context).let { f ->
            if (f.exists()) {
                f.delete()
                Log.i(TAG, "Deleted broken SM8550 embedding variant: ${f.name}")
            }
        }
    }

    override val dimensions: Int get() = synchronized(lock) { _state?.dimensions ?: 0 }

    private fun ensureState(): State? {
        synchronized(lock) {
            _state?.let { return it }

            val spFile = KernelModel.EMBEDDING_GEMMA_SP_MODEL.localFile(context)
            if (!spFile.exists()) {
                Log.w(TAG, "sentencepiece.model not found at ${spFile.absolutePath}")
                return null
            }

            return buildInterpreter(spFile, candidates = modelFileCandidates())
        }
    }

    /** Only the generic model is used — the SM8550 variant was disabled (LiteRT format incompatibility). */
    private fun modelFileCandidates(): List<File> = listOf(
        KernelModel.EMBEDDING_GEMMA_300M.localFile(context)
    ).filter { it.exists() }

    /**
     * Try each candidate in order, returning the first that initialises successfully.
     * The SM8550 model uses DISPATCH_OP (Qualcomm custom op) which fails without the
     * Qualcomm AI Engine delegate — we catch that and fall back to the generic model.
     */
    private fun buildInterpreter(spFile: File, candidates: List<File>): State? {
        if (candidates.isEmpty()) {
            Log.w(TAG, "No EmbeddingGemma model files found")
            return null
        }
        val tokenizer = try {
            SentencePieceTokenizer(spFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SentencePiece tokenizer", e)
            return null
        }
        val options = Interpreter.Options().apply { numThreads = 4 }
        for (modelFile in candidates) {
            try {
                val interpreter = Interpreter(mapModelFile(modelFile), options)
                val seqLen = interpreter.getInputTensor(0).shape().getOrElse(1) { 512 }
                val dim = interpreter.getOutputTensor(0).shape().last()
                Log.i(TAG, "EmbeddingGemma ready: model=${modelFile.name}, dim=$dim, seq=$seqLen")
                return State(interpreter, tokenizer, seqLen, dim).also { _state = it }
            } catch (e: Exception) {
                Log.w(TAG, "Model ${modelFile.name} failed to load (${e.message}), trying next")
            }
        }
        Log.e(TAG, "All EmbeddingGemma model candidates failed")
        return null
    }

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
