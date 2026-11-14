package com.kernel.ai


import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.skills.MiniLMIntentPhraseVectorAsset
import com.kernel.ai.core.skills.MiniLMIntentScorer
import com.kernel.ai.core.skills.MiniLMPhraseVectorizer
import com.kernel.ai.core.skills.QuickIntentRouter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

/** Opt-in generator; invoke it via direct instrumentation using the documented runner arguments. */
@RunWith(AndroidJUnit4::class)
class MiniLMPhraseVectorAssetGeneratorTest {
    @Test
    fun generateAssetAndVerifyRuntimeParity() {
        assumeTrue(
            "Set generate_minilm_phrase_vector_asset=true to run the one-time generator",
            InstrumentationRegistry.getArguments().getString(GENERATOR_ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val assets = context.assets
        val modelAsset = "minilm-l6-v2-int8.tflite"
        val vocabBytes = assets.open("vocab.txt").use { it.readBytes() }
        val phrasesBytes = assets.open("intent_phrases.json").use { it.readBytes() }
        val phrasesJson = String(phrasesBytes, StandardCharsets.UTF_8)
        val phraseCounts = MiniLMIntentPhraseVectorAsset.phraseCounts(phrasesJson)
        val vocab = HashMap<String, Int>(32_000)
        ByteArrayInputStream(vocabBytes).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, token -> vocab[token] = index }
        }

        val modelDescriptor = assets.openFd(modelAsset)
        val modelInput = modelDescriptor.createInputStream()
        val modelBuffer = modelInput.channel.map(
            FileChannel.MapMode.READ_ONLY,
            modelDescriptor.startOffset,
            modelDescriptor.declaredLength,
        )
        val interpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
            numThreads = 2
            useXNNPACK = true
        })
        try {
            val vectorizer = MiniLMPhraseVectorizer(vocab, interpreter)
            val buildStarted = android.os.SystemClock.elapsedRealtime()
            val runtimeVectors = vectorizer.buildPhraseVectors(phrasesJson)
            val buildElapsedMs = android.os.SystemClock.elapsedRealtime() - buildStarted
            assertEquals(phraseCounts.map { it.intent }.toSet(), runtimeVectors.keys)
            phraseCounts.forEach { group ->
                assertEquals(group.phraseCount, runtimeVectors.getValue(group.intent).size)
            }

            val sourceHashes = MiniLMIntentPhraseVectorAsset.SourceHashes(
                model = assets.open(modelAsset).use(MiniLMIntentPhraseVectorAsset::sha256),
                vocab = MiniLMIntentPhraseVectorAsset.sha256(vocabBytes),
                phrases = MiniLMIntentPhraseVectorAsset.sha256(phrasesBytes),
            )
            val generatedAssetBytes = MiniLMIntentPhraseVectorAsset.encode(runtimeVectors, phraseCounts, sourceHashes)
            val committedAssetBytes = try {
                assets.open(MiniLMIntentPhraseVectorAsset.FILE_NAME).use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
            val committedResult = committedAssetBytes?.let {
                MiniLMIntentPhraseVectorAsset.load(it, sourceHashes, phraseCounts)
            }
            val committedVectors = committedResult as? MiniLMIntentPhraseVectorAsset.LoadResult.Loaded
            val assetBytes = if (committedVectors != null) requireNotNull(committedAssetBytes) else generatedAssetBytes
            val loadStarted = android.os.SystemClock.elapsedRealtime()
            val loadedVectors = if (committedVectors != null) {
                committedVectors.vectors
            } else {
                val generatedResult = MiniLMIntentPhraseVectorAsset.load(generatedAssetBytes, sourceHashes, phraseCounts)
                assertTrue(generatedResult is MiniLMIntentPhraseVectorAsset.LoadResult.Loaded)
                (generatedResult as MiniLMIntentPhraseVectorAsset.LoadResult.Loaded).vectors
            }
            val assetLoadElapsedMs = android.os.SystemClock.elapsedRealtime() - loadStarted
            val assetSource = if (committedVectors != null) "committed" else "runtime-generated"
            if (committedResult is MiniLMIntentPhraseVectorAsset.LoadResult.Rejected) {
                Log.w(TAG, "Committed phrase vector asset was not reused: ${committedResult.reason}")
            }
            val parityStarted = android.os.SystemClock.elapsedRealtime()
            val noRegexInput = "minilm vector parity probe"
            assertTrue(QuickIntentRouter().route(noRegexInput) is QuickIntentRouter.RouteResult.FallThrough)

            var phraseIndex = 0
            var minimumCosine = 1f
            var maximumAbsoluteDelta = 0f
            var maximumBestScoreDelta = 0f
            var maximumSecondScoreDelta = 0f
            var bestIntentChanges = 0
            var secondIntentChanges = 0
            var confidenceThresholdCrossings = 0
            var ambiguityMarginCrossings = 0
            var classificationIntentChanges = 0
            var routeChanges = 0
            runtimeVectors.forEach { (intent, phraseVectors) ->
                phraseVectors.forEachIndexed { vectorIndex, runtimeVector ->
                    val assetVector = loadedVectors.getValue(intent)[vectorIndex]
                    assertArrayEquals(
                        "$intent phrase $vectorIndex",
                        runtimeVector,
                        assetVector,
                        MAX_VECTOR_COMPONENT_DELTA,
                    )
                    var vectorMaximumDelta = 0f
                    runtimeVector.indices.forEach { dimension ->
                        vectorMaximumDelta = maxOf(
                            vectorMaximumDelta,
                            kotlin.math.abs(runtimeVector[dimension] - assetVector[dimension]),
                        )
                    }
                    maximumAbsoluteDelta = maxOf(maximumAbsoluteDelta, vectorMaximumDelta)
                    val cosine = cosineSimilarity(runtimeVector, assetVector)
                    minimumCosine = minOf(minimumCosine, cosine)

                    val runtimeScores = MiniLMIntentScorer.score(runtimeVector, runtimeVectors)
                    val assetScores = MiniLMIntentScorer.score(runtimeVector, loadedVectors)
                    if (runtimeScores.bestIntent != assetScores.bestIntent) bestIntentChanges++
                    if (runtimeScores.topScores.getOrNull(1)?.first != assetScores.topScores.getOrNull(1)?.first) {
                        secondIntentChanges++
                    }
                    val runtimeConfident = runtimeScores.bestScore >= MiniLMIntentScorer.CONFIDENCE_THRESHOLD
                    val assetConfident = assetScores.bestScore >= MiniLMIntentScorer.CONFIDENCE_THRESHOLD
                    if (runtimeConfident != assetConfident) confidenceThresholdCrossings++
                    val runtimeUnambiguous =
                        runtimeScores.bestScore - runtimeScores.secondScore >= MiniLMIntentScorer.AMBIGUITY_MARGIN
                    val assetUnambiguous =
                        assetScores.bestScore - assetScores.secondScore >= MiniLMIntentScorer.AMBIGUITY_MARGIN
                    if (runtimeUnambiguous != assetUnambiguous) ambiguityMarginCrossings++
                    if (runtimeScores.classification?.intentName != assetScores.classification?.intentName) {
                        classificationIntentChanges++
                    }
                    if (routeIdentity(route(noRegexInput, runtimeScores.classification)) !=
                        routeIdentity(route(noRegexInput, assetScores.classification))
                    ) {
                        routeChanges++
                    }
                    maximumBestScoreDelta = maxOf(
                        maximumBestScoreDelta,
                        kotlin.math.abs(runtimeScores.bestScore - assetScores.bestScore),
                    )
                    maximumSecondScoreDelta = maxOf(
                        maximumSecondScoreDelta,
                        kotlin.math.abs(runtimeScores.secondScore - assetScores.secondScore),
                    )
                    phraseIndex++
                }
            }
            val parityElapsedMs = android.os.SystemClock.elapsedRealtime() - parityStarted
            assertEquals(515, phraseIndex)
            Log.i(
                TAG,
                "Compared $assetSource vectors=$phraseIndex bytes=${assetBytes.size}; " +
                    "runtimeBuildMs=$buildElapsedMs assetLoadMs=$assetLoadElapsedMs parityMs=$parityElapsedMs " +
                    "maximumAbsoluteDelta=$maximumAbsoluteDelta minimumCosine=$minimumCosine " +
                    "maximumBestScoreDelta=$maximumBestScoreDelta maximumSecondScoreDelta=$maximumSecondScoreDelta " +
                    "bestIntentChanges=$bestIntentChanges secondIntentChanges=$secondIntentChanges " +
                    "confidenceThresholdCrossings=$confidenceThresholdCrossings " +
                    "ambiguityMarginCrossings=$ambiguityMarginCrossings " +
                    "classificationIntentChanges=$classificationIntentChanges routeChanges=$routeChanges",
            )
            assertTrue("maximum vector delta=$maximumAbsoluteDelta", maximumAbsoluteDelta <= MAX_VECTOR_COMPONENT_DELTA)
            assertTrue("minimum cosine=$minimumCosine", minimumCosine >= MIN_VECTOR_COSINE)
            assertTrue("maximum best score delta=$maximumBestScoreDelta", maximumBestScoreDelta <= MAX_BEST_SCORE_DELTA)
            assertTrue(
                "maximum second score delta=$maximumSecondScoreDelta",
                maximumSecondScoreDelta <= MAX_SECOND_SCORE_DELTA,
            )
            assertEquals("top-1 intent changes", 0, bestIntentChanges)
            assertTrue(
                "top-2 intent changes=$secondIntentChanges",
                secondIntentChanges <= MAX_SECOND_INTENT_CHANGES,
            )
            assertEquals("confidence threshold crossings", 0, confidenceThresholdCrossings)
            assertEquals("ambiguity margin crossings", 0, ambiguityMarginCrossings)
            assertEquals("classification intent changes", 0, classificationIntentChanges)
            assertEquals("router outcome changes", 0, routeChanges)

            val output = File(requireNotNull(context.getExternalFilesDir(null)), MiniLMIntentPhraseVectorAsset.FILE_NAME)
            output.writeBytes(assetBytes)
            Log.i(
                TAG,
                "Verified $assetSource vectors=$phraseIndex bytes=${assetBytes.size}; " +
                    "runtimeBuildMs=$buildElapsedMs assetLoadMs=$assetLoadElapsedMs parityMs=$parityElapsedMs " +
                    "maximumAbsoluteDelta=$maximumAbsoluteDelta minimumCosine=$minimumCosine " +
                    "maximumBestScoreDelta=$maximumBestScoreDelta maximumSecondScoreDelta=$maximumSecondScoreDelta",
            )
        } finally {
            interpreter.close()
            modelInput.close()
            modelDescriptor.close()
        }
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        var dot = 0f
        var leftSquared = 0f
        var rightSquared = 0f
        for (index in left.indices) {
            val leftValue = left[index]
            val rightValue = right[index]
            dot += leftValue * rightValue
            leftSquared += leftValue * leftValue
            rightSquared += rightValue * rightValue
        }
        return dot / sqrt(leftSquared.toDouble() * rightSquared.toDouble()).toFloat()
    }
    private fun routeIdentity(result: QuickIntentRouter.RouteResult): Any = when (result) {
        is QuickIntentRouter.RouteResult.RegexMatch -> "regex" to result.intent
        is QuickIntentRouter.RouteResult.ClassifierMatch -> "classifier" to (result.intent to result.needsConfirmation)
        is QuickIntentRouter.RouteResult.FallThrough -> "fall-through" to result.bestGuess
        is QuickIntentRouter.RouteResult.NeedsSlot -> "needs-slot" to (result.intent to result.missingSlot)
    }

    private fun route(
        input: String,
        classification: QuickIntentRouter.IntentClassifier.Classification?,
    ): QuickIntentRouter.RouteResult = QuickIntentRouter(
        classifier = FixedClassifier(classification),
        similarityThreshold = MiniLMIntentScorer.CONFIDENCE_THRESHOLD,
    ).route(input)

    private class FixedClassifier(
        private val result: QuickIntentRouter.IntentClassifier.Classification?,
    ) : QuickIntentRouter.IntentClassifier {
        override fun classify(input: String) = result
    }

    private companion object {
        const val GENERATOR_ARGUMENT = "generate_minilm_phrase_vector_asset"
        const val TAG = "MiniLMPhraseVectorAssetGenerator"
        // Same 515-phrase API 35 x86_64 TFLite 2.17 vs LiteRT 2.1 comparison.
        // One second-rank change already occurs in the legacy TFLite-vs-asset baseline.
        const val MAX_VECTOR_COMPONENT_DELTA = 0.014f
        const val MIN_VECTOR_COSINE = 0.9975f
        const val MAX_BEST_SCORE_DELTA = 0.0025f
        const val MAX_SECOND_SCORE_DELTA = 0.0085f
        const val MAX_SECOND_INTENT_CHANGES = 1
    }
}

@RunWith(AndroidJUnit4::class)
class MiniLMIntentClassifierReadinessTest {
    @Test
    fun measuresReadinessWithoutOpeningChat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val classifier = com.kernel.ai.core.skills.MiniLMIntentClassifier(context)
        val deadline = startedAt + READINESS_TIMEOUT_MS
        while (!classifier.isReady() && !classifier.isFailed() &&
            android.os.SystemClock.elapsedRealtime() < deadline
        ) {
            android.os.SystemClock.sleep(50)
        }
        val readyElapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
        assertTrue("MiniLM classifier did not become ready within ${READINESS_TIMEOUT_MS}ms", classifier.isReady())
        Log.i(TAG, "MiniLM classifier readyMs=$readyElapsedMs")
    }

    private companion object {
        const val READINESS_TIMEOUT_MS = 180_000L
        const val TAG = "MiniLMIntentClassifierReadiness"
    }
}
