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
    fun `filter drops weak semantic neighbours without lexical overlap`() {
        val filtered = filterSearchMemoryResults(
            query = "What's my favourite coffee?",
            memoryResults = listOf(
                MemorySearchResult(
                    id = "unrelated",
                    content = "The user's preferred cleaner is Star Dry Cleaning.",
                    source = "core",
                    score = 0.25f,
                ),
            ),
            messageResults = emptyList(),
        )

        assertTrue(filtered.memoryResults.isEmpty())
    }

    @Test
    fun `preference recall keeps exact topic with realistic moderate embedding score`() {
        val filtered = filterSearchMemoryResults(
            query = "What's my favourite coffee?",
            memoryResults = listOf(
                MemorySearchResult(
                    id = "coffee",
                    content = "my favourite coffee is a flat white",
                    source = "core",
                    score = 0.445f,
                ),
            ),
            messageResults = emptyList(),
        )

        assertEquals(1, filtered.memoryResults.size)
        assertEquals("coffee", filtered.memoryResults.single().id)
    }

    @Test
    fun `preference recall excludes weakly similar different preferences`() {
        val filtered = filterSearchMemoryResults(
            query = "What's my favourite tea?",
            memoryResults = listOf(
                MemorySearchResult(
                    id = "coffee",
                    content = "my favourite coffee is a flat white",
                    source = "core",
                    score = 0.445f,
                ),
            ),
            messageResults = emptyList(),
        )

        assertTrue(filtered.memoryResults.isEmpty())
    }

    @Test
    fun `three letter preference topic retains exact fact at moderate score`() {
        val filtered = filterSearchMemoryResults(
            query = "What's my favourite tea?",
            memoryResults = listOf(
                MemorySearchResult(
                    id = "tea",
                    content = "my favourite tea is earl grey",
                    source = "core",
                    score = 0.445f,
                ),
            ),
            messageResults = emptyList(),
        )

        assertEquals(1, filtered.memoryResults.size)
        assertEquals("tea", filtered.memoryResults.single().id)
    }

    @Test
    fun `whats token does not create lexical overlap for unrelated memory`() {
        val filtered = filterSearchMemoryResults(
            query = "What's my favourite coffee?",
            memoryResults = listOf(
                MemorySearchResult(
                    id = "unrelated",
                    content = "I remember what's happening this afternoon",
                    source = "core",
                    score = 0.25f,
                ),
            ),
            messageResults = emptyList(),
        )

        assertTrue(filtered.memoryResults.isEmpty())
    }

    @Test
    fun `tea does not lexically overlap steak`() {
        val filtered = filterSearchMemoryResults(
            query = "What's my favourite tea?",
            memoryResults = listOf(
                MemorySearchResult(
                    id = "steak",
                    content = "My favourite steak is medium rare",
                    source = "core",
                    score = 0.25f,
                ),
            ),
            messageResults = emptyList(),
        )

        assertTrue(filtered.memoryResults.isEmpty())
    }

    private fun keeps(query: String, content: String): Boolean = filterSearchMemoryResults(
        query = query,
        memoryResults = listOf(MemorySearchResult(id = "m", content = content, source = "core", score = 0.25f)),
        messageResults = emptyList(),
    ).memoryResults.isNotEmpty()

    @Test
    fun `singular query matches plural or possessive stored words at a weak score`() {
        assertTrue(keeps("What's my favourite movie?", "My favourite movies are Alien and Heat"))
        assertTrue(keeps("What's my favourite story?", "My favourite stories are by Katherine Mansfield"))
        assertTrue(keeps("What's my dog called?", "My dog's name is Rua"))
        assertTrue(keeps("What are my favourite movies?", "My favourite movie is Heat"))
        assertTrue(keeps("What are my favourite stories?", "My favourite story is The Garden Party"))
        assertTrue(keeps("What do my dogs eat?", "My dog eats biscuits"))
        assertTrue(keeps("What's in my lunch boxes?", "My lunch box has sushi"))
    }

    @Test
    fun `plural endings do not reopen substring matches`() {
        assertTrue(!keeps("What's my favourite tea?", "My favourite steaks are rare"))
        assertTrue(!keeps("What's my favourite tea?", "I teased my brother"))
        assertTrue(!keeps("What's my favourite car?", "My favourite cards are aces"))
        // "-es" only after s/x/z/ch/sh/o stems; singular forms are re-checked against stopwords.
        assertTrue(!keeps("What's my favourite car?", "Nobody cares about traffic"))
        assertTrue(!keeps("Which hat do I wear?", "She hates rain"))
        assertTrue(!keeps("What's my plan?", "The planes were late"))
        assertTrue(!keeps("What's my news?", "A new kettle"))
        assertTrue(keeps("What's in my lunch box?", "My lunch boxes are blue"))
        assertTrue(keeps("When does my bus come?", "The buses leave at 7"))
    }

    @Test
    fun `our does not lexically overlap favourite`() {
        val filtered = filterSearchMemoryResults(
            query = "What did we discuss about our holiday?",
            memoryResults = listOf(
                MemorySearchResult(
                    id = "coffee",
                    content = "My favourite coffee is a flat white",
                    source = "core",
                    score = 0.25f,
                ),
            ),
            messageResults = emptyList(),
        )

        assertTrue(filtered.memoryResults.isEmpty())
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
