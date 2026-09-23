package com.kernel.ai.core.inference

/** Arctic Embed M v1.5's asymmetric retrieval preprocessing. */
internal class ArcticEmbeddingTokenizer(
    private val vocabulary: Map<String, Int>,
    private val sequenceLength: Int = SEQUENCE_LENGTH,
) {
    fun encodeQuery(text: String): Pair<IntArray, IntArray> = encode(QUERY_PREFIX + text)

    fun encodeDocument(text: String): Pair<IntArray, IntArray> = encode(text)

    private fun encode(text: String): Pair<IntArray, IntArray> = WordPieceTokenizer.encode(
        text = text,
        vocab = vocabulary,
        maxSequenceLength = sequenceLength,
        lowerCase = true,
        stripAccents = true,
        maxWordLength = MAX_WORD_LENGTH,
    )

    companion object {
        const val QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
        const val SEQUENCE_LENGTH = 512
        private const val MAX_WORD_LENGTH = 100
    }
}
