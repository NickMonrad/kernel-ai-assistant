package com.kernel.ai.core.memory.rag

import android.util.Log
import com.kernel.ai.core.inference.ContextWindowManager
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.vector.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the RAG (Retrieval-Augmented Generation) memory pipeline.
 *
 * Responsibilities:
 * - Index each message into the sqlite-vec vector store after it is saved.
 * - Retrieve the most semantically relevant past messages for a given query.
 * - Format retrieved context into a prefix that can be prepended to the user prompt,
 *   merging both core memories and episodic (message) memories.
 *
 * The embedding table is created lazily on first use, once the [EmbeddingEngine] has
 * loaded its model and the embedding dimensions are known.
 */
@Singleton
class RagRepository @Inject constructor(
    private val embeddingEngine: EmbeddingEngine,
    private val vectorStore: VectorStore,
    private val messageDao: MessageDao,
    private val embeddingDao: MessageEmbeddingDao,
    private val memoryRepository: MemoryRepository,
) {
    companion object {
        private const val TAG = "RagRepository"
        private const val TABLE = "message_embeddings"
        private const val DEFAULT_TOP_K = 5

        /** Maximum cosine distance to include a result (0 = identical, 2 = opposite).
         *  0.4 ≈ cosine similarity 0.6 — filters out unrelated memories while keeping
         *  genuinely relevant context. Without this, top-K always returns results even
         *  when nothing in memory is actually related to the current query. */
        private const val MAX_DISTANCE = 0.4f
    }

    private var tableCreated = false

    /**
     * Embed [content] and store it in the vector index for later retrieval.
     * No-op if [content] is blank, the embedding engine is not ready, or the
     * message is already indexed.
     */
    suspend fun indexMessage(
        messageId: String,
        conversationId: String,
        content: String,
    ) = withContext(Dispatchers.IO) {
        if (content.isBlank()) return@withContext
        if (embeddingDao.getRowIdForMessage(messageId) != null) return@withContext

        val vector = embeddingEngine.embed(content)
        if (vector.isEmpty()) {
            Log.w(TAG, "Embedding engine not ready for message $messageId — skipping index")
            return@withContext
        }

        ensureTable(vector.size)

        val entity = MessageEmbeddingEntity(messageId = messageId, conversationId = conversationId)
        val rowId = embeddingDao.insert(entity)
        if (rowId > 0) {
            vectorStore.upsert(TABLE, rowId, vector)
            Log.d(TAG, "Indexed message $messageId → rowId=$rowId")
        }
    }

    /**
     * Find the [topK] most semantically relevant past messages and core memories for [query].
     * Returns a formatted context block ready to prepend to a prompt, or an
     * empty string when no relevant context is available.
     *
     * @param conversationId Only episodic (message) memories from this conversation are
     *   considered, preventing memories from unrelated conversations from leaking in.
     * @param excludeMessageIds Message IDs to exclude (e.g. the current turn's user message).
     * @param maxTokens Maximum token budget for the returned context block (estimated at chars/3).
     *   Results are truncated to fit within the budget. Defaults to [ContextWindowManager.EPISODIC_BUDGET].
     */
    suspend fun getRelevantContext(
        query: String,
        conversationId: String,
        topK: Int = DEFAULT_TOP_K,
        excludeMessageIds: Set<String> = emptySet(),
        maxTokens: Int = ContextWindowManager.EPISODIC_BUDGET,
    ): String = withContext(Dispatchers.IO) {
        val queryVector = embeddingEngine.embed(query)
        if (queryVector.isEmpty()) return@withContext ""

        val charsPerToken = 3
        // Reserve tokens for the framing header that wraps the entire RAG block.
        val framingHeader = "The following context has been retrieved from memory. " +
            "Use it to inform your response where relevant — do not repeat it verbatim.\n\n"
        val framingTokenCost = (framingHeader.length + charsPerToken - 1) / charsPerToken
        var tokenBudgetRemaining = maxTokens - framingTokenCost

        // --- Core Memories ---
        val coreMemoryLines = mutableListOf<String>()
        runCatching {
            val coreResults = memoryRepository.searchMemories(queryVector, topK = 5)
            val coreHeader = "[Core Memories]\n"
            val coreFooter = "[End of core memories]"
            val coreOverhead = (coreHeader.length + coreFooter.length + charsPerToken - 1) / charsPerToken
            var coreBudget = tokenBudgetRemaining - coreOverhead
            for (result in coreResults.filter { it.source == "core" }) {
                val line = result.content.take(300)
                val cost = (line.length + 1 + charsPerToken - 1) / charsPerToken
                if (coreBudget - cost < 0) break
                coreMemoryLines.add(line)
                coreBudget -= cost
            }
            if (coreMemoryLines.isNotEmpty()) {
                tokenBudgetRemaining = coreBudget
            }
        }.onFailure { Log.w(TAG, "Core memory retrieval failed: ${it.message}") }

        // --- Episodic (message) Memories ---
        val episodicLines = mutableListOf<String>()
        if (tableCreated) {
            runCatching {
                val results = vectorStore.search(TABLE, queryVector, topK + excludeMessageIds.size)
                val candidates = results
                    .filter { it.distance <= MAX_DISTANCE }
                    .map { it.rowId }

                if (candidates.isNotEmpty()) {
                    val filteredEntities = embeddingDao.getByRowIdsForConversation(candidates, conversationId)
                        .filter { it.messageId !in excludeMessageIds }
                        .sortedBy { candidates.indexOf(it.rowId) }
                        .take(topK)

                    val messages = filteredEntities.mapNotNull { entity ->
                        messageDao.getByConversation(entity.conversationId)
                            .firstOrNull { it.id == entity.messageId }
                    }

                    val episodicHeader = "[Episodic Memories — recalled from a past conversation]\n"
                    val episodicFooter = "[End of episodic memories]"
                    val episodicOverhead = (episodicHeader.length + episodicFooter.length + charsPerToken - 1) / charsPerToken
                    var episodicBudget = tokenBudgetRemaining - episodicOverhead

                    for (msg in messages) {
                        val role = if (msg.role == "user") "User" else "Assistant"
                        val line = "$role: ${msg.content.take(300)}"
                        val cost = (line.length + 1 + charsPerToken - 1) / charsPerToken
                        if (episodicBudget - cost < 0) break
                        episodicLines.add(line)
                        episodicBudget -= cost
                    }
                }
            }.onFailure { Log.w(TAG, "Episodic retrieval failed: ${it.message}") }
        }

        if (coreMemoryLines.isEmpty() && episodicLines.isEmpty()) return@withContext ""

        buildString {
            append(framingHeader)
            if (coreMemoryLines.isNotEmpty()) {
                append("[Core Memories — permanent facts about the user]\n")
                coreMemoryLines.forEach { appendLine(it) }
                append("[End of core memories]")
            }
            if (episodicLines.isNotEmpty()) {
                if (coreMemoryLines.isNotEmpty()) append("\n\n")
                append("[Episodic Memories — recalled from a past conversation]\n")
                episodicLines.forEach { appendLine(it) }
                append("[End of episodic memories]")
            }
        }
    }

    private fun ensureTable(dimensions: Int) {
        if (!tableCreated) {
            vectorStore.createTable(TABLE, dimensions)
            tableCreated = true
            Log.i(TAG, "Created vector table '$TABLE' with dim=$dimensions")
        }
    }
}
