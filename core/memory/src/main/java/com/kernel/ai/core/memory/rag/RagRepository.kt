package com.kernel.ai.core.memory.rag

import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
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
 * - Format retrieved context into a prefix that can be prepended to the user prompt.
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
) {
    companion object {
        private const val TAG = "RagRepository"
        private const val TABLE = "message_embeddings"
        private const val DEFAULT_TOP_K = 5

        /** Maximum cosine distance to include a result (0 = identical, 2 = opposite). */
        private const val MAX_DISTANCE = 1.0f
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
     * Find the [topK] most semantically relevant past messages for [query].
     * Returns a formatted context block ready to prepend to a prompt, or an
     * empty string when no relevant context is available.
     *
     * @param excludeMessageIds Message IDs to exclude (e.g. the current turn's user message).
     * @param maxTokens Maximum token budget for the returned context block (estimated at chars/3).
     *   Results are truncated to fit within the budget. Defaults to [Int.MAX_VALUE] (no limit).
     */
    suspend fun getRelevantContext(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        excludeMessageIds: Set<String> = emptySet(),
        maxTokens: Int = Int.MAX_VALUE,
    ): String = withContext(Dispatchers.IO) {
        if (!tableCreated) return@withContext ""

        val queryVector = embeddingEngine.embed(query)
        if (queryVector.isEmpty()) return@withContext ""

        val results = vectorStore.search(TABLE, queryVector, topK + excludeMessageIds.size)
        if (results.isEmpty()) return@withContext ""

        val candidateRowIds = results
            .filter { it.distance <= MAX_DISTANCE }
            .map { it.rowId }

        if (candidateRowIds.isEmpty()) return@withContext ""

        val embeddingEntities = embeddingDao.getByRowIds(candidateRowIds)
        val filteredEntities = embeddingEntities
            .filter { it.messageId !in excludeMessageIds }
            .sortedBy { candidateRowIds.indexOf(it.rowId) }
            .take(topK)

        if (filteredEntities.isEmpty()) return@withContext ""

        // Fetch full message content, preserving relevance order.
        val messages = filteredEntities.mapNotNull { entity ->
            messageDao.getByConversation(entity.conversationId)
                .firstOrNull { it.id == entity.messageId }
        }

        if (messages.isEmpty()) return@withContext ""

        buildString {
            // Estimate token cost of all lines before committing to include them.
            val charsPerToken = 3
            var tokenBudgetRemaining = maxTokens
            val header = "[Episodic Memories]\n"
            val footer = "[End of episodic memories]"
            tokenBudgetRemaining -= (header.length + footer.length) / charsPerToken

            val lines = mutableListOf<String>()
            for (msg in messages) {
                val role = if (msg.role == "user") "User" else "Assistant"
                val line = "$role: ${msg.content.take(300)}"
                val lineCost = (line.length + 1) / charsPerToken // +1 for newline
                if (tokenBudgetRemaining - lineCost < 0) break
                lines.add(line)
                tokenBudgetRemaining -= lineCost
            }

            if (lines.isEmpty()) return@withContext ""

            append(header)
            lines.forEach { appendLine(it) }
            append(footer)
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
