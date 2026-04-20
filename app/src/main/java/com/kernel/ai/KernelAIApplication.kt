package com.kernel.ai

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kernel.ai.core.inference.InferenceEngine
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
    @Inject lateinit var inferenceEngine: InferenceEngine

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

    /**
     * Release the LLM inference session under genuine memory pressure only.
     *
     * Two triggers:
     *  - RUNNING_CRITICAL (15): device is critically low on RAM while the app is foregrounded.
     *  - BACKGROUND (40+): app has been backgrounded long enough that the OS may kill it.
     *
     * Deliberately excluded: TRIM_MEMORY_UI_HIDDEN (20), which fires the moment the user
     * switches to another app. Releasing here would wipe the KV cache and destroy conversation
     * context on every app switch — not a memory emergency, just a visibility change.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val shouldRelease = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (shouldRelease) {
            Log.w("KernelAI", "onTrimMemory level=$level — releasing inference session")
            inferenceEngine.releaseForMemoryPressure()
        }
    }
}
