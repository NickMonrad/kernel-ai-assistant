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
import java.util.concurrent.atomic.AtomicInteger
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
        // sqlite-vec returns L2 distance, not cosine distance.
        // For unit-normalized 768-dim vectors: L2 = sqrt(2 * (1 - cos_sim))
        // Thresholds calibrated from on-device measurements (EmbeddingGemma-300M):
        //   ancestor memory: best match dist=1.0996 (cos_sim ≈ 0.40) — must pass
        //   aubergine memory: best match dist=0.9398 (cos_sim ≈ 0.56) — must pass
        // Core: 1.10 = sqrt(2 * 0.605) → cos_sim ≥ 0.395 — wide net for explicit memories
        // Episodic: 1.10 — same as core; episodic summaries are less lexically similar to
        //   queries than verbatim core memories, so they need a looser threshold too
        /** Loose threshold for core memories — covers cos_sim ≥ 0.40 in L2 space. */
        private const val CORE_MAX_DISTANCE = 1.10f
        /** Threshold for episodic memories — cos_sim ≥ 0.40 equivalent in L2 space. */
        private const val EPISODIC_MAX_DISTANCE = 1.10f
        /** Minimum content length for an episodic entry to appear in search results.
         *  Guards against short model hallucinations ("Nick", "You: Here") polluting results. */
        private const val MIN_EPISODIC_CONTENT_LENGTH = 20
    }

    // AtomicBoolean + Mutex for thread-safe lazy table creation
    private val episodicVecTableCreated = AtomicBoolean(false)
    private val episodicVecMutex = Mutex()
    private val coreVecTableCreated = AtomicBoolean(false)
    private val coreVecMutex = Mutex()
    private val coreVecDimensions = AtomicInteger(0)

    override suspend fun addEpisodicMemory(
        conversationId: String,
        content: String,
        embeddingVector: FloatArray,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = EpisodicMemoryEntity(
            id = id,
            conversationId = conversationId,
            content = content,
            createdAt = now,
            vectorized = false,
        )
        val rowId = episodicDao.insert(entity)
        if (rowId > 0) {
            ensureEpisodicVecTable(embeddingVector.size)
            vectorStore.upsert(EPISODIC_VEC_TABLE, rowId, embeddingVector)
            episodicDao.markVectorized(rowId)
        }
        prune()
        Log.d(TAG, "Added episodic memory id=$id rowId=$rowId")
        return id
    }

    override suspend fun addCoreMemory(
        content: String,
        source: String,
        embeddingVector: FloatArray,
        category: String,
        term: String,
        definition: String,
        triggerContext: String,
        vibeLevel: Int,
        metadataJson: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = CoreMemoryEntity(
            id = id,
            content = content,
            createdAt = now,
            lastAccessedAt = now,
            source = source,
            vectorized = false,
            category = category,
            term = term,
            definition = definition,
            triggerContext = triggerContext,
            vibeLevel = vibeLevel,
            metadataJson = metadataJson,
        )
        val rowId = coreDao.insert(entity)
        if (rowId > 0) {
            ensureCoreVecTable(embeddingVector.size)
            vectorStore.upsert(CORE_VEC_TABLE, rowId, embeddingVector)
            coreDao.markVectorized(rowId)
        }
        prune()
        Log.d(TAG, "Added core memory id=$id rowId=$rowId source=$source")
        return id
    }

    override suspend fun backfillCoreVector(rowId: Long, vector: FloatArray) {
        ensureCoreVecTable(vector.size)
        vectorStore.upsert(CORE_VEC_TABLE, rowId, vector)
        coreDao.markVectorized(rowId)
    }

    override suspend fun backfillEpisodicVector(rowId: Long, vector: FloatArray) {
        ensureEpisodicVecTable(vector.size)
        vectorStore.upsert(EPISODIC_VEC_TABLE, rowId, vector)
        episodicDao.markVectorized(rowId)
    }

    // Remove flag-guards: persisted vec tables must be searched after app restart.
    // runCatching already swallows "no such table" when no embeddings have been stored.
    override suspend fun searchMemories(
        queryVector: FloatArray,
        coreTopK: Int,
        episodicTopK: Int,
        identityTopK: Int,
    ): List<MemorySearchResult> {
        val results = mutableListOf<MemorySearchResult>()

        runCatching {
            // Fetch more than needed so we can split by category after filtering
            val fetchTopK = coreTopK + identityTopK
            val rawCoreResults = vectorStore.search(CORE_VEC_TABLE, queryVector, fetchTopK)
            Log.d(TAG, "Core vec search: ${rawCoreResults.size} raw results, distances=${rawCoreResults.map { "%.3f".format(it.distance) }}")
            val coreResults = rawCoreResults.filter { it.distance <= CORE_MAX_DISTANCE }
            val rowIds = coreResults.map { it.rowId }
            if (rowIds.isNotEmpty()) {
                val entities = coreDao.getAll().filter { it.rowId in rowIds }
                val distanceMap = coreResults.associate { it.rowId to it.distance }

                // Split by category and apply separate topK limits
                val userEntities = entities
                    .filter { it.category != "agent_identity" }
                    .sortedBy { distanceMap[it.rowId] ?: Float.MAX_VALUE }
                    .take(coreTopK)
                val identityEntities = entities
                    .filter { it.category == "agent_identity" }
                    .sortedBy { distanceMap[it.rowId] ?: Float.MAX_VALUE }
                    // Apply vibe-level distance threshold: higher vibe = tighter match required
                    .filter { entity ->
                        val dist = distanceMap[entity.rowId] ?: Float.MAX_VALUE
                        val maxDist = when {
                            entity.vibeLevel <= 2 -> CORE_MAX_DISTANCE  // 1.10 — surface freely
                            entity.vibeLevel == 3 -> 0.95f               // moderate match required
                            else -> 0.80f                                 // tight match for vibe 4-5
                        }
                        dist <= maxDist
                    }
                    .take(identityTopK)
                val combined = userEntities + identityEntities

                combined.forEach { entity ->
                    results.add(
                        MemorySearchResult(
                            id = entity.id,
                            content = entity.content,
                            source = "core",
                            score = 1f - (distanceMap[entity.rowId] ?: 1f),
                            lastAccessedAt = entity.lastAccessedAt,
                            term = entity.term,
                            definition = entity.definition,
                        )
                    )
                }
                runCatching {
                    val now = System.currentTimeMillis()
                    coreDao.incrementAccessStatsAndNotify(combined.map { it.id }, now)
                }.onFailure { Log.w(TAG, "incrementAccessStatsAndNotify failed: ${it.message}") }
            }
        }.onFailure { Log.w(TAG, "Core memory search failed: ${it.message}") }

        runCatching {
            if (episodicTopK <= 0) return@runCatching
            val rawEpisodicResults = vectorStore.search(EPISODIC_VEC_TABLE, queryVector, episodicTopK)
            Log.d(TAG, "Episodic vec search: ${rawEpisodicResults.size} raw results, distances=${rawEpisodicResults.map { "%.4f".format(it.distance) }}")
            val episodicResults = rawEpisodicResults.filter { it.distance <= EPISODIC_MAX_DISTANCE }
            val rowIds = episodicResults.map { it.rowId }
            if (rowIds.isNotEmpty()) {
                val entities = episodicDao.getAll()
                    .filter { it.rowId in rowIds && it.content.length >= MIN_EPISODIC_CONTENT_LENGTH }
                val distanceMap = episodicResults.associate { it.rowId to it.distance }
                entities.forEach { entity ->
                    results.add(
                        MemorySearchResult(
                            id = entity.id,
                            content = entity.content,
                            source = "episodic",
                            score = 1f - (distanceMap[entity.rowId] ?: 1f),
                            lastAccessedAt = entity.lastAccessedAt,
                            conversationId = entity.conversationId,
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
        // update vec first; only write content if vec succeeds (matches updateEpisodicMemory pattern)
        if (newVector != null) {
            val rowId = coreDao.getRowIdById(id)
            if (rowId != null && rowId > 0) {
                ensureCoreVecTable(newVector.size)
                vectorStore.upsert(CORE_VEC_TABLE, rowId, newVector)
            }
        }
        coreDao.updateContent(id, newContent)
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
                coreVecDimensions.set(dimensions)
                coreVecTableCreated.set(true)
                Log.i(TAG, "Created core vec table dim=$dimensions")
            }
        }
    }

    override suspend fun countCoreMemoriesBySource(source: String): Int =
        coreDao.countBySource(source)

    override suspend fun deleteAllCoreMemoriesBySource(source: String) {
        // Delete from the vec table first (needs rowIds), then from the Room table.
        val rowIds = coreDao.getRowIdsBySource(source)
        rowIds.forEach { vectorStore.delete(CORE_VEC_TABLE, it) }
        coreDao.deleteBySource(source)
    }

    override suspend fun resetCoreVecTable() {
        coreVecMutex.withLock {
            // Drop + recreate to purge all ghost entries (orphaned vec rows with no Room counterpart).
            val dim = coreVecDimensions.get()
            vectorStore.dropTable(CORE_VEC_TABLE)
            if (dim > 0) {
                vectorStore.createTable(CORE_VEC_TABLE, dim)
                Log.i(TAG, "Reset core vec table dim=$dim — all ghost entries purged")
            } else {
                // Table will be recreated lazily on next addCoreMemory call.
                Log.i(TAG, "Reset core vec table — will recreate lazily (dim not yet known)")
            }
            coreVecTableCreated.set(dim > 0)
        }
        // Mark all remaining Room rows as un-vectorized so the backfill worker re-embeds them.
        coreDao.markAllUnvectorized()
    }
}
