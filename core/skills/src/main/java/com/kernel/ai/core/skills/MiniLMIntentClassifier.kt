package com.kernel.ai.core.skills

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import org.tensorflow.lite.Interpreter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero-shot intent classifier using all-MiniLM-L6-v2 (int8 TFLite).
 *
 * Embeds user input and scores it against precomputed individual intent phrases
 * via nearest-neighbour cosine similarity. Thread-safe — interpreter access is synchronised.
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

        internal fun loadOrBuildPhraseVectors(
            assetResult: MiniLMIntentPhraseVectorAsset.LoadResult,
            buildRuntimeVectors: (String) -> Map<String, List<FloatArray>>,
        ): Map<String, List<FloatArray>> =
            when (assetResult) {
                is MiniLMIntentPhraseVectorAsset.LoadResult.Loaded -> assetResult.vectors
                is MiniLMIntentPhraseVectorAsset.LoadResult.Rejected ->
                    buildRuntimeVectors(assetResult.reason)
            }

    }

    // All mutable state is set exactly once from the init coroutine and then read-only.
    @Volatile private var vocab: Map<String, Int>? = null
    @Volatile private var vectorizer: MiniLMPhraseVectorizer? = null
    /** Per-intent phrase vectors; scoring uses nearest-neighbour similarity. */
    @Volatile private var intentPhraseVectors: Map<String, List<FloatArray>>? = null
    @Volatile private var initFailed: Boolean = false
    private val initJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            val vocabBytes = context.assets.open(VOCAB_ASSET).use { it.readBytes() }
            val loadedVocab = loadVocab(vocabBytes)
            val interp = loadInterpreter() ?: run {
                initFailed = true
                return@launch
            }
            val phraseVectorsStartedAt = android.os.SystemClock.elapsedRealtime()
            val phraseBytes = context.assets.open(PHRASES_ASSET).use { it.readBytes() }
            val phrasesJson = String(phraseBytes, StandardCharsets.UTF_8)
            val phraseCounts = MiniLMIntentPhraseVectorAsset.phraseCounts(phrasesJson)
            val encoder = MiniLMPhraseVectorizer(loadedVocab, interp)
            vocab = loadedVocab
            vectorizer = encoder

            val hashes = MiniLMIntentPhraseVectorAsset.SourceHashes(
                model = context.assets.open(MODEL_ASSET).use(MiniLMIntentPhraseVectorAsset::sha256),
                vocab = MiniLMIntentPhraseVectorAsset.sha256(vocabBytes),
                phrases = MiniLMIntentPhraseVectorAsset.sha256(phraseBytes),
            )
            val assetResult = try {
                context.assets.open(MiniLMIntentPhraseVectorAsset.FILE_NAME).use { bytes ->
                    MiniLMIntentPhraseVectorAsset.load(bytes.readBytes(), hashes, phraseCounts)
                }
            } catch (exception: Exception) {
                MiniLMIntentPhraseVectorAsset.LoadResult.Rejected(
                    "asset unavailable (${exception.javaClass.simpleName})",
                )
            }
            if (assetResult is MiniLMIntentPhraseVectorAsset.LoadResult.Loaded) {
                Log.i(TAG, "Loaded hash-matched phrase vectors from ${MiniLMIntentPhraseVectorAsset.FILE_NAME}")
            }
            val phraseVectors = loadOrBuildPhraseVectors(assetResult) { reason ->
                Log.w(TAG, "Precomputed phrase vectors rejected: $reason; building at runtime")
                encoder.buildPhraseVectors(phrasesJson)
            }
            intentPhraseVectors = phraseVectors
            val totalVectors = phraseVectors.values.sumOf { it.size }
            Log.i(
                TAG,
                "Ready: ${phraseVectors.size} intents, $totalVectors phrase vectors loaded " +
                    "(nearest-neighbour); phraseVectorsReadyMs=${android.os.SystemClock.elapsedRealtime() - phraseVectorsStartedAt}",
            )
        } catch (e: Exception) {
            initFailed = true
            Log.e(TAG, "Failed to initialise — classify() will return null", e)
        }
    }

    override fun isReady(): Boolean = vocab != null && vectorizer != null && intentPhraseVectors != null
    override fun isFailed(): Boolean = initFailed

    override fun classify(input: String): QuickIntentRouter.IntentClassifier.Classification? {
        if (input.isBlank()) return null
        if (vocab == null) {
            Log.i(TAG, "classify('$input') — not ready (vocab null, initFailed=$initFailed), skipping")
            return null
        }
        val encoder = vectorizer ?: run {
            Log.i(TAG, "classify('$input') — not ready (interpreter null), skipping")
            return null
        }
        val phraseVectors = intentPhraseVectors ?: run {
            Log.i(TAG, "classify('$input') — not ready (phraseVectors null), skipping")
            return null
        }

        Log.i(TAG, "classify('$input') — running against ${phraseVectors.size} intents")
        if (phraseVectors.isEmpty()) {
            Log.w(TAG, "classify('$input') — phrase vector map is empty (all embeds failed at init), skipping")
            return null
        }
        val queryEmbedding = encoder.embed(input.lowercase().trim()) ?: return null
        val scores = MiniLMIntentScorer.score(queryEmbedding, phraseVectors)
        val top5Log = scores.topScores.joinToString(" | ") { (name, score) -> "$name=${"%.3f".format(score)}" }
        val classification = scores.classification
        if (classification == null) {
            if (scores.bestIntent == null || scores.bestScore < MiniLMIntentScorer.CONFIDENCE_THRESHOLD) {
                Log.i(
                    TAG,
                    "classify('$input') — below threshold: best=${scores.bestIntent} " +
                        "score=${"%.3f".format(scores.bestScore)} threshold=${MiniLMIntentScorer.CONFIDENCE_THRESHOLD} top5=[$top5Log]",
                )
            } else {
                Log.i(
                    TAG,
                    "classify('$input') — ambiguous: best=${scores.bestIntent} " +
                        "score=${"%.3f".format(scores.bestScore)} second=${"%.3f".format(scores.secondScore)} " +
                        "margin=${"%.3f".format(scores.bestScore - scores.secondScore)} top5=[$top5Log]",
                )
            }
            return null
        }
        Log.i(
            TAG,
            "classify('$input') -> ${classification.intentName} " +
                "(score=${"%.3f".format(classification.confidence)}, " +
                "margin=${"%.3f".format(scores.bestScore - scores.secondScore)}, top5=[$top5Log])",
        )
        return classification
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

    private fun loadVocab(bytes: ByteArray): Map<String, Int> {
        val map = HashMap<String, Int>(32_000)
        ByteArrayInputStream(bytes).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, token -> map[token] = index }
        }
        Log.d(TAG, "Vocab loaded: ${map.size} tokens")
        return map
    }


}
