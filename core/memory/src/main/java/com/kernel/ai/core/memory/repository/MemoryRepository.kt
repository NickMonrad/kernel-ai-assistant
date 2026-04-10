package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import kotlinx.coroutines.flow.Flow

interface MemoryRepository {
    /** Store a volatile, conversation-scoped memory. */
    suspend fun addEpisodicMemory(conversationId: String, content: String, embeddingVector: FloatArray? = null): String
    /** Store a permanent cross-conversation memory. */
    suspend fun addCoreMemory(content: String, source: String = "user", embeddingVector: FloatArray? = null): String
    /** Search BOTH tiers; core ranked above episodic. */
    suspend fun searchMemories(queryVector: FloatArray, topK: Int = 5): List<MemorySearchResult>
    /** Delete a specific core memory. */
    suspend fun deleteCoreMemory(id: String)
    /** Delete all episodic memories. */
    suspend fun clearEpisodicMemories()
    /** Observe all core memories (for UI). */
    fun observeCoreMemories(): Flow<List<CoreMemoryEntity>>
    /** Observe episodic memory count (for UI). */
    fun observeEpisodicCount(): Flow<Int>
    /** Prune: episodic older than 30 days or count > 500; core capped at 200. */
    suspend fun prune()
}
