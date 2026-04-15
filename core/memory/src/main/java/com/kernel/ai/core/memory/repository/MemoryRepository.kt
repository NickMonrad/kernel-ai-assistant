package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import kotlinx.coroutines.flow.Flow

interface MemoryRepository {
    /** Store a volatile, conversation-scoped memory. */
    suspend fun addEpisodicMemory(conversationId: String, content: String, embeddingVector: FloatArray): String
    /** Store a permanent cross-conversation memory. */
    suspend fun addCoreMemory(content: String, source: String = "user", embeddingVector: FloatArray, category: String = "user"): String
    /** Backfill vector for an existing core memory that was saved without one (used by MemoryEmbeddingWorker). */
    suspend fun backfillCoreVector(rowId: Long, vector: FloatArray)
    /** Backfill vector for an existing episodic memory that was saved without one (used by MemoryEmbeddingWorker). */
    suspend fun backfillEpisodicVector(rowId: Long, vector: FloatArray)
    /** Search BOTH tiers; core ranked above episodic. Agent identity memories use separate topK. */
    suspend fun searchMemories(
        queryVector: FloatArray,
        coreTopK: Int = 10,
        episodicTopK: Int = 5,
        identityTopK: Int = 2,
    ): List<MemorySearchResult>
    /** Delete a specific core memory. */
    suspend fun deleteCoreMemory(id: String)
    /** Delete all episodic memories. */
    suspend fun clearEpisodicMemories()
    /** Observe all core memories (for UI). */
    fun observeCoreMemories(): Flow<List<CoreMemoryEntity>>
    /** Observe episodic memory count (for UI). */
    fun observeEpisodicCount(): Flow<Int>
    /** Record that core memories with the given [ids] were accessed (increments accessCount). */
    suspend fun recordCoreMemoryAccess(ids: List<String>)
    /** Observe all episodic memories ordered by most recent first (for UI). */
    fun observeEpisodicMemories(): Flow<List<EpisodicMemoryEntity>>
    /** Delete a single episodic memory and its vector entry. */
    suspend fun deleteEpisodicMemory(id: String)
    /** Prune: episodic older than 30 days or count > 500; core capped at 200. */
    suspend fun prune()
    /** Update the content of an existing core memory, optionally updating its vector. */
    suspend fun updateCoreMemory(id: String, newContent: String, newVector: FloatArray? = null)
    /** Update the content of an existing episodic memory and re-embed it. */
    suspend fun updateEpisodicMemory(id: String, newContent: String, newVector: FloatArray)
}
