package com.kernel.ai.core.memory.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.memory.repository.ConversationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "ArchiveCleanupWorker"
const val WORK_NAME_ARCHIVE_CLEANUP = "archive_cleanup"

/**
 * Daily WorkManager job that auto-deletes archived conversations older than the
 * user-configured retention period (from [ChatPreferences]).
 *
 * Runs with battery-not-low constraint at most once per day.
 */
@HiltWorker
class ArchiveCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val conversationRepository: ConversationRepository,
    private val chatPreferences: ChatPreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val retentionDays = chatPreferences.archiveRetentionDays.first()
            if (retentionDays == -1) {
                Log.i(TAG, "Archive retention is set to Never — skipping cleanup")
                return@withContext Result.success()
            }
            val cutoffMs = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
            conversationRepository.deleteArchivedOlderThan(cutoffMs)
            Log.i(TAG, "Archive cleanup complete — deleted conversations archived before cutoff (retentionDays=$retentionDays)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Archive cleanup failed", e)
            Result.retry()
        }
    }
}
