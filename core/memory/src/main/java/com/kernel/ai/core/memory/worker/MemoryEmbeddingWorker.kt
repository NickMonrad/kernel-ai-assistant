package com.kernel.ai.core.memory.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.KiwiMemoryDao
import com.kernel.ai.core.memory.repository.MemoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MemoryEmbeddingWorker"
const val WORK_NAME_BACKFILL = "memory_embedding_backfill"

/**
 * WorkManager [CoroutineWorker] that backfills embedding vectors for any core or episodic
 * memories saved before vector embedding was made mandatory.
 *
 * Enqueued as a one-time unique work at app startup (ExistingWorkPolicy.KEEP), so it runs
 * once after an upgrade and is a no-op on subsequent launches once all memories are vectorized.
 *
 * Future extension: schedule as a PeriodicWorkRequest with charging + idle constraints
 * for nightly re-embedding (e.g. after a model upgrade) or episodic distillation.
 */
@HiltWorker
class MemoryEmbeddingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val embeddingEngine: EmbeddingEngine,
    private val coreMemoryDao: CoreMemoryDao,
    private val episodicMemoryDao: EpisodicMemoryDao,
    private val kiwiMemoryDao: KiwiMemoryDao,
    private val memoryRepository: MemoryRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            var backfilled = 0

            val unvectorizedCore = coreMemoryDao.getUnvectorized()
            for (entity in unvectorizedCore) {
                val vector = embeddingEngine.embed(entity.content)
                if (vector.isEmpty()) {
                    Log.w(TAG, "Embedding engine not ready — skipping core memory rowId=${entity.rowId}")
                    continue
                }
                memoryRepository.backfillCoreVector(entity.rowId, vector)
                backfilled++
            }

            val unvectorizedEpisodic = episodicMemoryDao.getUnvectorized()
            for (entity in unvectorizedEpisodic) {
                val vector = embeddingEngine.embed(entity.content)
                if (vector.isEmpty()) {
                    Log.w(TAG, "Embedding engine not ready — skipping episodic memory rowId=${entity.rowId}")
                    continue
                }
                memoryRepository.backfillEpisodicVector(entity.rowId, vector)
                backfilled++
            }

            val unvectorizedKiwi = kiwiMemoryDao.getUnvectorized()
            for (entity in unvectorizedKiwi) {
                val vector = embeddingEngine.embed(entity.content)
                if (vector.isEmpty()) {
                    Log.w(TAG, "Embedding engine not ready — skipping kiwi memory rowId=${entity.rowId}")
                    continue
                }
                memoryRepository.backfillKiwiVector(entity.rowId, vector)
                backfilled++
            }

            Log.i(TAG, "Memory backfill complete — $backfilled memories vectorized")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Memory backfill failed", e)
            Result.retry()
        }
    }
}

