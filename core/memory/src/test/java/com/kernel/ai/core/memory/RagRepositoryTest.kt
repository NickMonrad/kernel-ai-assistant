package com.kernel.ai.core.memory

import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import com.kernel.ai.core.memory.entity.MessageEntity
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.MemorySearchResult
import com.kernel.ai.core.memory.vector.VectorSearchResult
import com.kernel.ai.core.memory.vector.VectorStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class RagRepositoryTest {

    private val embeddingEngine: EmbeddingEngine = mockk()
    private val vectorStore: VectorStore = mockk()
    private val messageDao: MessageDao = mockk()
    private val embeddingDao: MessageEmbeddingDao = mockk()
    private val memoryRepository: MemoryRepository = mockk()
    private val episodicMemoryDao: EpisodicMemoryDao = mockk()

    private lateinit var ragRepository: RagRepository

    @BeforeEach
    fun setUp() {
        ragRepository = RagRepository(embeddingEngine, vectorStore, messageDao, embeddingDao, memoryRepository, episodicMemoryDao)
    }

    /**
     * Primes RagRepository's internal `tableCreated` flag by running a successful
     * indexMessage call. This is required for the [Episodic Memories] section to be
     * populated in getRelevantContext.
     */
    private suspend fun primeEpisodicTable(vector: FloatArray = floatArrayOf(0.5f, 0.5f, 0.0f)) {
        coEvery { embeddingDao.getRowIdForMessage(any()) } returns null
        coEvery { embeddingEngine.embed(any()) } returns vector
        every { vectorStore.createTable(any(), any()) } just Runs
        coEvery { embeddingDao.insert(any()) } returns 1L
        every { vectorStore.upsert(any(), any(), any()) } just Runs

        ragRepository.indexMessage("prime-msg", "prime-conv", "priming content")
    }

    // ─────────────────────────────── getRelevantContext ──────────────────────────────

    @Test
    fun `getRelevantContext — returns empty string when embedding engine returns empty array`() = runTest {
        coEvery { embeddingEngine.embed(any()) } returns FloatArray(0)

        val result = ragRepository.getRelevantContext("any query", conversationId = "test-conv")

        assertEquals("", result)
    }

    @Test
    fun `getRelevantContext — returns only Core Memories section when core results exist and no episodic indexed`() = runTest {
        val queryVector = floatArrayOf(1.0f, 0.0f, 0.0f)
        coEvery { embeddingEngine.embed(any()) } returns queryVector

        // Memory tier: one core result — episodic table NOT created so no [Episodic Memories]
        coEvery { memoryRepository.searchMemories(any(), any(), any(), any(), any()) } returns listOf(
            MemorySearchResult(
                id = "core-1",
                content = "User prefers dark mode",
                source = "core",
                score = 0.95f,
            )
        )

        val result = ragRepository.getRelevantContext("user preferences", conversationId = "test-conv")

        assertTrue(result.contains("[Core Memories"), "Output must contain [Core Memories] section")
        assertTrue(result.contains("User prefers dark mode"), "Output must include the core memory content")
        assertTrue(!result.contains("[Episodic Memories"), "Output must NOT contain [Episodic Memories] when table not initialised")
        assertTrue(result.startsWith("The following context has been retrieved from memory."), "Output must start with framing instruction")

        // Verify the retrieval caps stay tight to avoid over-injecting long-term memories.
        coVerify(exactly = 1) {
            memoryRepository.searchMemories(any(), coreTopK = 6, episodicTopK = 3, kiwiTopK = 6)
        }
    }

    @Test
    fun `getRelevantContext — includes kiwi memories in long-term memory section`() = runTest {
        val queryVector = floatArrayOf(1.0f, 0.0f, 0.0f)
        coEvery { embeddingEngine.embed(any()) } returns queryVector
        coEvery { memoryRepository.searchMemories(any(), any(), any(), any(), any()) } returns listOf(
            MemorySearchResult(
                id = "kiwi-1",
                content = "Flight of the Conchords are a New Zealand comedy duo",
                source = "kiwi",
                score = 0.96f,
                term = "Flight of the Conchords",
                definition = "A New Zealand musical comedy duo consisting of Bret McKenzie and Jemaine Clement.",
            )
        )

        val result = ragRepository.getRelevantContext("who are flight of the conchords", conversationId = "test-conv")

        assertTrue(result.contains("[Core Memories"), "Output must contain the long-term memory section")
        assertTrue(result.contains("[NZ Context: Flight of the Conchords]"), "Kiwi memory should be formatted as NZ context")
        assertTrue(result.contains("musical comedy duo"), "Kiwi definition should be injected into the prompt")
    }

    @Test
    fun `getRelevantContext — core memories appear before episodic memories in output`() = runTest {
        val sharedVector = floatArrayOf(0.5f, 0.5f, 0.0f)

        // Prime tableCreated so the episodic retrieval path is active
        primeEpisodicTable(sharedVector)

        // Subsequent calls to embed (for getRelevantContext) reuse the same vector
        coEvery { embeddingEngine.embed(any()) } returns sharedVector

        // Memory tier: core only (episodic from searchMemories is NOT rendered)
        coEvery { memoryRepository.searchMemories(any(), any(), any(), any(), any()) } returns listOf(
            MemorySearchResult(id = "core-1", content = "Core preference fact", source = "core", score = 0.9f),
        )

        // Message history — comes from vector store search
        val embeddingEntity = MessageEmbeddingEntity(rowId = 1L, messageId = "msg-1", conversationId = "conv-1")
        val messageEntity = MessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "user",
            content = "Hello from message history",
            thinkingText = null,
            timestamp = System.currentTimeMillis(),
        )
        every { vectorStore.search(any(), any(), any()) } returns listOf(VectorSearchResult(rowId = 1L, distance = 0.1f))
        coEvery { embeddingDao.getByRowIdsForConversation(any(), any()) } returns listOf(embeddingEntity)
        coEvery { messageDao.getByIds(any()) } returns listOf(messageEntity)

        val result = ragRepository.getRelevantContext("tell me about preferences", conversationId = "conv-1")

        assertTrue(result.startsWith("The following context has been retrieved from memory."), "Output must start with framing instruction")
        assertTrue(result.contains("[Core Memories"), "Output must contain [Core Memories]")
        assertTrue(result.contains("[Message History"), "Output must contain [Message History]")
        assertTrue(result.contains("conversation"), "Message history header must clarify source as conversation")

        val framingIndex = result.indexOf("The following context")
        val coreIndex = result.indexOf("[Core Memories")
        val historyIndex = result.indexOf("[Message History")
        assertTrue(framingIndex < coreIndex, "Framing must appear before [Core Memories]")
        assertTrue(coreIndex < historyIndex, "[Core Memories] must appear before [Message History]")
    }

    @Test
    fun `getRelevantContext — returns empty string when both memory tiers return no results`() = runTest {
        val queryVector = floatArrayOf(0.1f, 0.2f, 0.3f)
        coEvery { embeddingEngine.embed(any()) } returns queryVector

        // No memory results from either tier
        coEvery { memoryRepository.searchMemories(any(), any(), any(), any(), any()) } returns emptyList()

        // Note: tableCreated is false so vectorStore.search is NOT called;
        // both sections are empty → result is ""

        val result = ragRepository.getRelevantContext("unknown topic", conversationId = "test-conv")

        assertEquals("", result, "Should return empty string when both tiers are empty")
    }

    @Test
    fun `getRelevantContext excludes messages from other conversations`() = runTest {
        val sharedVector = floatArrayOf(0.7f, 0.3f, 0.0f)

        // Prime tableCreated so the episodic retrieval path is active
        primeEpisodicTable(sharedVector)

        coEvery { embeddingEngine.embed(any()) } returns sharedVector
        coEvery { memoryRepository.searchMemories(any(), any(), any(), any(), any()) } returns emptyList()

        // Vector search returns two candidates — one from each conversation
        every { vectorStore.search(any(), any(), any()) } returns listOf(
            VectorSearchResult(rowId = 10L, distance = 0.05f),
            VectorSearchResult(rowId = 20L, distance = 0.10f),
        )

        // SQL-level filter: only conv-1 entity is returned when queried for conv-1
        val conv1Entity = MessageEmbeddingEntity(rowId = 10L, messageId = "msg-conv1", conversationId = "conv-1")
        coEvery { embeddingDao.getByRowIdsForConversation(any(), any()) } returns listOf(conv1Entity)

        val conv1Message = MessageEntity(
            id = "msg-conv1",
            conversationId = "conv-1",
            role = "user",
            content = "This message belongs to conv-1",
            thinkingText = null,
            timestamp = System.currentTimeMillis(),
        )
        coEvery { messageDao.getByIds(any()) } returns listOf(conv1Message)

        val result = ragRepository.getRelevantContext("some query", conversationId = "conv-1")

        assertTrue(result.contains("This message belongs to conv-1"), "conv-1 content must appear in results")
        assertTrue(!result.contains("conv-2"), "conv-2 content must be absent from results scoped to conv-1")
    }

    @Test
    fun `getRelevantContext — core memory failure is swallowed gracefully and returns episodic only`() = runTest {
        val sharedVector = floatArrayOf(0.8f, 0.2f, 0.0f)

        // Prime tableCreated
        primeEpisodicTable(sharedVector)

        coEvery { embeddingEngine.embed(any()) } returns sharedVector

        // Memory tier throws — should be caught, not propagated
        coEvery { memoryRepository.searchMemories(any(), any(), any(), any(), any()) } throws RuntimeException("Memory DB failure")

        // Message history is still available
        val embeddingEntity = MessageEmbeddingEntity(rowId = 2L, messageId = "msg-2", conversationId = "conv-2")
        val messageEntity = MessageEntity(
            id = "msg-2",
            conversationId = "conv-2",
            role = "user",
            content = "A message from episodic history",
            thinkingText = null,
            timestamp = System.currentTimeMillis(),
        )
        every { vectorStore.search(any(), any(), any()) } returns listOf(VectorSearchResult(rowId = 2L, distance = 0.15f))
        coEvery { embeddingDao.getByRowIdsForConversation(any(), any()) } returns listOf(embeddingEntity)
        coEvery { messageDao.getByIds(any()) } returns listOf(messageEntity)

        // Must not throw
        val result = ragRepository.getRelevantContext("what did the user say earlier", conversationId = "conv-2")

        assertTrue(!result.contains("[Core Memories"), "Failed core search must not produce a [Core Memories] section")
        assertTrue(result.contains("[Message History"), "Message history must still appear despite core failure")
    }

    @Test
    fun `getRelevantContext — lastAccessedAt tiebreaker orders memories with equal score by recency`() = runTest {
        val queryVector = floatArrayOf(1.0f, 0.0f, 0.0f)
        coEvery { embeddingEngine.embed(any()) } returns queryVector

        // Two memories with identical score — stale predates recent by access time.
        // Budget is deliberately tight (≈30 tokens) so only one fits.
        coEvery { memoryRepository.searchMemories(any(), any(), any(), any(), any()) } returns listOf(
            MemorySearchResult(id = "stale",  content = "Stale fact",  source = "core", score = 0.9f, lastAccessedAt = 1_000L),
            MemorySearchResult(id = "recent", content = "Recent fact", source = "core", score = 0.9f, lastAccessedAt = 9_000L),
        )

        val result = ragRepository.getRelevantContext("query", conversationId = "c", maxTokens = 75)

        assertTrue(result.contains("Recent fact"), "Higher lastAccessedAt must win the tiebreak and appear in output")
        assertFalse(result.contains("Stale fact"), "Lower lastAccessedAt must be truncated when budget is tight")
    }

    @Test
    fun `getRelevantContext — caps long-term memory lines across core and kiwi`() = runTest {
        val queryVector = floatArrayOf(1.0f, 0.0f, 0.0f)
        coEvery { embeddingEngine.embed(any()) } returns queryVector
        coEvery { memoryRepository.searchMemories(any(), any(), any(), any(), any()) } returns (1..8).map { index ->
            MemorySearchResult(
                id = "memory-$index",
                content = "Memory $index",
                source = if (index <= 4) "core" else "kiwi",
                score = 1.0f - (index * 0.01f),
                lastAccessedAt = (10_000 - index).toLong(),
                term = if (index > 4) "Kiwi $index" else "",
                definition = if (index > 4) "Definition $index" else "",
            )
        }

        val result = ragRepository.getRelevantContext("query", conversationId = "c", maxTokens = 500)

        assertTrue(result.contains("Memory 1"), "Top-ranked core memories should still be included")
        assertTrue(result.contains("[NZ Context: Kiwi 5]"), "Top-ranked kiwi memories should be eligible for inclusion")
        assertFalse(result.contains("Definition 8"), "Long-term memory injection must be capped to protect context budget")
    }
}
