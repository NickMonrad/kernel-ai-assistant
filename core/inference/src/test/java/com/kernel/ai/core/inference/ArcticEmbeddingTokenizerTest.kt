package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArcticEmbeddingTokenizerTest {
    private val vocabulary = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", "cafe",
        "represent", "this", "sentence", "for", "searching", "relevant", "passages", ":",
    ).withIndex().associate { it.value to it.index }

    @Test
    fun `query receives retrieval prefix while document does not`() {
        val tokenizer = ArcticEmbeddingTokenizer(vocabulary, sequenceLength = 16)

        val document = tokenizer.encodeDocument("Café")
        val query = tokenizer.encodeQuery("Café")

        assertArrayEquals(intArrayOf(2, 6, 3), document.first.copyOfRange(0, 3))
        assertArrayEquals(intArrayOf(1, 1, 1), document.second.copyOfRange(0, 3))
        assertArrayEquals(
            intArrayOf(2, 7, 8, 9, 10, 11, 12, 13, 14, 6, 3),
            query.first.copyOfRange(0, 11),
        )
        assertEquals(11, query.second.takeWhile { it == 1 }.size)
    }

    @Test
    fun `fixed-width input retains CLS SEP mask and truncates content`() {
        val tokenizer = ArcticEmbeddingTokenizer(vocabulary, sequenceLength = 4)

        val (ids, mask) = tokenizer.encodeDocument("hello world hello")

        assertArrayEquals(intArrayOf(2, 4, 5, 3), ids)
        assertArrayEquals(intArrayOf(1, 1, 1, 1), mask)
    }

    @Test
    fun `padding is masked out`() {
        val tokenizer = ArcticEmbeddingTokenizer(vocabulary, sequenceLength = 6)

        val (ids, mask) = tokenizer.encodeDocument("hello")

        assertArrayEquals(intArrayOf(2, 4, 3, 0, 0, 0), ids)
        assertArrayEquals(intArrayOf(1, 1, 1, 0, 0, 0), mask)
    }
    @Test
    fun `BERT cleanup removes format controls before WordPiece splitting`() {
        val vocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "foobar")
            .withIndex()
            .associate { it.value to it.index }

        val (ids, mask) = WordPieceTokenizer.encode(
            text = "foo\u200Bbar",
            vocab = vocab,
            maxSequenceLength = 4,
            lowerCase = true,
            stripAccents = true,
        )

        assertArrayEquals(intArrayOf(2, 4, 3, 0), ids)
        assertArrayEquals(intArrayOf(1, 1, 1, 0), mask)
    }

    @Test
    fun `supplementary CJK ideographs split and encode by Unicode code point`() {
        val first = String(Character.toChars(0x20000))
        val second = String(Character.toChars(0x20001))
        val vocab = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", first, second)
            .withIndex()
            .associate { it.value to it.index }

        val (ids, mask) = WordPieceTokenizer.encode(
            text = first + second,
            vocab = vocab,
            maxSequenceLength = 5,
            lowerCase = true,
            stripAccents = true,
        )

        assertArrayEquals(intArrayOf(2, 4, 5, 3, 0), ids)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 0), mask)
    }

}
