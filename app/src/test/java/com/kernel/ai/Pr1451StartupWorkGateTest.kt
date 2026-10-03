package com.kernel.ai

import com.kernel.ai.core.memory.worker.WORK_NAME_BACKFILL
import com.kernel.ai.core.memory.worker.WORK_NAME_NEXTCLOUD_SYNC
import com.kernel.ai.core.memory.worker.WORK_NAME_NEXTCLOUD_SYNC_PERIODIC
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Pr1451StartupWorkGateTest {
    @Test
    fun `normal startup preserves backfill archive and nextcloud scheduling order`() {
        val events = mutableListOf<String>()

        Pr1451StartupWorkGate.schedule(
            isolationEnabled = false,
            cancelUniqueWork = { events += "cancel:$it" },
            enqueueMemoryBackfill = { events += "backfill" },
            enqueueArchiveCleanup = { events += "archive" },
            enqueueNextcloud = { events += "nextcloud" },
        )

        assertEquals(listOf("backfill", "archive", "nextcloud"), events)
    }

    @Test
    fun `benchmark isolation cancels only overlapping work and never invokes nextcloud enqueue`() {
        val events = mutableListOf<String>()
        var nextcloudEnqueueCalls = 0

        Pr1451StartupWorkGate.schedule(
            isolationEnabled = true,
            cancelUniqueWork = { events += "cancel:$it" },
            enqueueMemoryBackfill = { events += "backfill" },
            enqueueArchiveCleanup = { events += "archive" },
            enqueueNextcloud = {
                nextcloudEnqueueCalls++
                events += "nextcloud"
            },
        )

        assertEquals(
            listOf(
                "cancel:$WORK_NAME_BACKFILL",
                "cancel:$WORK_NAME_NEXTCLOUD_SYNC",
                "cancel:$WORK_NAME_NEXTCLOUD_SYNC_PERIODIC",
                "archive",
            ),
            events,
        )
        assertEquals(0, nextcloudEnqueueCalls)
    }
}
