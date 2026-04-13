package com.kernel.ai.core.memory.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.vector.VectorStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MemoryEmbeddingWorker"
const val WORK_NAME_BACKFILL = "memory_embedding_backfill"

@HiltWorker
class MemoryEmbeddingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val embeddingEngine: EmbeddingEngine,
    private val coreMemoryDao: CoreMemoryDao,
    private val episodicMemoryDao: EpisodicMemoryDao,
    private val vectorStore: VectorStore,
) : CoroutineWorker(context, params) {

    companion object {
        private const val CORE_VEC_TABLE = "core_memories_vec"
        private const val EPISODIC_VEC_TABLE = "episodic_memories_vec"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            var backfilled = 0

            // Backfill core memories
            val unvectorizedCore = coreMemoryDao.getUnvectorized()
            for (entity in unvectorizedCore) {
                val vector = embeddingEngine.embed(entity.content)
                if (vector.isEmpty()) {
                    Log.w(TAG, "Embedding engine not ready — skipping core memory rowId=${entity.rowId}")
                    continue
                }
                vectorStore.upsert(CORE_VEC_TABLE, entity.rowId, vector)
                coreMemoryDao.markVectorized(entity.rowId)
                backfilled++
            }

            // Backfill episodic memories
            val unvectorizedEpisodic = episodicMemoryDao.getUnvectorized()
            for (entity in unvectorizedEpisodic) {
                val vector = embeddingEngine.embed(entity.content)
                if (vector.isEmpty()) {
                    Log.w(TAG, "Embedding engine not ready — skipping episodic memory rowId=${entity.rowId}")
                    continue
                }
                vectorStore.upsert(EPISODIC_VEC_TABLE, entity.rowId, vector)
                episodicMemoryDao.markVectorized(entity.rowId)
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
