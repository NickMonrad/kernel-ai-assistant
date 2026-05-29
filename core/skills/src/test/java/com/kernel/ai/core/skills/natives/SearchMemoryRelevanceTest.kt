package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.memory.rag.MessageSearchResult
import com.kernel.ai.core.memory.repository.MemorySearchResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchMemoryRelevanceTest {
    @Test
    fun `filter drops weak unrelated neighbours`() {
        val filtered = filterSearchMemoryResults(
            query = "Can you search memory to see if I like aubergines",
            memoryResults = listOf(
                MemorySearchResult(id = "1", content = "Nick likes dark mode", source = "core", score = 0.04f),
                MemorySearchResult(id = "2", content = "Nick is vegetarian", source = "core", score = 0.08f),
            ),
            messageResults = listOf(
                MessageSearchResult(
                    role = "assistant",
                    content = "We talked about pancakes last week",
                    conversationId = "conv-1",
                    timestamp = 1L,
                    score = 0.05f,
                ),
            ),
        )

        assertTrue(filtered.memoryResults.isEmpty())
        assertTrue(filtered.messageResults.isEmpty())
    }

    @Test
    fun `filter keeps lexical matches even when score is weak`() {
        val filtered = filterSearchMemoryResults(
            query = "What do you remember about my family",
            memoryResults = listOf(
                MemorySearchResult(id = "1", content = "Nick's family lives in Brisbane", source = "core", score = 0.03f),
            ),
            messageResults = emptyList(),
        )

        assertEquals(1, filtered.memoryResults.size)
    }

    @Test
    fun `filter keeps high confidence matches without lexical overlap`() {
        val filtered = filterSearchMemoryResults(
            query = "What do you remember about my family",
            memoryResults = listOf(
                MemorySearchResult(id = "2", content = "Nick prefers dark mode", source = "core", score = 0.34f),
            ),
            messageResults = listOf(
                MessageSearchResult(
                    role = "user",
                    content = "My sister moved to Melbourne",
                    conversationId = "conv-2",
                    timestamp = 2L,
                    score = 0.27f,
                ),
            ),
        )

        assertEquals(1, filtered.memoryResults.size)
        assertEquals(1, filtered.messageResults.size)
    }

    @Test
    fun `filter drops results when query is anaphoric save`() {
        val filtered = filterSearchMemoryResults(
            query = "remember that",
            memoryResults = listOf(
                MemorySearchResult(id = "1", content = "Nick is vegetarian", source = "core", score = 0.1f),
            ),
            messageResults = emptyList(),
        )

        // Anaphoric save queries should not trigger memory search
        assertTrue(filtered.memoryResults.isEmpty())
    }

    @Test
    fun `filter drops results when query is a bare tool confirmation`() {
        val filtered = filterSearchMemoryResults(
            query = "done!",
            memoryResults = listOf(
                MemorySearchResult(id = "1", content = "alarm set for 7am", source = "core", score = 0.12f),
            ),
            messageResults = emptyList(),
        )

        assertTrue(filtered.memoryResults.isEmpty())
    }

    @Test
    fun `filter keeps results for legitimate keyword queries`() {
        val filtered = filterSearchMemoryResults(
            query = "what's on my shopping list",
            memoryResults = listOf(
                MemorySearchResult(id = "1", content = "Shopping list: milk, eggs, bread", source = "core", score = 0.15f),
            ),
            messageResults = emptyList(),
        )

        assertEquals(1, filtered.memoryResults.size)
    }

    @Test
    fun `filter handles empty results gracefully`() {
        val filtered = filterSearchMemoryResults(
            query = "test query",
            memoryResults = emptyList(),
            messageResults = emptyList(),
        )

        assertTrue(filtered.memoryResults.isEmpty())
        assertTrue(filtered.messageResults.isEmpty())
    }
}
