package com.kernel.ai.core.memory.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kernel.ai.core.memory.vector.EmbeddingIndexMigration
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "MemoryEmbeddingWorker"
const val WORK_NAME_BACKFILL = "memory_embedding_backfill"

/** Rebuilds all semantic indexes from canonical Room content after model changes. */
@HiltWorker
class MemoryEmbeddingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val indexMigration: EmbeddingIndexMigration,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        if (indexMigration.ensureCurrent()) {
            Result.success()
        } else {
            Result.retry()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Semantic index migration failed; WorkManager will retry", e)
        Result.retry()
    }
}

