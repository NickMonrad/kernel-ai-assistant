package com.kernel.ai.core.skills

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Zero-shot intent classifier using all-MiniLM-L6-v2 (int8 TFLite).
 *
 * Embeds user input and compares against pre-computed intent phrase centroids
 * via cosine similarity. Thread-safe — TFLite interpreter access is synchronised.
 *
 * Implements [QuickIntentRouter.IntentClassifier] so it plugs directly into the
 * Tier 2 fast intent pipeline without any changes to [QuickIntentRouter].
 *
 * Graceful degradation: if model or vocab assets are missing, [classify] returns null
 * and the router falls through to E4B.
 */
@Singleton
class MiniLMIntentClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : QuickIntentRouter.IntentClassifier {

    companion object {
        private const val TAG = "MiniLMIntentClassifier"
        private const val MODEL_ASSET = "minilm-l6-v2-int8.tflite"
        private const val VOCAB_ASSET = "vocab.txt"
        private const val PHRASES_ASSET = "intent_phrases.json"
        private const val EMBEDDING_DIM = 384
        private const val MAX_SEQ_LEN = 64
        private const val CONFIDENCE_THRESHOLD = 0.75f
        private const val AMBIGUITY_MARGIN = 0.05f
        private const val UNK = "[UNK]"
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
    }

