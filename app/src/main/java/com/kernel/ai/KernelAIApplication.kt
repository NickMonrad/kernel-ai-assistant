package com.kernel.ai

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kernel.ai.core.memory.worker.MemoryEmbeddingWorker
import com.kernel.ai.core.memory.worker.WORK_NAME_BACKFILL
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] to supply [HiltWorkerFactory], which is required
 * so WorkManager can inject dependencies into [androidx.work.ListenableWorker] subclasses
 * annotated with [@HiltWorker][androidx.hilt.work.HiltWorker].
 */
@HiltAndroidApp
class KernelAIApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniqueWork(
            WORK_NAME_BACKFILL,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MemoryEmbeddingWorker>().build(),
        )
    }
}
