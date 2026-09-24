package com.kernel.ai.core.inference

import android.content.Context
import android.util.Log
import com.kernel.ai.core.inference.download.KernelModel
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
 * EmbeddingEngine backed by the Arctic Embed M v1.5 LiteRT artifact.
 *
 * Documents use uncased WordPiece without a prefix; queries use the upstream
 * retrieval instruction prefix. The model output is CLS pooled and L2-normalized.
 * Returns an empty vector while either required public model asset is unavailable.

 */
@Singleton
class LiteRtEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : EmbeddingEngine {

    private data class State(
        val interpreter: Interpreter,
        val tokenizer: ArcticEmbeddingTokenizer,
        val dimensions: Int,
    )

    private var _state: State? = null
    private val lock = Any()


    override val dimensions: Int get() = synchronized(lock) { _state?.dimensions ?: 0 }

    private fun ensureState(): State? {
        synchronized(lock) {
            _state?.let { return it }
            val modelFile = KernelModel.ARCTIC_EMBED_M_V1_5.localFile(context)
            val vocabFile = KernelModel.ARCTIC_EMBED_V1_5_VOCAB.localFile(context)
            if (!modelFile.isFile || !vocabFile.isFile) {
                Log.w(TAG, "Arctic Embed model or vocabulary is not downloaded")
                return null
            }
            return buildInterpreter(modelFile, vocabFile)
        }
    }

    private fun buildInterpreter(modelFile: File, vocabFile: File): State? {
        var interpreter: Interpreter? = null
        return try {
            val vocab = HashMap<String, Int>(30_522)
            vocabFile.bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, token -> vocab[token] = index }
            }
            require(vocab["[PAD]"] == 0 && vocab["[UNK]"] != null && vocab["[CLS]"] != null && vocab["[SEP]"] != null) {
                "Arctic vocabulary is missing required BERT special tokens"
            }

            val loaded = Interpreter(
                mapModelFile(modelFile),
                Interpreter.Options().apply { numThreads = 4 },
            )
            interpreter = loaded
            val inputShape = loaded.getInputTensor(0).shape()
            val maskShape = loaded.getInputTensor(1).shape()
            val outputShape = loaded.getOutputTensor(0).shape()
            require(loaded.inputTensorCount == 2 && inputShape.contentEquals(intArrayOf(1, 512)) &&
                maskShape.contentEquals(intArrayOf(1, 512)) && outputShape.contentEquals(intArrayOf(1, 768))) {
                "Unexpected Arctic tensor contract: inputs=${inputShape.contentToString()}, " +
                    "${maskShape.contentToString()}, output=${outputShape.contentToString()}"
            }
            Log.i(TAG, "Arctic Embed M v1.5 ready: model=${modelFile.name}, dim=768, seq=512")
            State(loaded, ArcticEmbeddingTokenizer(vocab), dimensions = 768).also { _state = it }
        } catch (e: Exception) {
            interpreter?.close()
            Log.e(TAG, "Failed to initialise Arctic Embed M v1.5", e)
            null
        }
    }

    override suspend fun embedQuery(text: String): FloatArray = embed(text, isQuery = true)

    override suspend fun embedDocument(text: String): FloatArray = embed(text, isQuery = false)

    private suspend fun embed(text: String, isQuery: Boolean): FloatArray = withContext(Dispatchers.IO) {
        val state = ensureState() ?: return@withContext FloatArray(0)
        val (tokenIds, maskValues) = if (isQuery) {
            state.tokenizer.encodeQuery(text)
        } else {
            state.tokenizer.encodeDocument(text)
        }
        synchronized(lock) {
            val output = Array(1) { FloatArray(state.dimensions) }
            state.interpreter.runForMultipleInputsOutputs(
                arrayOf<Any>(arrayOf(tokenIds), arrayOf(maskValues)),
                mapOf(0 to output),
            )
            output[0].l2Normalize()
        }
    }


    override fun close() {
        // Log at WARN so any unexpected call (e.g. accidental close during Gemma-4 init,
        // see #445) is immediately visible in logcat. Intentional shutdown calls are fine.
        Log.w(TAG, "EmbeddingEngine.close() called — interpreter will be nulled. " +
            "Subsequent embed() calls will re-initialise automatically.")
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

/** L2-normalizes this vector in-place and returns it. No-op if the magnitude is zero. */
private fun FloatArray.l2Normalize(): FloatArray {
    val magnitude = kotlin.math.sqrt(sumOf { (it * it).toDouble() }.toFloat())
    if (magnitude > 0f) {
        for (i in indices) this[i] /= magnitude
    }
    return this
}
