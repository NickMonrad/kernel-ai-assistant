package com.kernel.ai.core.memory.repository

import android.util.Log
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.KiwiMemoryDao
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.entity.KiwiMemoryEntity
import com.kernel.ai.core.memory.vector.EmbeddingIndexMigration
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
    private val kiwiMemoryDao: KiwiMemoryDao,
    private val indexMigration: EmbeddingIndexMigration,
    private val vectorStore: VectorStore,
) : MemoryRepository {

    companion object {
        private const val TAG = "KernelAI"
        private const val EPISODIC_VEC_TABLE = "episodic_memories_vec"
        private const val CORE_VEC_TABLE = "core_memories_vec"
        private const val KIWI_VEC_TABLE = "kiwi_memories_vec"
        private const val EPISODIC_MAX = 500
        private const val CORE_MAX = 200
        private const val EPISODIC_TTL_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
        // Phase B cutoffs are fit per consumer on calibration queries and checked on held-out queries.
        private const val CORE_MAX_DISTANCE = 0.789f
        private const val EPISODIC_MAX_DISTANCE = 0.666f
        private val IDENTITY_MAX_DISTANCE_BY_VIBE = floatArrayOf(0.759f, 0.764f, 0.737f, 0.720f, 0.691f)
        private val KIWI_MAX_DISTANCE_BY_VIBE = floatArrayOf(0.564f, 0.731f, 0.667f, 0.620f, 0.500f)
        private fun maxDistanceForVibe(vibeLevel: Int, limits: FloatArray): Float =
            limits.getOrNull(vibeLevel - 1) ?: -1f
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
    private val kiwiVecTableCreated = AtomicBoolean(false)
    private val kiwiVecMutex = Mutex()
    private val kiwiVecDimensions = AtomicInteger(0)

    override suspend fun addEpisodicMemory(
        conversationId: String,
        content: String,
        embeddingVector: FloatArray,
    ): String {
        val indexCurrent = indexMigration.ensureCurrent()
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
        if (indexCurrent && rowId > 0 && embeddingVector.isNotEmpty()) {
            ensureEpisodicVecTable(embeddingVector.size)
            vectorStore.upsert(EPISODIC_VEC_TABLE, rowId, embeddingVector)
            episodicDao.markVectorized(rowId)
        }
        prune()
        Log.d(TAG, "Added episodic memory id=$id rowId=$rowId")
        return id
    }

    override suspend fun hasEpisodicMemory(conversationId: String, content: String): Boolean =
        episodicDao.existsByConversationAndContent(conversationId, content)

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
        val indexCurrent = indexMigration.ensureCurrent()
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
        if (indexCurrent && rowId > 0 && embeddingVector.isNotEmpty()) {
            ensureCoreVecTable(embeddingVector.size)
            vectorStore.upsert(CORE_VEC_TABLE, rowId, embeddingVector)
            coreDao.markVectorized(rowId)
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
        identityTopK: Int,
        kiwiTopK: Int,
    ): List<MemorySearchResult> {
        if (!indexMigration.ensureCurrent()) return emptyList()
        val results = mutableListOf<MemorySearchResult>()

        runCatching {
            // Retrieve the shared core table once, then apply independent cutoffs after category split.
            val fetchTopK = coreTopK + identityTopK
            val rawCoreResults = vectorStore.search(CORE_VEC_TABLE, queryVector, fetchTopK)
            Log.d(TAG, "Core vec search: ${rawCoreResults.size} raw results, distances=${rawCoreResults.map { "%.3f".format(it.distance) }}")
            val rowIds = rawCoreResults.map { it.rowId }
            if (rowIds.isNotEmpty()) {
                val entities = coreDao.getAll().filter { it.vectorized && it.rowId in rowIds }
                val distanceMap = rawCoreResults.associate { it.rowId to it.distance }

                // Split by category before thresholding so each consumer uses its calibrated bound.
                val userEntities = entities
                    .filter { it.category != "agent_identity" && it.source != "jandal_persona" }
                    .filter { (distanceMap[it.rowId] ?: Float.MAX_VALUE) <= CORE_MAX_DISTANCE }
                    .sortedBy { distanceMap[it.rowId] ?: Float.MAX_VALUE }
                    .take(coreTopK)
                val identityEntities = entities
                    .filter { it.category == "agent_identity" }
                    .filter {
                        (distanceMap[it.rowId] ?: Float.MAX_VALUE) <= maxDistanceForVibe(it.vibeLevel, IDENTITY_MAX_DISTANCE_BY_VIBE)
                    }
                    .sortedBy { distanceMap[it.rowId] ?: Float.MAX_VALUE }
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
                    .filter { it.vectorized && it.rowId in rowIds && it.content.length >= MIN_EPISODIC_CONTENT_LENGTH }
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

        // ── Kiwi (NZ corpus) search ────────────────────────────────────────
        if (kiwiTopK > 0) {
            runCatching {
                val rawKiwiResults = vectorStore.search(KIWI_VEC_TABLE, queryVector, kiwiTopK)
                Log.d(TAG, "Kiwi vec search: ${rawKiwiResults.size} raw results, distances=${rawKiwiResults.map { "%.3f".format(it.distance) }}")
                val rowIds = rawKiwiResults.map { it.rowId }
                if (rowIds.isNotEmpty()) {
                    val entities = kiwiMemoryDao.getByRowIds(rowIds)
                    val distanceMap = rawKiwiResults.associate { it.rowId to it.distance }
                    val filtered = entities
                        .filter {
                            (distanceMap[it.rowId] ?: Float.MAX_VALUE) <= maxDistanceForVibe(it.vibeLevel, KIWI_MAX_DISTANCE_BY_VIBE)
                        }
                        .sortedBy { distanceMap[it.rowId] ?: Float.MAX_VALUE }
                        .take(kiwiTopK)
                    filtered.forEach { entity ->
                        results.add(
                            MemorySearchResult(
                                id = entity.id,
                                content = entity.content,
                                source = "kiwi",
                                score = 1f - (distanceMap[entity.rowId] ?: 1f),
                                lastAccessedAt = entity.lastAccessedAt,
                                term = entity.term,
                                definition = entity.definition,
                            )
                        )
                    }
                    runCatching {
                        val now = System.currentTimeMillis()
                        kiwiMemoryDao.incrementAccessStatsAndNotify(filtered.map { it.id }, now)
                    }.onFailure { Log.w(TAG, "kiwi incrementAccessStats failed: ${it.message}") }
                }
            }.onFailure { Log.w(TAG, "Kiwi memory search failed: ${it.message}") }
        }

        return results
    }

    override suspend fun deleteCoreMemory(id: String) {
        coreDao.delete(id)
        Log.d(TAG, "Deleted core memory id=$id")
    }

    override suspend fun updateCoreMemory(id: String, newContent: String, newVector: FloatArray?) {
        val indexCurrent = indexMigration.ensureCurrent()
        coreDao.updateContent(id, newContent)
        val rowId = coreDao.getRowIdById(id)
        if (indexCurrent && newVector != null && newVector.isNotEmpty() && rowId != null && rowId > 0) {
            ensureCoreVecTable(newVector.size)
            vectorStore.upsert(CORE_VEC_TABLE, rowId, newVector)
            coreDao.markVectorized(rowId)
        }
        Log.d(TAG, "Updated core memory id=$id")
    }

    override suspend fun updateEpisodicMemory(id: String, newContent: String, newVector: FloatArray) {
        val indexCurrent = indexMigration.ensureCurrent()
        episodicDao.updateContent(id, newContent)
        val rowId = episodicDao.getRowIdById(id)
        if (indexCurrent && newVector.isNotEmpty() && rowId != null && rowId > 0) {
            ensureEpisodicVecTable(newVector.size)
            vectorStore.upsert(EPISODIC_VEC_TABLE, rowId, newVector)
            episodicDao.markVectorized(rowId)
        }
        Log.d(TAG, "Updated episodic memory id=$id")
    }

    override suspend fun clearEpisodicMemories() {
        val indexCurrent = indexMigration.ensureCurrent()
        val rowIds = episodicDao.getRowIdsOlderThan(Long.MAX_VALUE)
        episodicDao.deleteOlderThan(Long.MAX_VALUE)
        if (indexCurrent) {
            rowIds.forEach { vectorStore.delete(EPISODIC_VEC_TABLE, it) }
        }
        Log.d(TAG, "Cleared all episodic memories (${rowIds.size} vec entries removed)")
    }

    override fun observeCoreMemories(): Flow<List<CoreMemoryEntity>> = coreDao.observeAll()

    override suspend fun getAllCoreMemories(): List<CoreMemoryEntity> = coreDao.getAll()

    override fun observeEpisodicCount(): Flow<Int> = episodicDao.observeCount()

    override fun observeEpisodicMemories(): Flow<List<EpisodicMemoryEntity>> = episodicDao.observeAll()

    override suspend fun deleteEpisodicMemory(id: String) {
        val indexCurrent = indexMigration.ensureCurrent()
        val rowId = episodicDao.getRowIdAndDelete(id)
        if (indexCurrent && rowId != null) {
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
        val indexCurrent = indexMigration.ensureCurrent()
        val now = System.currentTimeMillis()
        val cutoff = now - EPISODIC_TTL_MS

        val expiredRowIds = episodicDao.getRowIdsOlderThan(cutoff)
        episodicDao.deleteOlderThan(cutoff)
        if (indexCurrent) expiredRowIds.forEach { vectorStore.delete(EPISODIC_VEC_TABLE, it) }

        val episodicCount = episodicDao.count()
        if (episodicCount > EPISODIC_MAX) {
            val overflow = episodicCount - EPISODIC_MAX
            val overflowRowIds = episodicDao.getRowIdsAndDeleteByLRU(overflow)
            if (indexCurrent) overflowRowIds.forEach { vectorStore.delete(EPISODIC_VEC_TABLE, it) }
        }

        val coreCount = coreDao.count()
        if (coreCount > CORE_MAX) {
            val overflow = coreCount - CORE_MAX
            val overflowRowIds = coreDao.getOldestRowIds(overflow)
            coreDao.deleteOldestBeyondLimit(overflow)
            if (indexCurrent) overflowRowIds.forEach { vectorStore.delete(CORE_VEC_TABLE, it) }
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

    private suspend fun ensureKiwiVecTable(dimensions: Int) {
        if (kiwiVecTableCreated.get()) return
        kiwiVecMutex.withLock {
            if (!kiwiVecTableCreated.get()) {
                vectorStore.createTable(KIWI_VEC_TABLE, dimensions)
                kiwiVecDimensions.set(dimensions)
                kiwiVecTableCreated.set(true)
                Log.i(TAG, "Created kiwi vec table dim=$dimensions")
            }
        }
    }

    override suspend fun countCoreMemoriesBySource(source: String): Int =
        coreDao.countBySource(source)

    override suspend fun deleteAllCoreMemoriesBySource(source: String) {
        val indexCurrent = indexMigration.ensureCurrent()
        val rowIds = coreDao.getRowIdsBySource(source)
        if (indexCurrent) rowIds.forEach { vectorStore.delete(CORE_VEC_TABLE, it) }
        coreDao.deleteBySource(source)
    }

    override suspend fun resetCoreVecTable() {
        if (!indexMigration.ensureCurrent()) {
            coreDao.markAllUnvectorized()
            return
        }
        coreVecMutex.withLock {
            val dim = coreVecDimensions.get()
            vectorStore.dropTable(CORE_VEC_TABLE)
            if (dim > 0) {
                vectorStore.createTable(CORE_VEC_TABLE, dim)
                Log.i(TAG, "Reset core vec table dim=$dim — all ghost entries purged")
            } else {
                Log.i(TAG, "Reset core vec table — will recreate lazily (dim not yet known)")
            }
            coreVecTableCreated.set(dim > 0)
        }
        coreDao.markAllUnvectorized()
    }

    override suspend fun upsertKiwiMemory(entity: KiwiMemoryEntity, embeddingVector: FloatArray) {
        val indexCurrent = indexMigration.ensureCurrent()
        val rowId = kiwiMemoryDao.insert(entity)
        if (indexCurrent && rowId > 0 && embeddingVector.isNotEmpty()) {
            ensureKiwiVecTable(embeddingVector.size)
            vectorStore.upsert(KIWI_VEC_TABLE, rowId, embeddingVector)
            kiwiMemoryDao.markVectorized(rowId)
        }
        Log.d(TAG, "Upserted kiwi memory id=${entity.id} rowId=$rowId")
    }

    override suspend fun deleteAllKiwiMemoriesBySource(source: String) {
        val indexCurrent = indexMigration.ensureCurrent()
        val rowIds = kiwiMemoryDao.getRowIdsBySource(source)
        if (indexCurrent) rowIds.forEach { vectorStore.delete(KIWI_VEC_TABLE, it) }
        kiwiMemoryDao.deleteBySource(source)
    }

    override suspend fun resetKiwiVecTable() {
        if (!indexMigration.ensureCurrent()) {
            kiwiMemoryDao.markAllUnvectorized()
            return
        }
        kiwiVecMutex.withLock {
            val dim = kiwiVecDimensions.get().takeIf { it > 0 } ?: coreVecDimensions.get()
            vectorStore.dropTable(KIWI_VEC_TABLE)
            if (dim > 0) {
                vectorStore.createTable(KIWI_VEC_TABLE, dim)
                kiwiVecDimensions.set(dim)
                Log.i(TAG, "Reset kiwi vec table dim=$dim — all ghost entries purged")
            } else {
                Log.i(TAG, "Reset kiwi vec table — will recreate lazily (dim not yet known)")
            }
            kiwiVecTableCreated.set(dim > 0)
        }
        kiwiMemoryDao.markAllUnvectorized()
    }


    override fun observeAllKiwiMemories(): Flow<List<KiwiMemoryEntity>> = kiwiMemoryDao.observeAll()

    override suspend fun getAllKiwiMemories(): List<KiwiMemoryEntity> = kiwiMemoryDao.getAll()
}
