package com.kernel.ai.debug.tts.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KokoroTtsPartitionTest {
    private val limit = KokoroTts.MAX_TOKENS - 2

    @Test
    fun `chunks rejoin to the input minus whitespace and each fits the token limit`() {
        // Sentences, a pause mark, an unbroken run longer than the limit, and a supplementary-plane character.
        val text = "həloʊ wɜːld, ðɪs ɪz ə tɛst. " +
            "aɪ".repeat(60) + "! " +
            "x".repeat(300) + " " +
            "\uD835\uDD18".repeat(80) + "?"

        val chunks = KokoroTts.partition(text, limit)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= limit }) { "chunk lengths: ${chunks.map { it.length }}" }
        assertEquals(text.filterNot(Char::isWhitespace), chunks.joinToString("") { it.filterNot(Char::isWhitespace) })
    }

    @Test
    fun `text within the limit is returned as one trimmed chunk`() {
        assertEquals(listOf("short line."), KokoroTts.partition("  short line.  ", limit))
    }
}
