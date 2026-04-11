package com.kernel.ai.core.memory

import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.dao.MessageDao
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

    private lateinit var ragRepository: RagRepository

    @BeforeEach
    fun setUp() {
        ragRepository = RagRepository(embeddingEngine, vectorStore, messageDao, embeddingDao, memoryRepository)
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
        coEvery { memoryRepository.searchMemories(any(), any(), any()) } returns listOf(
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

        // Verify the correct topK contract — core gets 10 slots, episodic is suppressed (0)
        coVerify(exactly = 1) {
            memoryRepository.searchMemories(any(), coreTopK = 10, episodicTopK = 0)
        }
    }

    @Test
    fun `getRelevantContext — core memories appear before episodic memories in output`() = runTest {
        val sharedVector = floatArrayOf(0.5f, 0.5f, 0.0f)

        // Prime tableCreated so the episodic retrieval path is active
        primeEpisodicTable(sharedVector)

        // Subsequent calls to embed (for getRelevantContext) reuse the same vector
        coEvery { embeddingEngine.embed(any()) } returns sharedVector

        // Memory tier: core only (episodic from searchMemories is NOT rendered)
        coEvery { memoryRepository.searchMemories(any(), any(), any()) } returns listOf(
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
        coEvery { messageDao.getByConversation(any()) } returns listOf(messageEntity)

        val result = ragRepository.getRelevantContext("tell me about preferences", conversationId = "conv-1")

        assertTrue(result.startsWith("The following context has been retrieved from memory."), "Output must start with framing instruction")
        assertTrue(result.contains("[Core Memories"), "Output must contain [Core Memories]")
        assertTrue(result.contains("[Episodic Memories"), "Output must contain [Episodic Memories]")
        assertTrue(result.contains("past conversation"), "Episodic header must clarify source as past conversation")

        val framingIndex = result.indexOf("The following context")
        val coreIndex = result.indexOf("[Core Memories")
        val episodicIndex = result.indexOf("[Episodic Memories")
        assertTrue(framingIndex < coreIndex, "Framing must appear before [Core Memories]")
        assertTrue(coreIndex < episodicIndex, "[Core Memories] must appear before [Episodic Memories]")
    }

    @Test
    fun `getRelevantContext — returns empty string when both memory tiers return no results`() = runTest {
        val queryVector = floatArrayOf(0.1f, 0.2f, 0.3f)
        coEvery { embeddingEngine.embed(any()) } returns queryVector

        // No memory results from either tier
        coEvery { memoryRepository.searchMemories(any(), any(), any()) } returns emptyList()

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
        coEvery { memoryRepository.searchMemories(any(), any(), any()) } returns emptyList()

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
        coEvery { messageDao.getByConversation("conv-1") } returns listOf(conv1Message)

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
        coEvery { memoryRepository.searchMemories(any(), any(), any()) } throws RuntimeException("Memory DB failure")

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
        coEvery { messageDao.getByConversation(any()) } returns listOf(messageEntity)

        // Must not throw
        val result = ragRepository.getRelevantContext("what did the user say earlier", conversationId = "conv-2")

        assertTrue(!result.contains("[Core Memories"), "Failed core search must not produce a [Core Memories] section")
        assertTrue(result.contains("[Episodic Memories"), "Episodic content must still appear despite core failure")
    }

    @Test
    fun `getRelevantContext — lastAccessedAt tiebreaker orders memories with equal score by recency`() = runTest {
        val queryVector = floatArrayOf(1.0f, 0.0f, 0.0f)
        coEvery { embeddingEngine.embed(any()) } returns queryVector

        // Two memories with identical score — stale predates recent by access time.
        // Budget is deliberately tight (≈30 tokens) so only one fits.
        coEvery { memoryRepository.searchMemories(any(), any(), any()) } returns listOf(
            MemorySearchResult(id = "stale",  content = "Stale fact",  source = "core", score = 0.9f, lastAccessedAt = 1_000L),
            MemorySearchResult(id = "recent", content = "Recent fact", source = "core", score = 0.9f, lastAccessedAt = 9_000L),
        )

        val result = ragRepository.getRelevantContext("query", conversationId = "c", maxTokens = 75)

        assertTrue(result.contains("Recent fact"), "Higher lastAccessedAt must win the tiebreak and appear in output")
        assertFalse(result.contains("Stale fact"), "Lower lastAccessedAt must be truncated when budget is tight")
    }
}