    // All mutable state is set exactly once from the init coroutine and then read-only.
    @Volatile private var vocab: Map<String, Int>? = null
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var intentCentroids: Map<String, FloatArray>? = null
    @Volatile private var initFailed: Boolean = false
    private val interpreterLock = Any()
    private val initJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            val v = loadVocab()
            val interp = loadInterpreter() ?: run {
                initFailed = true
                return@launch
            }
            val phrasesJson = context.assets.open(PHRASES_ASSET).bufferedReader().readText()
            val centroids = computeCentroids(phrasesJson, v, interp)
            vocab = v
            interpreter = interp
            intentCentroids = centroids
            Log.i(TAG, "Ready: ${centroids.size} intent centroids loaded")
        } catch (e: Exception) {
            initFailed = true
            Log.e(TAG, "Failed to initialise — classify() will return null", e)
        }
    }

    override fun classify(input: String): QuickIntentRouter.IntentClassifier.Classification? {
        if (input.isBlank()) return null

        // If init hasn't finished yet, wait up to 500ms before giving up — prevents the race
        // condition where the first user message arrives before centroid pre-computation is done.
        if (intentCentroids == null && !initFailed) {
            Log.i(TAG, "classify('$input') — init still in progress, waiting up to 500ms")
            runBlocking { withTimeoutOrNull(500) { initJob.join() } }
        }

        val v = vocab ?: run {
            Log.i(TAG, "classify('$input') — not ready (vocab null, initFailed=$initFailed), skipping")
            return null
        }
        val interp = interpreter ?: run {
            Log.i(TAG, "classify('$input') — not ready (interpreter null), skipping")
            return null
        }
        val centroids = intentCentroids ?: run {
            Log.i(TAG, "classify('$input') — not ready (centroids null), skipping")
            return null
        }

        Log.i(TAG, "classify('$input') — running against ${centroids.size} centroids")

        val queryEmbedding = synchronized(interpreterLock) {
            embed(input.lowercase().trim(), v, interp)
        } ?: return null

        var bestIntent: String? = null
        var bestScore = -1f
        var secondScore = -1f

        for ((name, centroid) in centroids) {
            val score = dot(queryEmbedding, centroid)
            when {
                score > bestScore -> { secondScore = bestScore; bestScore = score; bestIntent = name }
                score > secondScore -> secondScore = score
            }
        }

        if (bestIntent == null || bestScore < CONFIDENCE_THRESHOLD) {
            Log.i(TAG, "classify('$input') — below threshold: best=$bestIntent score=${"%.3f".format(bestScore)} threshold=$CONFIDENCE_THRESHOLD")
            return null
        }
        if (bestScore - secondScore < AMBIGUITY_MARGIN) {
            Log.i(TAG, "classify('$input') — ambiguous: best=$bestIntent score=${"%.3f".format(bestScore)} second=${"%.3f".format(secondScore)}")
            return null
        }

        Log.i(TAG, "classify('$input') -> $bestIntent (score=${"%.3f".format(bestScore)}, margin=${"%.3f".format(bestScore - secondScore)})")
        return QuickIntentRouter.IntentClassifier.Classification(bestIntent, bestScore)
    }

    // ── Model loading ────────────────────────────────────────────────────────

    private fun loadInterpreter(): Interpreter? {
        return try {
            val fd = context.assets.openFd(MODEL_ASSET)
            val buffer = fd.createInputStream().channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
            val options = Interpreter.Options().apply {
                numThreads = 2
                useXNNPACK = true
            }
            Interpreter(buffer, options).also {
                Log.d(TAG, "TFLite interpreter loaded (${fd.declaredLength / 1024 / 1024}MB)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Model asset '$MODEL_ASSET' not found — classifier disabled", e)
            null
        }
    }

    // ── Vocabulary ───────────────────────────────────────────────────────────

    private fun loadVocab(): Map<String, Int> {
        val map = HashMap<String, Int>(32_000)
        context.assets.open(VOCAB_ASSET).bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, token -> map[token] = i }
        }
        Log.d(TAG, "Vocab loaded: ${map.size} tokens")
        return map
    }

    // ── Tokenisation (BERT WordPiece) ────────────────────────────────────────

    private fun tokenize(text: String, vocab: Map<String, Int>): Pair<IntArray, IntArray> {
        val basicTokens = basicTokenize(text)
        val wpTokens = mutableListOf<String>()
        for (token in basicTokens) wpTokens.addAll(wordPiece(token, vocab))

        val maxTokens = MAX_SEQ_LEN - 2
        val truncated = if (wpTokens.size > maxTokens) wpTokens.subList(0, maxTokens) else wpTokens

        val tokens = buildList {
            add(CLS)
            addAll(truncated)
            add(SEP)
        }

        val inputIds = IntArray(MAX_SEQ_LEN)
        val attentionMask = IntArray(MAX_SEQ_LEN)
        for (i in tokens.indices) {
            inputIds[i] = vocab[tokens[i]] ?: vocab[UNK] ?: 0
            attentionMask[i] = 1
        }
        return inputIds to attentionMask
    }

    private fun basicTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in text) {
            when {
                ch.isWhitespace() -> { if (buf.isNotEmpty()) { tokens += buf.toString(); buf.clear() } }
                isPunct(ch) -> { if (buf.isNotEmpty()) { tokens += buf.toString(); buf.clear() }; tokens += ch.toString() }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) tokens += buf.toString()
        return tokens
    }

    private fun wordPiece(word: String, vocab: Map<String, Int>): List<String> {
        if (word.length > 200) return listOf(UNK)
        val result = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: String? = null
            while (start < end) {
                val substr = if (start == 0) word.substring(start, end) else "##${word.substring(start, end)}"
                if (substr in vocab) { found = substr; break }
                end--
            }
            if (found == null) return listOf(UNK)
            result += found
            start = end
        }
        return result
    }

    private fun isPunct(ch: Char) = ch in "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    // ── Embedding ────────────────────────────────────────────────────────────

    private fun embed(text: String, vocab: Map<String, Int>, interp: Interpreter): FloatArray? {
        return try {
            val (inputIds, mask) = tokenize(text, vocab)

            val inputIdsBatch = Array(1) { inputIds }
            val maskBatch = Array(1) { mask }
            val tokenTypeIds = Array(1) { IntArray(MAX_SEQ_LEN) }  // all zeros

            // Check output tensor shape to handle both model variants:
            // [1, MAX_SEQ_LEN, EMBEDDING_DIM] — all token embeddings, we mean-pool
            // [1, EMBEDDING_DIM]              — already mean-pooled by the model
            val outputShape = interp.getOutputTensor(0).shape()

            if (outputShape.size == 2 && outputShape[1] == EMBEDDING_DIM) {
                // Model already outputs a single pooled embedding
                val output = Array(1) { FloatArray(EMBEDDING_DIM) }
                interp.runForMultipleInputsOutputs(
                    arrayOf(inputIdsBatch, maskBatch, tokenTypeIds),
                    mapOf(0 to output)
                )
                output[0].l2Normalize()
            } else {
                // Output: [1, MAX_SEQ_LEN, EMBEDDING_DIM] — all token embeddings
                val tokenEmbeddings = Array(1) { Array(MAX_SEQ_LEN) { FloatArray(EMBEDDING_DIM) } }
                interp.runForMultipleInputsOutputs(
                    arrayOf(inputIdsBatch, maskBatch, tokenTypeIds),
                    mapOf(0 to tokenEmbeddings)
                )
                // Mean pooling over non-padding tokens, then L2-normalise
                meanPool(tokenEmbeddings[0], mask).l2Normalize()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embed failed for '$text'", e)
            null
        }
    }

    private fun meanPool(tokenEmbeddings: Array<FloatArray>, mask: IntArray): FloatArray {
        val result = FloatArray(EMBEDDING_DIM)
        var count = 0
        for (i in mask.indices) {
            if (mask[i] == 0) continue
            for (j in 0 until EMBEDDING_DIM) result[j] += tokenEmbeddings[i][j]
            count++
        }
        if (count > 0) for (j in result.indices) result[j] /= count
        return result
    }

    private fun FloatArray.l2Normalize(): FloatArray {
        val mag = sqrt(sumOf { (it * it).toDouble() }.toFloat())
        if (mag > 0f) for (i in indices) this[i] /= mag
        return this
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    // ── Centroid pre-computation ─────────────────────────────────────────────

    private fun computeCentroids(
        phrasesJson: String,
        vocab: Map<String, Int>,
        interp: Interpreter,
    ): Map<String, FloatArray> {
        val root = JSONObject(phrasesJson)
        val intents = root.getJSONObject("intents")
        val centroids = HashMap<String, FloatArray>()

        for (intentName in intents.keys()) {
            val phrases = intents.getJSONObject(intentName).getJSONArray("phrases")
            val vectors = mutableListOf<FloatArray>()
            for (i in 0 until phrases.length()) {
                val phrase = phrases.getString(i).lowercase().trim()
                synchronized(interpreterLock) {
                    embed(phrase, vocab, interp)
                }?.let { vectors += it }
            }
            if (vectors.isEmpty()) continue

            // Average the phrase embeddings into a single centroid, then re-normalise
            val centroid = FloatArray(EMBEDDING_DIM)
            for (v in vectors) for (j in v.indices) centroid[j] += v[j]
            for (j in centroid.indices) centroid[j] /= vectors.size
            centroids[intentName] = centroid.l2Normalize()
        }
        return centroids
    }
}
