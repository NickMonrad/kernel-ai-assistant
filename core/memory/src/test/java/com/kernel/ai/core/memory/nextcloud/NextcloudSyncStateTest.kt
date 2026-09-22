package com.kernel.ai.core.memory.nextcloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NextcloudSyncStateTest {
    @Test
    fun `a bound list with no failure is up to date`() {
        assertEquals(NextcloudListState.UP_TO_DATE, summary().state())
    }

    @Test
    fun `an in-flight synchronization overlays the durable state`() {
        assertEquals(NextcloudListState.SYNCING, summary().state(syncing = true))
    }

    @Test
    fun `a recorded failure needs attention`() {
        assertEquals(
            NextcloudListState.NEEDS_ATTENTION,
            summary(failureCode = "NETWORK").state(),
        )
    }

    @Test
    fun `an explicitly stopped list reports sync off`() {
        assertEquals(NextcloudListState.SYNC_OFF, summary(syncEnabled = false).state())
    }

    @Test
    fun `stopping a failed list reports sync off rather than needs attention`() {
        // Stop is a deliberate user action, so it must win over the recorded failure; otherwise the
        // row keeps offering Stop and Resume is unreachable (#1551).
        assertEquals(
            NextcloudListState.SYNC_OFF,
            summary(syncEnabled = false, failureCode = "PERMISSION").state(),
        )
    }

    private fun summary(
        syncEnabled: Boolean = true,
        failureCode: String? = null,
    ) = NextcloudListSyncSummary(
        collectionId = "collection-shopping",
        remoteHref = "https://cloud.example/tasks/shopping/",
        syncEnabled = syncEnabled,
        lastFailureCode = failureCode,
    )
}
