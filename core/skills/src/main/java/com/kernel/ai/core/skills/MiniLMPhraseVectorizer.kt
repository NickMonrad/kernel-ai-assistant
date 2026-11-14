package com.kernel.ai.core.skills

import android.util.Log
import com.kernel.ai.core.inference.WordPieceTokenizer
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import kotlin.math.sqrt

/** Shared production embedding path used by runtime fallback and the asset generator. */
class MiniLMPhraseVectorizer(
    private val vocab: Map<String, Int>,
    private val interpreter: Interpreter,
) {
    private val interpreterLock = Any()

    fun embed(text: String): FloatArray? = synchronized(interpreterLock) {
        try {
            val (inputIds, mask) = WordPieceTokenizer.encode(text, vocab, MAX_SEQ_LEN)
            val inputIdsBatch = Array(1) { inputIds }
            val maskBatch = Array(1) { mask }
            val outputShape = interpreter.getOutputTensor(0).shape()
            if (outputShape.size == 2 && outputShape[1] == MiniLMIntentPhraseVectorAsset.EMBEDDING_DIMENSION) {
                val output = Array(1) { FloatArray(MiniLMIntentPhraseVectorAsset.EMBEDDING_DIMENSION) }
                interpreter.runForMultipleInputsOutputs(arrayOf(inputIdsBatch, maskBatch), mapOf(0 to output))
                output[0].l2Normalize()
            } else {
                val tokenEmbeddings = Array(1) {
                    Array(MAX_SEQ_LEN) { FloatArray(MiniLMIntentPhraseVectorAsset.EMBEDDING_DIMENSION) }
                }
                interpreter.runForMultipleInputsOutputs(arrayOf(inputIdsBatch, maskBatch), mapOf(0 to tokenEmbeddings))
                meanPool(tokenEmbeddings[0], mask).l2Normalize()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Embed failed for '$text'", exception)
            null
        }
    }

    fun buildPhraseVectors(phrasesJson: String): Map<String, List<FloatArray>> {
        val intents = JSONObject(phrasesJson).getJSONObject("intents")
        val result = HashMap<String, List<FloatArray>>()
        for (intentName in intents.keys()) {
            val phrases = intents.getJSONObject(intentName).getJSONArray("phrases")
            val vectors = mutableListOf<FloatArray>()
            for (index in 0 until phrases.length()) {
                val phrase = phrases.getString(index).lowercase().trim()
                embed(phrase)?.let(vectors::add)
            }
            if (vectors.isNotEmpty()) result[intentName] = vectors
        }
        return result
    }

    private fun meanPool(tokenEmbeddings: Array<FloatArray>, mask: IntArray): FloatArray {
        val result = FloatArray(MiniLMIntentPhraseVectorAsset.EMBEDDING_DIMENSION)
        var count = 0
        for (index in mask.indices) {
            if (mask[index] == 0) continue
            for (dimension in result.indices) result[dimension] += tokenEmbeddings[index][dimension]
            count++
        }
        if (count > 0) for (index in result.indices) result[index] /= count
        return result
    }

    private fun FloatArray.l2Normalize(): FloatArray {
        val magnitude = sqrt(sumOf { (it * it).toDouble() }.toFloat())
        if (magnitude > 0f) for (index in indices) this[index] /= magnitude
        return this
    }

    private companion object {
        const val MAX_SEQ_LEN = 64
        const val TAG = "MiniLMIntentClassifier"
    }
}
