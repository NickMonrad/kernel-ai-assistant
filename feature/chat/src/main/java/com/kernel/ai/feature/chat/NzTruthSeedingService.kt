package com.kernel.ai.feature.chat

import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.memory.entity.KiwiMemoryEntity
import com.kernel.ai.core.memory.repository.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NzTruthSeeding"

/**
 * Application-scoped singleton responsible for seeding NZ truth memories on first launch.
 *
 * Previously this ran in [viewModelScope], which meant the seeding coroutine was cancelled
 * whenever the user navigated away mid-seed, leaving the SharedPrefs flag unset and causing
 * an infinite delete-and-reseed loop on every subsequent ViewModel creation.
 *
 * This service owns its own [CoroutineScope] (SupervisorJob + Dispatchers.IO) so seeding
 * survives ViewModel recreation and completes exactly once per seed-guard version.
 */
@Singleton
class NzTruthSeedingService @Inject constructor(
    private val jandalPersona: JandalPersona,
    private val memoryRepository: MemoryRepository,
    private val embeddingEngine: EmbeddingEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * Launches seeding in the background if it hasn't completed yet.
     * Safe to call multiple times — only one seeding job runs at a time.
     */
    fun seedIfNeeded() {
        scope.launch { doSeedIfNeeded() }
    }

    private suspend fun doSeedIfNeeded() {
        mutex.withLock {
            if (jandalPersona.isTruthsSeeded) {
                // Guard against stale flag: DB may have been wiped (e.g. migration) while
                // SharedPreferences flag remained true. Re-seed if no jandal_persona kiwi memories present.
                val seededCount = memoryRepository.getAllKiwiMemories()
                    .count { it.source == "jandal_persona" }
                if (seededCount > 0) return
                Log.w(TAG, "truths_seeded flag was set but DB has 0 jandal_persona kiwi memories — re-seeding")
                jandalPersona.resetTruthsSeeded()
            }

            // Wipe any stale entries from a previous seed-guard version before re-seeding.
            val staleCount = memoryRepository.getAllKiwiMemories()
                .count { it.source == "jandal_persona" }
            if (staleCount > 0) {
                Log.i(TAG, "Clearing $staleCount stale jandal_persona entries from kiwi table before re-seeding")
                memoryRepository.deleteAllKiwiMemoriesBySource("jandal_persona")
            }
            // Clean up any legacy jandal_persona entries from core_memories (pre-#500 versions).
            val legacyCoreCount = memoryRepository.countCoreMemoriesBySource("jandal_persona")
            if (legacyCoreCount > 0) {
                Log.i(TAG, "Cleaning up $legacyCoreCount legacy jandal_persona entries from core_memories")
                memoryRepository.deleteAllCoreMemoriesBySource("jandal_persona")
            }
            // Always reset the kiwi vec table to purge ghost entries from any previous seeding cycles.
            memoryRepository.resetKiwiVecTable()

            var seeded = 0
            jandalPersona.nzTruths.forEach { truth ->
                val vector = embeddingEngine.embed(truth.vectorText).takeIf { it.isNotEmpty() }
                    ?: return@forEach // skip if engine not ready
                val now = System.currentTimeMillis()
                val entity = KiwiMemoryEntity(
                    id = truth.id,
                    content = truth.vectorText,
                    source = JandalPersona.SOURCE,
                    category = "agent_identity",
                    term = truth.term,
                    definition = truth.definition,
                    triggerContext = truth.triggerContext,
                    vibeLevel = truth.vibeLevel,
                    metadataJson = truth.metadataJson,
                    createdAt = now,
                    lastAccessedAt = now,
                )
                memoryRepository.upsertKiwiMemory(entity, vector)
                seeded++
            }

            jandalPersona.markTruthsSeeded()
            Log.i(TAG, "Seeded $seeded NZ truth memories into kiwi_memories table")
        }
    }
}
