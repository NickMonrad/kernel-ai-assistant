package com.kernel.ai.core.memory.repository

import android.util.Log
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.vector.VectorStore
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val episodicDao: EpisodicMemoryDao,
    private val coreDao: CoreMemoryDao,
    private val vectorStore: VectorStore,
) : MemoryRepository {

    companion object {
        private const val TAG = "KernelAI"
        private const val EPISODIC_VEC_TABLE = "episodic_memories_vec"
        private const val CORE_VEC_TABLE = "core_memories_vec"
        private const val EPISODIC_MAX = 500
        private const val CORE_MAX = 200
        private const val EPISODIC_TTL_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
    }

    private var episodicVecTableCreated = false
    private var coreVecTableCreated = false

    override suspend fun addEpisodicMemory(
        conversationId: String,
        content: String,
        embeddingVector: FloatArray?,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = EpisodicMemoryEntity(
            id = id,
            conversationId = conversationId,
            content = content,
            createdAt = now,
        )
        val rowId = episodicDao.insert(entity)
        if (embeddingVector != null && rowId > 0) {
            ensureEpisodicVecTable(embeddingVector.size)
            vectorStore.upsert(EPISODIC_VEC_TABLE, rowId, embeddingVector)
        }
        prune()
        Log.d(TAG, "Added episodic memory id=$id rowId=$rowId")
        return id
    }

    override suspend fun addCoreMemory(
        content: String,
        source: String,
        embeddingVector: FloatArray?,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = CoreMemoryEntity(
            id = id,
            content = content,
            createdAt = now,
            lastAccessedAt = now,
            source = source,
        )
        val rowId = coreDao.insert(entity)
        if (embeddingVector != null && rowId > 0) {
            ensureCoreVecTable(embeddingVector.size)
            vectorStore.upsert(CORE_VEC_TABLE, rowId, embeddingVector)
        }
        prune()
        Log.d(TAG, "Added core memory id=$id rowId=$rowId source=$source")
        return id
    }

    override suspend fun searchMemories(queryVector: FloatArray, topK: Int): List<MemorySearchResult> {
        val results = mutableListOf<MemorySearchResult>()

        if (coreVecTableCreated) {
            runCatching {
                val coreResults = vectorStore.search(CORE_VEC_TABLE, queryVector, topK)
                val rowIds = coreResults.map { it.rowId }
                if (rowIds.isNotEmpty()) {
                    val entities = coreDao.getAll().filter { it.rowId in rowIds }
                    val distanceMap = coreResults.associate { it.rowId to it.distance }
                    entities.forEach { entity ->
                        results.add(
                            MemorySearchResult(
                                id = entity.id,
                                content = entity.content,
                                source = "core",
                                score = 1f - (distanceMap[entity.rowId] ?: 1f),
                            )
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Core memory search failed: ${it.message}") }
        }

        if (episodicVecTableCreated) {
            runCatching {
                val episodicResults = vectorStore.search(EPISODIC_VEC_TABLE, queryVector, topK)
                val rowIds = episodicResults.map { it.rowId }
                if (rowIds.isNotEmpty()) {
                    val entities = episodicDao.getAll().filter { it.rowId in rowIds }
                    val distanceMap = episodicResults.associate { it.rowId to it.distance }
                    entities.forEach { entity ->
                        results.add(
                            MemorySearchResult(
                                id = entity.id,
                                content = entity.content,
                                source = "episodic",
                                score = 1f - (distanceMap[entity.rowId] ?: 1f),
                            )
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Episodic memory search failed: ${it.message}") }
        }

        // Core ranked above episodic, then by score descending
        return results
            .sortedWith(compareBy<MemorySearchResult> { if (it.source == "core") 0 else 1 }.thenByDescending { it.score })
            .take(topK)
    }

    override suspend fun deleteCoreMemory(id: String) {
        coreDao.delete(id)
        Log.d(TAG, "Deleted core memory id=$id")
    }

    override suspend fun clearEpisodicMemories() {
        episodicDao.deleteOlderThan(0L)
        Log.d(TAG, "Cleared all episodic memories")
    }

    override fun observeCoreMemories(): Flow<List<CoreMemoryEntity>> = coreDao.observeAll()

    override fun observeEpisodicCount(): Flow<Int> = episodicDao.observeCount()

    override suspend fun prune() {
        val now = System.currentTimeMillis()
        val cutoff = now - EPISODIC_TTL_MS
        episodicDao.deleteOlderThan(cutoff)

        val episodicCount = episodicDao.count()
        if (episodicCount > EPISODIC_MAX) {
            episodicDao.deleteOldestBeyondLimit(episodicCount - EPISODIC_MAX)
        }

        val coreCount = coreDao.count()
        if (coreCount > CORE_MAX) {
            coreDao.deleteOldestBeyondLimit(coreCount - CORE_MAX)
        }
    }

    private fun ensureEpisodicVecTable(dimensions: Int) {
        if (!episodicVecTableCreated) {
            vectorStore.createTable(EPISODIC_VEC_TABLE, dimensions)
            episodicVecTableCreated = true
            Log.i(TAG, "Created episodic vec table dim=$dimensions")
        }
    }

    private fun ensureCoreVecTable(dimensions: Int) {
        if (!coreVecTableCreated) {
            vectorStore.createTable(CORE_VEC_TABLE, dimensions)
            coreVecTableCreated = true
            Log.i(TAG, "Created core vec table dim=$dimensions")
        }
    }
}
