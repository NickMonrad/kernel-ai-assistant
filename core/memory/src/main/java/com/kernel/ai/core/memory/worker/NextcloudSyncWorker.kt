package com.kernel.ai.core.memory.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import com.kernel.ai.core.memory.dao.ListChangeDao
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.WorkerParameters
import com.kernel.ai.core.memory.nextcloud.NextcloudFailure
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val WORK_NAME_NEXTCLOUD_SYNC = "nextcloud_lists_sync"
private const val WORK_NAME_NEXTCLOUD_SYNC_PERIODIC = "nextcloud_lists_sync_periodic"

@HiltWorker
class NextcloudSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val adapter: NextcloudSyncAdapter,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when (val result = adapter.syncAll()) {
            is NextcloudSyncResult.Success -> Result.success()
            is NextcloudSyncResult.Failure -> when (result.error.code) {
                NextcloudFailure.Code.NETWORK,
                NextcloudFailure.Code.SERVER,
                NextcloudFailure.Code.CONFLICT,
                -> Result.retry()
                else -> Result.failure()
            }
        }
    }
}

@Singleton
class NextcloudSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val listChangeDao: ListChangeDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            listChangeDao.observePendingCount().drop(1).collect { count ->
                if (count > 0) enqueueNow()
            }
        }
    }
    fun enqueuePeriodic() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_NEXTCLOUD_SYNC_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NextcloudSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }

    fun enqueueNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_NEXTCLOUD_SYNC,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<NextcloudSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }
}
