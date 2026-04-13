package com.kernel.ai.core.memory.repository

import android.util.Log
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.vector.VectorStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
        /** Looser threshold for core memories — user deliberately added these, cast a wide net. */
        private const val CORE_MAX_DISTANCE = 0.55f
        /** Stricter threshold for episodic memories — only surface clearly relevant past exchanges. */
        private const val EPISODIC_MAX_DISTANCE = 0.40f
    }

    // AtomicBoolean + Mutex for thread-safe lazy table creation
    private val episodicVecTableCreated = AtomicBoolean(false)
    private val episodicVecMutex = Mutex()
    private val coreVecTableCreated = AtomicBoolean(false)
    private val coreVecMutex = Mutex()

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

    // Remove flag-guards: persisted vec tables must be searched after app restart.
    // runCatching already swallows "no such table" when no embeddings have been stored.
    override suspend fun searchMemories(
        queryVector: FloatArray,
        coreTopK: Int,
        episodicTopK: Int,
    ): List<MemorySearchResult> {
        val results = mutableListOf<MemorySearchResult>()

        runCatching {
            val coreResults = vectorStore.search(CORE_VEC_TABLE, queryVector, coreTopK)
                .filter { it.distance <= CORE_MAX_DISTANCE }
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
                            lastAccessedAt = entity.lastAccessedAt,
                        )
                    )
                }
                // Update access stats atomically via @Transaction wrapper so Room's
                // InvalidationTracker fires on commit and observeAll() re-emits.
                runCatching {
                    val now = System.currentTimeMillis()
                    coreDao.incrementAccessStatsAndNotify(entities.map { it.id }, now)
                }.onFailure { Log.w(TAG, "incrementAccessStatsAndNotify failed: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "Core memory search failed: ${it.message}") }

        runCatching {
            if (episodicTopK <= 0) return@runCatching
            val episodicResults = vectorStore.search(EPISODIC_VEC_TABLE, queryVector, episodicTopK)
                .filter { it.distance <= EPISODIC_MAX_DISTANCE }
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
                            lastAccessedAt = entity.lastAccessedAt,
                        )
                    )
                }
                // Update last accessed timestamp for LRU pruning (#167)
                val now = System.currentTimeMillis()
                entities.forEach { entity ->
                    runCatching {
                        episodicDao.updateLastAccessedAt(entity.id, now)
                    }.onFailure { Log.w(TAG, "updateLastAccessedAt failed for ${entity.id}: ${it.message}") }
                }
            }
        }.onFailure { Log.w(TAG, "Episodic memory search failed: ${it.message}") }

        return results
    }

    override suspend fun deleteCoreMemory(id: String) {
        coreDao.delete(id)
        Log.d(TAG, "Deleted core memory id=$id")
    }

    override suspend fun updateCoreMemory(id: String, newContent: String, newVector: FloatArray?) {
        coreDao.updateContent(id, newContent)
        if (newVector != null) {
            val rowId = coreDao.getRowIdById(id)
            if (rowId != null && rowId > 0) {
                ensureCoreVecTable(newVector.size)
                vectorStore.upsert(CORE_VEC_TABLE, rowId, newVector)
            }
        }
        Log.d(TAG, "Updated core memory id=$id")
    }

    override suspend fun updateEpisodicMemory(id: String, newContent: String, newVector: FloatArray) {
        // Atomicity: update vec first; only write content if vec succeeds.
        val rowId = episodicDao.getRowIdById(id)
        if (rowId != null && rowId > 0) {
            ensureEpisodicVecTable(newVector.size)
            vectorStore.upsert(EPISODIC_VEC_TABLE, rowId, newVector)
        }
        episodicDao.updateContent(id, newContent)
        Log.d(TAG, "Updated episodic memory id=$id")
    }

    override suspend fun clearEpisodicMemories() {
        // Fetch rowIds first so we can remove orphaned vec entries
        val rowIds = episodicDao.getRowIdsOlderThan(Long.MAX_VALUE)
        episodicDao.deleteOlderThan(Long.MAX_VALUE)
        rowIds.forEach { vectorStore.delete(EPISODIC_VEC_TABLE, it) }
        Log.d(TAG, "Cleared all episodic memories (${rowIds.size} vec entries removed)")
    }

    override fun observeCoreMemories(): Flow<List<CoreMemoryEntity>> = coreDao.observeAll()

    override fun observeEpisodicCount(): Flow<Int> = episodicDao.observeCount()

    override fun observeEpisodicMemories(): Flow<List<EpisodicMemoryEntity>> = episodicDao.observeAll()

    override suspend fun deleteEpisodicMemory(id: String) {
        val rowId = episodicDao.getRowIdAndDelete(id)
        if (rowId != null) {
            runCatching { vectorStore.delete(EPISODIC_VEC_TABLE, rowId) }
        }
        Log.d(TAG, "Deleted episodic memory id=$id rowId=$rowId")
    }

    override suspend fun recordCoreMemoryAccess(ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            coreDao.incrementAccessStatsAndNotify(ids, now)
            Log.d(TAG, "Recorded access for ${ids.size} core memories")
        }.onFailure { Log.w(TAG, "recordCoreMemoryAccess failed: ${it.message}") }
    }

    // Delete vec entries for pruned rows to prevent orphan accumulation
    override suspend fun prune() {
        val now = System.currentTimeMillis()
        val cutoff = now - EPISODIC_TTL_MS

        val expiredRowIds = episodicDao.getRowIdsOlderThan(cutoff)
        episodicDao.deleteOlderThan(cutoff)
        expiredRowIds.forEach { vectorStore.delete(EPISODIC_VEC_TABLE, it) }

        val episodicCount = episodicDao.count()
        if (episodicCount > EPISODIC_MAX) {
            val overflow = episodicCount - EPISODIC_MAX
            val overflowRowIds = episodicDao.getRowIdsAndDeleteByLRU(overflow)
            overflowRowIds.forEach { vectorStore.delete(EPISODIC_VEC_TABLE, it) }
        }

        val coreCount = coreDao.count()
        if (coreCount > CORE_MAX) {
            val overflow = coreCount - CORE_MAX
            val overflowRowIds = coreDao.getOldestRowIds(overflow)
            coreDao.deleteOldestBeyondLimit(overflow)
            overflowRowIds.forEach { vectorStore.delete(CORE_VEC_TABLE, it) }
        }
    }

    private suspend fun ensureEpisodicVecTable(dimensions: Int) {
        if (episodicVecTableCreated.get()) return
        episodicVecMutex.withLock {
            if (!episodicVecTableCreated.get()) {
                vectorStore.createTable(EPISODIC_VEC_TABLE, dimensions)
                episodicVecTableCreated.set(true)
                Log.i(TAG, "Created episodic vec table dim=$dimensions")
            }
        }
    }

    private suspend fun ensureCoreVecTable(dimensions: Int) {
        if (coreVecTableCreated.get()) return
        coreVecMutex.withLock {
            if (!coreVecTableCreated.get()) {
                vectorStore.createTable(CORE_VEC_TABLE, dimensions)
                coreVecTableCreated.set(true)
                Log.i(TAG, "Created core vec table dim=$dimensions")
            }
        }
    }
}
