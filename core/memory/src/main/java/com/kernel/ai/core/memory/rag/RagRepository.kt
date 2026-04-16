package com.kernel.ai.core.memory.rag

import android.util.Log
import com.kernel.ai.core.inference.ContextWindowManager
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.MemorySearchResult
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
 *   merging core memories, distilled episodic conversation summaries, and per-message
 *   episodic memories.
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
    private val episodicMemoryDao: EpisodicMemoryDao,
) {
    companion object {
        private const val TAG = "RagRepository"
        private const val TABLE = "message_embeddings"
        private const val DEFAULT_TOP_K = 3
        /** Minimum message content length to surface in search results.
         *  Prevents short conversational acknowledgements ("Choice bro", "The above")
         *  from polluting search_memory responses. */
        private const val MIN_MESSAGE_CONTENT_LENGTH = 20

        /** Maximum L2 distance to include a result (0 = identical, sqrt(2) ≈ 1.41 = opposite for unit vectors).
         *  0.90 ≈ cos_sim ≥ 0.595 for unit-normalized 768-dim vectors: L2 = sqrt(2 * (1 - cos_sim)).
         *  Tighter than core/episodic thresholds (1.10) because message history retrieval uses
         *  verbatim text that is lexically closer to queries — a tighter floor filters low-relevance
         *  historical messages and reduces token cost without losing genuinely relevant context. */
        private const val MAX_DISTANCE = 0.90f

        /** Sibling expansion caps for searchCoreAndEpisodic. */
        private const val SIBLING_MAX_PER_CONVERSATION = 3
        private const val SIBLING_MAX_CONVERSATIONS = 2
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
     * @param conversationId Only message history from this conversation is
     *   considered for the [Message History] section, preventing messages from
     *   unrelated conversations from leaking in. Episodic memories (distilled
     *   conversation summaries) are cross-conversation by design.
     * @param excludeMessageIds Message IDs to exclude (e.g. the current turn's user message).
     * @param maxTokens Maximum token budget for the returned context block (estimated at chars/3).
     *   Results are truncated to fit within the budget. Scaled to the active context window via
     *   [ContextWindowManager.episodicBudget] — callers should pass that value explicitly.
     */
    suspend fun getRelevantContext(
        query: String,
        conversationId: String,
        topK: Int = DEFAULT_TOP_K,
        excludeMessageIds: Set<String> = emptySet(),
        maxTokens: Int = ContextWindowManager.episodicBudget(4096),
    ): String = withContext(Dispatchers.IO) {
        val queryVector = embeddingEngine.embed(query)
        if (queryVector.isEmpty()) return@withContext ""

        val charsPerToken = 3
        // Reserve tokens for the framing header that wraps the entire RAG block.
        val framingHeader = "The following context has been retrieved from memory. " +
            "Use it to inform your response where relevant — do not repeat it verbatim.\n\n"
        val framingTokenCost = (framingHeader.length + charsPerToken - 1) / charsPerToken
        var tokenBudgetRemaining = maxTokens - framingTokenCost

        // --- Core + Distilled Episodic Memories ---
        val coreMemoryLines = mutableListOf<String>()
        val distilledMemoryLines = mutableListOf<String>()
        runCatching {
            val allMemoryResults = memoryRepository.searchMemories(queryVector, coreTopK = 10, episodicTopK = 3)
            val coreResults = allMemoryResults
                .filter { it.source == "core" }
                // Primary: semantic relevance. Secondary: recency (recently-accessed facts win ties).
                .sortedWith(compareByDescending<MemorySearchResult> { it.score }.thenByDescending { it.lastAccessedAt })
            val distilledResults = allMemoryResults
                .filter { it.source == "episodic" }
                .sortedByDescending { it.score }

            val coreHeader = "[Core Memories — permanent facts about the user]\n"
            val coreFooter = "[End of core memories]"
            val coreOverhead = (coreHeader.length + coreFooter.length + charsPerToken - 1) / charsPerToken
            var coreBudget = tokenBudgetRemaining - coreOverhead
            for (result in coreResults) {
                val line = result.content.take(300)
                val cost = (line.length + 1 + charsPerToken - 1) / charsPerToken
                if (coreBudget - cost < 0) break
                coreMemoryLines.add(line)
                coreBudget -= cost
            }
            if (coreMemoryLines.isNotEmpty()) {
                tokenBudgetRemaining = coreBudget
            }

            val distilledHeader = "[Episodic Memories — summaries of past conversations]\n"
            val distilledFooter = "[End of episodic memories]"
            val distilledOverhead = (distilledHeader.length + distilledFooter.length + charsPerToken - 1) / charsPerToken
            var distilledBudget = tokenBudgetRemaining - distilledOverhead
            for (result in distilledResults) {
                val prefix = result.conversationId?.let { "conversation:$it | " } ?: ""
                val line = "$prefix${result.content.take(400)}"
                val cost = (line.length + 1 + charsPerToken - 1) / charsPerToken
                if (distilledBudget - cost < 0) break
                distilledMemoryLines.add(line)
                distilledBudget -= cost
            }
            if (distilledMemoryLines.isNotEmpty()) {
                tokenBudgetRemaining = distilledBudget
            }
        }.onFailure { Log.w(TAG, "Core/distilled memory retrieval failed: ${it.message}") }

        // --- Episodic (message) Memories ---
        val episodicLines = mutableListOf<String>()
        if (tableCreated) {
            runCatching {
                val results = vectorStore.search(TABLE, queryVector, topK + excludeMessageIds.size)
                Log.d(TAG, "Message vec search: ${results.size} raw results, distances=${results.map { "%.4f".format(it.distance) }}")
                val candidates = results
                    .filter { it.distance <= MAX_DISTANCE }
                    .map { it.rowId }

                if (candidates.isNotEmpty()) {
                    val filteredEntities = embeddingDao.getByRowIdsForConversation(candidates, conversationId)
                        .filter { it.messageId !in excludeMessageIds }
                        .sortedBy { candidates.indexOf(it.rowId) }
                        .take(topK)

                    val fetchedMsgIds = filteredEntities.map { it.messageId }
                    val fetchedMsgMap = messageDao.getByIds(fetchedMsgIds).associateBy { it.id }
                    val messages = filteredEntities.mapNotNull { fetchedMsgMap[it.messageId] }

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

        if (coreMemoryLines.isEmpty() && distilledMemoryLines.isEmpty() && episodicLines.isEmpty()) return@withContext ""

        buildString {
            append(framingHeader)
            if (coreMemoryLines.isNotEmpty()) {
                append("[Core Memories — permanent facts about the user]\n")
                coreMemoryLines.forEach { appendLine(it) }
                append("[End of core memories]")
            }
            if (distilledMemoryLines.isNotEmpty()) {
                if (coreMemoryLines.isNotEmpty()) append("\n\n")
                append("[Episodic Memories — summaries of past conversations]\n")
                distilledMemoryLines.forEach { appendLine(it) }
                append("[End of episodic memories]")
            }
            if (episodicLines.isNotEmpty()) {
                if (coreMemoryLines.isNotEmpty() || distilledMemoryLines.isNotEmpty()) append("\n\n")
                append("[Message History — earlier in this conversation]\n")
                episodicLines.forEach { appendLine(it) }
                append("[End of message history]")
            }
        }
    }

    /**
     * Search [message_embeddings] for messages semantically similar to [query].
     *
     * @param query Natural-language search query to embed and compare against stored messages.
     * @param conversationId When provided, restricts results to that conversation (summary-to-detail
     *   path). When null, searches across all conversations (cross-conversation recall).
     * @param topK Maximum number of results to return.
     * @return List of matching messages with role, content, and conversationId; empty if none found
     *   or if the embedding engine is not ready.
     */
    suspend fun searchMessages(
        query: String,
        conversationId: String? = null,
        topK: Int = DEFAULT_TOP_K,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val queryVector = embeddingEngine.embed(query)
        if (queryVector.isEmpty()) return@withContext emptyList()
        if (!tableCreated) return@withContext emptyList()

        runCatching {
            val rawResults = vectorStore.search(TABLE, queryVector, topK * 2)
                .filter { it.distance <= MAX_DISTANCE }
                .map { it.rowId }
            if (rawResults.isEmpty()) return@runCatching emptyList()

            val entities = if (conversationId != null) {
                embeddingDao.getByRowIdsForConversation(rawResults, conversationId)
            } else {
                embeddingDao.getByRowIds(rawResults)
            }
                .sortedBy { rawResults.indexOf(it.rowId) }
                .take(topK)

            val messageIds = entities.map { it.messageId }
            val fetchedMessages = messageDao.getByIds(messageIds).associateBy { it.id }

            entities.mapNotNull { entity ->
                val msg = fetchedMessages[entity.messageId] ?: return@mapNotNull null
                if (msg.content.length < MIN_MESSAGE_CONTENT_LENGTH) return@mapNotNull null
                MessageSearchResult(
                    role = msg.role,
                    content = msg.content,
                    conversationId = entity.conversationId,
                    timestamp = msg.timestamp,
                )
            }
        }.getOrElse {
            Log.w(TAG, "searchMessages failed: ${it.message}")
            emptyList()
        }
    }

    /**
     * Search [core_memories_vec] and [episodic_memories_vec] for memories semantically
     * similar to [query]. This is the path used by [SearchMemorySkill] to surface facts
     * that were explicitly saved via `save_memory`.
     *
     * @param query Natural-language search query.
     * @param topK Maximum results per tier (core and episodic each contribute up to this many).
     * @return Combined list of matching memories; empty if the embedding engine is not ready
     *   or no results pass the relevance threshold.
     */
    suspend fun searchCoreAndEpisodic(
        query: String,
        topK: Int = DEFAULT_TOP_K,
    ): List<MemorySearchResult> = withContext(Dispatchers.IO) {
        val queryVector = embeddingEngine.embed(query)
        if (queryVector.isEmpty()) return@withContext emptyList()
        runCatching {
            val initialResults = memoryRepository.searchMemories(
                queryVector = queryVector,
                coreTopK = topK,
                episodicTopK = topK,
            )

            // Sibling expansion: fetch all episodic entries from the same conversations as
            // the initial episodic hits, capped to avoid bloating the result set.
            val episodicHitConversationIds = initialResults
                .filter { it.source == "episodic" && it.conversationId != null }
                .mapNotNull { it.conversationId }
                .distinct()
                .take(SIBLING_MAX_CONVERSATIONS)

            if (episodicHitConversationIds.isEmpty()) return@runCatching initialResults

            val alreadyReturnedIds = initialResults.map { it.id }.toSet()
            val siblings = episodicMemoryDao.getByConversationIds(episodicHitConversationIds)
                .filter { it.id !in alreadyReturnedIds }
                .groupBy { it.conversationId }
                .flatMap { (_, entries) -> entries.take(SIBLING_MAX_PER_CONVERSATION) }
                .map { entity ->
                    MemorySearchResult(
                        id = entity.id,
                        content = entity.content,
                        source = "episodic",
                        score = -0.5f, // below all initial hits (floor is ~-0.10f at max distance)
                        lastAccessedAt = entity.lastAccessedAt,
                        conversationId = entity.conversationId,
                    )
                }

            Log.d(TAG, "searchCoreAndEpisodic: ${initialResults.size} initial + ${siblings.size} siblings from ${episodicHitConversationIds.size} conversation(s)")
            initialResults + siblings
        }.getOrElse {
            Log.w(TAG, "searchCoreAndEpisodic failed: ${it.message}")
            emptyList()
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
