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

    @Test
    fun `stranded local work is reported before a recorded failure and before an explicit stop`() {
        assertEquals(
            NextcloudListState.UNSYNCED_CHANGES,
            summary(failureCode = "PERMISSION", blockedUnsyncedAt = 5L).state(),
        )
        assertEquals(
            NextcloudListState.UNSYNCED_CHANGES,
            summary(syncEnabled = false, blockedUnsyncedAt = 5L).state(),
        )
    }

    @Test
    fun `a removed share reports unavailable before its stranded work`() {
        assertEquals(
            NextcloudListState.UNAVAILABLE,
            summary(remoteAvailable = false).state(),
        )
        assertEquals(
            NextcloudListState.UNAVAILABLE,
            summary(remoteAvailable = false, blockedUnsyncedAt = 5L).state(),
        )
    }

    @Test
    fun `an in-flight synchronization still overlays lost access`() {
        assertEquals(
            NextcloudListState.SYNCING,
            summary(remoteAvailable = false).state(syncing = true),
        )
    }

    private fun summary(
        syncEnabled: Boolean = true,
        failureCode: String? = null,
        remoteWritable: Boolean = true,
        remoteAvailable: Boolean = true,
        blockedUnsyncedAt: Long? = null,
    ) = NextcloudListSyncSummary(
        collectionId = "collection-shopping",
        remoteHref = "https://cloud.example/tasks/shopping/",
        syncEnabled = syncEnabled,
        lastFailureCode = failureCode,
        remoteWritable = remoteWritable,
        remoteAvailable = remoteAvailable,
        blockedUnsyncedAt = blockedUnsyncedAt,
    )
}
