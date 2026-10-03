package com.kernel.ai

import com.kernel.ai.core.memory.worker.WORK_NAME_BACKFILL
import com.kernel.ai.core.memory.worker.WORK_NAME_NEXTCLOUD_SYNC
import com.kernel.ai.core.memory.worker.WORK_NAME_NEXTCLOUD_SYNC_PERIODIC

/** Keeps only backfill and Nextcloud startup work away from the #1451 benchmark process. */
internal object Pr1451StartupWorkGate {
    fun schedule(
        isolationEnabled: Boolean,
        cancelUniqueWork: (String) -> Unit,
        enqueueMemoryBackfill: () -> Unit,
        enqueueArchiveCleanup: () -> Unit,
        enqueueNextcloud: () -> Unit,
    ) {
        if (isolationEnabled) {
            cancelUniqueWork(WORK_NAME_BACKFILL)
            cancelUniqueWork(WORK_NAME_NEXTCLOUD_SYNC)
            cancelUniqueWork(WORK_NAME_NEXTCLOUD_SYNC_PERIODIC)
        } else {
            enqueueMemoryBackfill()
        }

        enqueueArchiveCleanup()

        if (!isolationEnabled) enqueueNextcloud()
    }
}
