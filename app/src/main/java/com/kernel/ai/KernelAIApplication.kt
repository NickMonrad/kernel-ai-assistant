package com.kernel.ai

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
}
