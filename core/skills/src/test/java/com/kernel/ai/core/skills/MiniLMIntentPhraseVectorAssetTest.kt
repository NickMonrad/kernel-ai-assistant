package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiniLMIntentPhraseVectorAssetTest {
    private val groups = listOf(
        MiniLMIntentPhraseVectorAsset.IntentPhraseCount("first_intent", 2),
        MiniLMIntentPhraseVectorAsset.IntentPhraseCount("second_intent", 1),
    )
    private val hashes = MiniLMIntentPhraseVectorAsset.SourceHashes(
        model = "1".repeat(64),
        vocab = "2".repeat(64),
        phrases = "3".repeat(64),
    )
    private val vectors = mapOf(
        "first_intent" to listOf(vector(0), vector(1)),
        "second_intent" to listOf(vector(2)),
    )

    @Test
    fun `round trips grouped vectors with matching source hashes`() {
        val encoded = MiniLMIntentPhraseVectorAsset.encode(vectors, groups, hashes)

        val loaded = assertInstanceOf(
            MiniLMIntentPhraseVectorAsset.LoadResult.Loaded::class.java,
            MiniLMIntentPhraseVectorAsset.load(encoded, hashes, groups),
        )
        assertEquals(vectors.keys, loaded.vectors.keys)
        vectors.forEach { (intent, expectedVectors) ->
            val actualVectors = loaded.vectors.getValue(intent)
            assertEquals(expectedVectors.size, actualVectors.size)
            expectedVectors.indices.forEach { index ->
                assertArrayEquals(expectedVectors[index], actualVectors[index])
            }
        }
    }

    @Test
    fun `loaded vectors preserve MiniLM scores and QuickIntentRouter decision`() {
        val encoded = MiniLMIntentPhraseVectorAsset.encode(vectors, groups, hashes)
        val loaded = MiniLMIntentClassifier.loadOrBuildPhraseVectors(
            assetResult = MiniLMIntentPhraseVectorAsset.load(encoded, hashes, groups),
        ) {
            error("A valid asset must not rebuild phrase vectors")
        }
        val query = vectors.getValue("first_intent").first()
        val runtimeScores = MiniLMIntentScorer.score(query, vectors)
        val loadedScores = MiniLMIntentScorer.score(query, loaded)

        assertEquals(runtimeScores.bestIntent, loadedScores.bestIntent)
        assertEquals(runtimeScores.bestScore, loadedScores.bestScore, 1e-6f)
        assertEquals(runtimeScores.classification, loadedScores.classification)
        val input = "minilm vector parity probe"
        assertInstanceOf(QuickIntentRouter.RouteResult.FallThrough::class.java, QuickIntentRouter().route(input))
        val runtimeRoute = QuickIntentRouter(
            classifier = FixedClassifier(runtimeScores.classification),
            similarityThreshold = MiniLMIntentScorer.CONFIDENCE_THRESHOLD,
        ).route(input)
        val loadedRoute = QuickIntentRouter(
            classifier = FixedClassifier(loadedScores.classification),
            similarityThreshold = MiniLMIntentScorer.CONFIDENCE_THRESHOLD,
        ).route(input)
        assertEquals(runtimeRoute, loadedRoute)
    }

    private class FixedClassifier(
        private val result: QuickIntentRouter.IntentClassifier.Classification?,
    ) : QuickIntentRouter.IntentClassifier {
        override fun classify(input: String) = result
    }

    @Test
    fun `rejects vectors when any source asset hash differs`() {
        val encoded = MiniLMIntentPhraseVectorAsset.encode(vectors, groups, hashes)
        val changedModel = hashes.copy(model = "4".repeat(64))

        val rejected = assertInstanceOf(
            MiniLMIntentPhraseVectorAsset.LoadResult.Rejected::class.java,
            MiniLMIntentPhraseVectorAsset.load(encoded, changedModel, groups),
        )
        assertEquals("source hash mismatch", rejected.reason)
    }

    @Test
    fun `rejects vectors when phrase shape differs`() {
        val encoded = MiniLMIntentPhraseVectorAsset.encode(vectors, groups, hashes)
        val changedGroups = groups.map { group ->
            if (group.intent == "first_intent") group.copy(phraseCount = 1) else group
        }

        val rejected = assertInstanceOf(
            MiniLMIntentPhraseVectorAsset.LoadResult.Rejected::class.java,
            MiniLMIntentPhraseVectorAsset.load(encoded, hashes, changedGroups),
        )
        assertEquals("phrase shape mismatch", rejected.reason)
    }

    @Test
    fun `rejects a payload corrupted after generation`() {
        val encoded = MiniLMIntentPhraseVectorAsset.encode(vectors, groups, hashes)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()

        val rejected = assertInstanceOf(
            MiniLMIntentPhraseVectorAsset.LoadResult.Rejected::class.java,
            MiniLMIntentPhraseVectorAsset.load(encoded, hashes, groups),
        )
        assertEquals("payload checksum mismatch", rejected.reason)
    }

    @Test
    fun `stale and corrupt assets rebuild phrase vectors that still classify`() {
        val encoded = MiniLMIntentPhraseVectorAsset.encode(vectors, groups, hashes)
        val stale = assertInstanceOf(
            MiniLMIntentPhraseVectorAsset.LoadResult.Rejected::class.java,
            MiniLMIntentPhraseVectorAsset.load(encoded, hashes.copy(model = "4".repeat(64)), groups),
        )
        assertEquals("source hash mismatch", stale.reason)

        val corrupted = encoded.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }
        val corrupt = assertInstanceOf(
            MiniLMIntentPhraseVectorAsset.LoadResult.Rejected::class.java,
            MiniLMIntentPhraseVectorAsset.load(corrupted, hashes, groups),
        )
        assertEquals("payload checksum mismatch", corrupt.reason)

        listOf(stale, corrupt).forEach { rejected ->
            var buildCount = 0
            val rebuiltVectors = MiniLMIntentClassifier.loadOrBuildPhraseVectors(rejected) { reason ->
                assertEquals(rejected.reason, reason)
                buildCount++
                vectors
            }
            assertEquals(1, buildCount)
            assertEquals(vectors.keys, rebuiltVectors.keys)
            vectors.forEach { (intent, expectedVectors) ->
                val actualVectors = rebuiltVectors.getValue(intent)
                assertEquals(expectedVectors.size, actualVectors.size)
                expectedVectors.indices.forEach { index ->
                    assertArrayEquals(expectedVectors[index], actualVectors[index])
                }
            }

            val classification = MiniLMIntentScorer.score(
                vectors.getValue("first_intent").first(),
                rebuiltVectors,
            ).classification
            assertEquals("first_intent", classification?.intentName)
        }
    }

    @Test
    fun `rejects truncated and version-mismatched files`() {
        val encoded = MiniLMIntentPhraseVectorAsset.encode(vectors, groups, hashes)
        val truncated = assertInstanceOf(
            MiniLMIntentPhraseVectorAsset.LoadResult.Rejected::class.java,
            MiniLMIntentPhraseVectorAsset.load(encoded.copyOf(20), hashes, groups),
        )
        assertEquals("truncated header", truncated.reason)

        val unsupported = encoded.copyOf().apply { this[11] = 2 }
        val versionMismatch = assertInstanceOf(
            MiniLMIntentPhraseVectorAsset.LoadResult.Rejected::class.java,
            MiniLMIntentPhraseVectorAsset.load(unsupported, hashes, groups),
        )
        assertTrue(versionMismatch.reason.startsWith("unsupported version"))
    }

    private fun vector(axis: Int): FloatArray =
        FloatArray(MiniLMIntentPhraseVectorAsset.EMBEDDING_DIMENSION).also { it[axis] = 1f }
}
