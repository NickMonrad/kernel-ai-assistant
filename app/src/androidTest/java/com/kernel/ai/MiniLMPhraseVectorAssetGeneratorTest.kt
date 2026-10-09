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

/** Opt-in generator: ./gradlew :app:connectedDebugAndroidTest with the documented runner arg. */
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
            runtimeVectors.forEach { (intent, phraseVectors) ->
                phraseVectors.forEachIndexed { vectorIndex, runtimeVector ->
                    val assetVector = loadedVectors.getValue(intent)[vectorIndex]
                    assertArrayEquals("$intent phrase $vectorIndex", runtimeVector, assetVector, 1e-6f)
                    val runtimeScores = MiniLMIntentScorer.score(runtimeVector, runtimeVectors)
                    val assetScores = MiniLMIntentScorer.score(runtimeVector, loadedVectors)
                    assertEquals("top-1 for phrase $phraseIndex", runtimeScores.bestIntent, assetScores.bestIntent)
                    assertEquals("best score for phrase $phraseIndex", runtimeScores.bestScore, assetScores.bestScore, 1e-6f)
                    assertEquals("second score for phrase $phraseIndex", runtimeScores.secondScore, assetScores.secondScore, 1e-6f)
                    assertEquals("classification for phrase $phraseIndex", runtimeScores.classification, assetScores.classification)
                    assertEquals(
                        "QuickIntentRouter result for phrase $phraseIndex",
                        route(noRegexInput, runtimeScores.classification),
                        route(noRegexInput, assetScores.classification),
                    )
                    phraseIndex++
                }
            }
            val parityElapsedMs = android.os.SystemClock.elapsedRealtime() - parityStarted
            assertEquals(515, phraseIndex)

            val output = File(requireNotNull(context.getExternalFilesDir(null)), MiniLMIntentPhraseVectorAsset.FILE_NAME)
            output.writeBytes(assetBytes)
            Log.i(
                TAG,
                "Verified $assetSource ${output.absolutePath}; vectors=$phraseIndex bytes=${assetBytes.size}; " +
                    "runtimeBuildMs=$buildElapsedMs assetLoadMs=$assetLoadElapsedMs parityMs=$parityElapsedMs",
            )
        } finally {
            interpreter.close()
            modelInput.close()
            modelDescriptor.close()
        }
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
    }
}
