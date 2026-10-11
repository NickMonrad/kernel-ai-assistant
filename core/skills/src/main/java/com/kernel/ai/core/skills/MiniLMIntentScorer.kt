package com.kernel.ai.core.skills

/** Production nearest-neighbour scoring shared with phrase-vector parity checks. */
object MiniLMIntentScorer {
    // Must stay at or below the router's soft-fallback threshold so low-confidence guesses reach E4B.
    const val CONFIDENCE_THRESHOLD = 0.50f
    const val AMBIGUITY_MARGIN = 0.05f

    data class Result(
        val classification: QuickIntentRouter.IntentClassifier.Classification?,
        val bestIntent: String?,
        val bestScore: Float,
        val secondScore: Float,
        val topScores: List<Pair<String, Float>>,
    )

    fun score(
        queryEmbedding: FloatArray,
        phraseVectors: Map<String, List<FloatArray>>,
    ): Result {
        var bestIntent: String? = null
        var bestScore = -1f
        var secondScore = -1f
        val topScores = mutableListOf<Pair<String, Float>>()

        for ((name, vectors) in phraseVectors) {
            val score = vectors.maxOf { dot(queryEmbedding, it) }
            when {
                score > bestScore -> { secondScore = bestScore; bestScore = score; bestIntent = name }
                score > secondScore -> secondScore = score
            }
            val insertAt = topScores.indexOfFirst { score > it.second }
            if (insertAt >= 0) topScores.add(insertAt, name to score) else topScores.add(name to score)
            if (topScores.size > 5) topScores.removeAt(5)
        }

        val classification = if (bestIntent != null && bestScore >= CONFIDENCE_THRESHOLD &&
            bestScore - secondScore >= AMBIGUITY_MARGIN
        ) {
            QuickIntentRouter.IntentClassifier.Classification(bestIntent, bestScore)
        } else {
            null
        }
        return Result(classification, bestIntent, bestScore, secondScore, topScores)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (index in a.indices) sum += a[index] * b[index]
        return sum
    }
}
