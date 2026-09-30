package com.kernel.ai.core.memory.nextcloud

import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.NextcloudCollectionBindingDao
import com.kernel.ai.core.memory.dao.NextcloudItemBindingDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.entity.NextcloudItemBindingEntity
import com.kernel.ai.core.memory.lists.ListChange
import com.kernel.ai.core.memory.lists.ListChangeOperation
import com.kernel.ai.core.memory.lists.SharedCollectionSnapshot
import com.kernel.ai.core.memory.lists.VersionStamp
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NextcloudSyncAdapterTest {
    @Test
    fun `read-only binding pulls without pushing while writable bindings continue`() = runTest {
        val readOnly = binding("readonly")
        val writable = binding("writable")
        val bindings = linkedMapOf(readOnly.collectionId to readOnly, writable.collectionId to writable)
        val itemBindings = linkedMapOf(
            readOnly.collectionId to itemBinding(readOnly.collectionId, "readonly-item"),
            writable.collectionId to itemBinding(writable.collectionId, "writable-item"),
        )
        val lists = mapOf(
            readOnly.collectionId to list(readOnly.collectionId),
            writable.collectionId to list(writable.collectionId),
        )
        val rows = mapOf(
            readOnly.collectionId to listItem(readOnly.collectionId, "readonly-item"),
            writable.collectionId to listItem(writable.collectionId, "writable-item"),
        )
        val pending = listOf(change("writable", "writable-change"))
        val transport = RecordingTransport(readOnlyCollections = setOf("readonly"))
        val fixture = adapter(bindings, itemBindings, lists, rows, pending, transport)

        val result = fixture.adapter.syncAll()

        assertTrue(result is NextcloudSyncResult.Success)
        assertTrue("readonly" in transport.reportedCollections)
        assertTrue("writable" in transport.reportedCollections)
        assertTrue(transport.putCalls.none { "/readonly/" in it })
        coVerify(exactly = 0) {
            fixture.mutations.acknowledgePushed(match { "readonly-change" in it })
        }
        coVerify {
            fixture.mutations.acknowledgePushed(listOf("writable-change"))
        }
        assertFalse(bindings.getValue("readonly").remoteWritable)
        assertNull(bindings.getValue("readonly").lastFailureCode)
    }

    @Test
    fun `downgrade discovered after a stale local mutation quarantines the work and reports unsynced changes`() = runTest {
        val readOnly = binding("readonly")
        val bindings = linkedMapOf(readOnly.collectionId to readOnly)
        val itemBindings = linkedMapOf(
            readOnly.collectionId to itemBinding(readOnly.collectionId, "readonly-item"),
        )
        val lists = mapOf(readOnly.collectionId to list(readOnly.collectionId))
        val rows = mapOf(readOnly.collectionId to listItem(readOnly.collectionId, "readonly-item"))
        // The mutation was accepted while the cached binding still said writable.
        val pending = listOf(change("readonly", "readonly-change"))
        val transport = RecordingTransport(readOnlyCollections = setOf("readonly"))
        val fixture = adapter(bindings, itemBindings, lists, rows, pending, transport)

        val result = fixture.adapter.syncAll()

        assertEquals(
            NextcloudFailure.Code.PERMISSION,
            (result as NextcloudSyncResult.Failure).error.code,
        )
        assertTrue("readonly" in transport.reportedCollections, "owner changes are still pulled")
        assertTrue(
            transport.putCalls.none { "/readonly/" in it },
            "the stale local mutation is never published while the collection is read-only",
        )
        coVerify(exactly = 0) {
            fixture.mutations.acknowledgePushed(listOf("readonly-change"))
        }
        assertFalse(bindings.getValue("readonly").remoteWritable)
        assertNotNull(
            bindings.getValue("readonly").blockedUnsyncedAt,
            "the stranded work is recorded so it can never auto-push later",
        )
        assertEquals(
            NextcloudListState.UNSYNCED_CHANGES,
            summaryOf(bindings.getValue("readonly")).state(),
        )
    }

    @Test
    fun `write access restored after a discovered downgrade never publishes the stranded work`() = runTest {
        val stale = binding("readonly").copy(blockedUnsyncedAt = 5L)
        val bindings = linkedMapOf(stale.collectionId to stale)
        val itemBindings = linkedMapOf(
            stale.collectionId to itemBinding(stale.collectionId, "readonly-item"),
        )
        val lists = mapOf(stale.collectionId to list(stale.collectionId))
        val rows = mapOf(
            stale.collectionId to listItem(stale.collectionId, "readonly-item").copy(text = "Added while stale"),
        )
        val pending = listOf(change("readonly", "readonly-change"))
        // The owner restored write access; the quarantined work still must not move.
        val transport = RecordingTransport()
        val fixture = adapter(bindings, itemBindings, lists, rows, pending, transport)

        val result = fixture.adapter.syncAll()

        assertEquals(
            NextcloudFailure.Code.PERMISSION,
            (result as NextcloudSyncResult.Failure).error.code,
        )
        assertTrue(
            transport.putCalls.isEmpty(),
            "work stranded by a discovered downgrade is never published, even when write access returns",
        )
        coVerify(exactly = 0) {
            fixture.mutations.acknowledgePushed(listOf("readonly-change"))
        }
        assertNotNull(bindings.getValue("readonly").blockedUnsyncedAt)
    }

    @Test
    fun `discarding stranded work reverts to the provider representation and clears the record`() = runTest {
        val readOnly = binding("readonly").copy(
            remoteWritable = false,
            blockedUnsyncedAt = 5L,
            lastFailureCode = NextcloudFailure.Code.PERMISSION.name,
        )
        val bindings = linkedMapOf(readOnly.collectionId to readOnly)
        val itemBindings = linkedMapOf(
            readOnly.collectionId to itemBinding(readOnly.collectionId, "readonly-item"),
        )
        val lists = mapOf(readOnly.collectionId to list(readOnly.collectionId))
        val rows = mapOf(readOnly.collectionId to listItem(readOnly.collectionId, "readonly-item"))
        val fixture = adapter(bindings, itemBindings, lists, rows, listOf(change("readonly", "readonly-change")), RecordingTransport())

        val outcome = fixture.adapter.resolveLocalWork(
            readOnly.collectionId,
            NextcloudLocalWorkResolution.DISCARD_LOCAL_CHANGES,
        )

        assertEquals(1L, outcome.getOrNull())
        coVerify {
            fixture.mutations.importSnapshot(
                match {
                    it.collectionId == readOnly.collectionId &&
                        it.items.single().text == "Item" &&
                        it.items.single().textStamp.logicalClock > 1L
                },
                any(),
                true,
            )
        }
        coVerify { fixture.mutations.discardPendingChanges(readOnly.collectionId) }
        assertNull(
            bindings.getValue(readOnly.collectionId).blockedUnsyncedAt,
            "the record is cleared once the user resolves it",
        )
        assertTrue(
            bindings.containsKey(readOnly.collectionId),
            "a still-reachable read-only share keeps its binding",
        )
        coVerify(exactly = 0) { fixture.mutations.copyListAsLocal(any(), any()) }
    }

    @Test
    fun `keeping stranded work copies it out and returns the shared list to the owner`() = runTest {
        val readOnly = binding("readonly").copy(remoteWritable = false, blockedUnsyncedAt = 5L)
        val bindings = linkedMapOf(readOnly.collectionId to readOnly)
        val itemBindings = linkedMapOf(
            readOnly.collectionId to itemBinding(readOnly.collectionId, "readonly-item"),
        )
        val lists = mapOf(readOnly.collectionId to list(readOnly.collectionId))
        val rows = mapOf(readOnly.collectionId to listItem(readOnly.collectionId, "readonly-item"))
        val fixture = adapter(bindings, itemBindings, lists, rows, listOf(change("readonly", "readonly-change")), RecordingTransport())

        val outcome = fixture.adapter.resolveLocalWork(
            readOnly.collectionId,
            NextcloudLocalWorkResolution.KEEP_LOCAL_COPY,
        )

        assertEquals(99L, outcome.getOrNull(), "the copy is the list the user now edits")
        coVerify { fixture.mutations.copyListAsLocal(1L, "readonly (local copy)") }
        coVerify { fixture.mutations.importSnapshot(match { it.collectionId == readOnly.collectionId }, any(), true) }
        coVerify { fixture.mutations.discardPendingChanges(readOnly.collectionId) }
        assertNull(bindings.getValue(readOnly.collectionId).blockedUnsyncedAt)
        assertTrue(
            bindings.containsKey(readOnly.collectionId),
            "the shared list stays bound, read-only and owner-following",
        )
    }

    @Test
    fun `read-only share can be copied out proactively without unsynced work`() = runTest {
        val readOnly = binding("readonly").copy(remoteWritable = false)
        val bindings = linkedMapOf(readOnly.collectionId to readOnly)
        val itemBindings = linkedMapOf(
            readOnly.collectionId to itemBinding(readOnly.collectionId, "readonly-item"),
        )
        val lists = mapOf(readOnly.collectionId to list(readOnly.collectionId))
        val rows = mapOf(readOnly.collectionId to listItem(readOnly.collectionId, "readonly-item"))
        val fixture = adapter(bindings, itemBindings, lists, rows, emptyList(), RecordingTransport())

        val outcome = fixture.adapter.createLocalCopy(readOnly.collectionId)

        assertEquals(99L, outcome.getOrNull())
        coVerify { fixture.mutations.copyListAsLocal(1L, "readonly (local copy)") }
        coVerify(exactly = 0) { fixture.mutations.discardPendingChanges(any()) }
        assertTrue(bindings.containsKey(readOnly.collectionId), "the shared list stays bound and read-only")
    }

    @Test
    fun `removed share without unsynced work marks the binding unavailable and never writes`() = runTest {
        val removed = binding("readonly")
        val bindings = linkedMapOf(removed.collectionId to removed)
        val itemBindings = linkedMapOf(
            removed.collectionId to itemBinding(removed.collectionId, "readonly-item"),
        )
        val lists = mapOf(removed.collectionId to list(removed.collectionId))
        val rows = mapOf(removed.collectionId to listItem(removed.collectionId, "readonly-item"))
        val transport = RecordingTransport(absentCollections = setOf("readonly"))
        val fixture = adapter(bindings, itemBindings, lists, rows, emptyList(), transport)

        val result = fixture.adapter.syncAll()

        assertEquals(
            NextcloudFailure.Code.PERMISSION,
            (result as NextcloudSyncResult.Failure).error.code,
        )
        assertFalse(bindings.getValue("readonly").remoteAvailable, "the removal is durable")
        assertNull(
            bindings.getValue("readonly").blockedUnsyncedAt,
            "there is no local work to quarantine",
        )
        assertTrue(transport.putCalls.isEmpty())
        assertEquals(
            NextcloudListState.UNAVAILABLE,
            summaryOf(bindings.getValue("readonly")).state(),
        )
    }

    @Test
    fun `discarding stranded work after share removal restores cached owner state and releases provider metadata`() = runTest {
        val removed = binding("readonly")
        val bindings = linkedMapOf(removed.collectionId to removed)
        val itemBindings = linkedMapOf(
            removed.collectionId to itemBinding(removed.collectionId, "readonly-item"),
        )
        val lists = mapOf(removed.collectionId to list(removed.collectionId))
        val rows = mutableMapOf(
            removed.collectionId to listItem(removed.collectionId, "readonly-item")
                .copy(text = "Added while stale"),
        )
        val pending = listOf(change("readonly", "readonly-change"))
        val fixture = adapter(
            bindings,
            itemBindings,
            lists,
            rows,
            pending,
            RecordingTransport(absentCollections = setOf("readonly")),
            onImportSnapshot = { snapshot ->
                rows["readonly"] = rows.getValue("readonly").copy(text = snapshot.items.single().text)
            },
        )

        fixture.adapter.syncAll()
        assertNotNull(bindings.getValue("readonly").blockedUnsyncedAt)

        val outcome = fixture.adapter.resolveLocalWork(
            removed.collectionId,
            NextcloudLocalWorkResolution.DISCARD_LOCAL_CHANGES,
        )

        assertEquals(1L, outcome.getOrNull())
        assertEquals("Item", rows.getValue("readonly").text)
        assertTrue(fixture.pendingChanges.isEmpty())
        assertTrue(bindings.isEmpty(), "discarding an inaccessible list releases its collection binding")
        assertTrue(itemBindings.isEmpty(), "discarding an inaccessible list releases its item bindings")
        coVerify { fixture.mutations.discardPendingChanges(removed.collectionId) }
    }

    @Test
    fun `unavailable share without stranded work can be kept locally and releases provider metadata`() = runTest {
        val removed = binding("readonly").copy(remoteAvailable = false)
        val bindings = linkedMapOf(removed.collectionId to removed)
        val itemBindings = linkedMapOf(
            removed.collectionId to itemBinding(removed.collectionId, "readonly-item"),
        )
        val fixture = adapter(
            bindings,
            itemBindings,
            mapOf(removed.collectionId to list(removed.collectionId)),
            mapOf(removed.collectionId to listItem(removed.collectionId, "readonly-item")),
            emptyList(),
            RecordingTransport(absentCollections = setOf("readonly")),
        )

        val outcome = fixture.adapter.createLocalCopy(removed.collectionId)

        assertEquals(1L, outcome.getOrNull(), "the existing list becomes the local copy")
        assertTrue(bindings.isEmpty())
        assertTrue(itemBindings.isEmpty())
        coVerify { fixture.mutations.discardPendingChanges(removed.collectionId) }
        coVerify(exactly = 0) { fixture.mutations.copyListAsLocal(any(), any()) }
    }

    @Test
    fun `removed share with stranded work is released only when the user keeps it locally`() = runTest {
        val removed = binding("readonly")
        val bindings = linkedMapOf(removed.collectionId to removed)
        val itemBindings = linkedMapOf(
            removed.collectionId to itemBinding(removed.collectionId, "readonly-item"),
        )
        val lists = mapOf(removed.collectionId to list(removed.collectionId))
        val rows = mapOf(removed.collectionId to listItem(removed.collectionId, "readonly-item"))
        val pending = listOf(change("readonly", "readonly-change"))
        val transport = RecordingTransport(absentCollections = setOf("readonly"))
        val fixture = adapter(bindings, itemBindings, lists, rows, pending, transport)

        fixture.adapter.syncAll()

        assertFalse(bindings.getValue("readonly").remoteAvailable)
        assertNotNull(
            bindings.getValue("readonly").blockedUnsyncedAt,
            "the local work is preserved until the user decides",
        )
        assertTrue(bindings.containsKey("readonly"), "no automatic cleanup happens")
        assertTrue(itemBindings.containsKey("readonly"))

        val outcome = fixture.adapter.resolveLocalWork(
            removed.collectionId,
            NextcloudLocalWorkResolution.KEEP_LOCAL_COPY,
        )

        assertEquals(1L, outcome.getOrNull(), "the list itself becomes the local copy")
        coVerify(exactly = 0) { fixture.mutations.copyListAsLocal(any(), any()) }
        coVerify { fixture.mutations.discardPendingChanges(removed.collectionId) }
        assertTrue(bindings.isEmpty(), "the inaccessible association is cleaned up")
        assertTrue(itemBindings.isEmpty(), "provider item metadata goes with it")
    }

    @Test
    fun `a local copy created from a removed share is never pushed by later syncs`() = runTest {
        val removed = binding("readonly").copy(blockedUnsyncedAt = 5L, remoteAvailable = false)
        val bindings = linkedMapOf(removed.collectionId to removed)
        val itemBindings = linkedMapOf(
            removed.collectionId to itemBinding(removed.collectionId, "readonly-item"),
        )
        val lists = mapOf(removed.collectionId to list(removed.collectionId))
        val rows = mapOf(removed.collectionId to listItem(removed.collectionId, "readonly-item"))
        val transport = RecordingTransport(absentCollections = setOf("readonly"))
        val fixture = adapter(bindings, itemBindings, lists, rows, emptyList(), transport)

        fixture.adapter.resolveLocalWork(removed.collectionId, NextcloudLocalWorkResolution.KEEP_LOCAL_COPY)
        val result = fixture.adapter.syncAll()

        assertTrue(result is NextcloudSyncResult.Success)
        assertTrue(transport.putCalls.isEmpty(), "an unbound copy is never published")
        assertTrue(bindings.isEmpty(), "no binding is recreated for the copy")
    }

    @Test
    fun `kept local changes never publish after write access is restored`() = runTest {
        val stale = binding("writable").copy(
            remoteWritable = false,
            blockedUnsyncedAt = 5L,
            lastFailureCode = NextcloudFailure.Code.PERMISSION.name,
        )
        val bindings = linkedMapOf(stale.collectionId to stale)
        val itemBindings = linkedMapOf(
            stale.collectionId to itemBinding(stale.collectionId, "writable-item"),
        )
        val rows = mutableMapOf(
            stale.collectionId to listItem(stale.collectionId, "writable-item")
                .copy(text = "Added while read-only"),
        )
        val lists = mapOf(stale.collectionId to list(stale.collectionId))
        val transport = RecordingTransport()
        val fixture = adapter(
            bindings,
            itemBindings,
            lists,
            rows,
            listOf(change("writable", "writable-change")),
            transport,
            onImportSnapshot = { snapshot ->
                rows["writable"] = rows.getValue("writable").copy(text = snapshot.items.single().text)
            },
        )

        val copyId = fixture.adapter.resolveLocalWork(
            stale.collectionId,
            NextcloudLocalWorkResolution.KEEP_LOCAL_COPY,
        ).getOrThrow()

        assertEquals(99L, copyId)
        assertTrue(fixture.pendingChanges.isEmpty(), "kept work leaves the provider outbox")
        assertNull(bindings.getValue("writable").blockedUnsyncedAt)

        val result = fixture.adapter.syncAll()

        assertTrue(result is NextcloudSyncResult.Success)
        assertTrue(transport.putCalls.isEmpty())
        assertTrue(
            transport.putBodies.none { "Added while read-only" in it },
            "restored access cannot publish work already kept in a separate local list",
        )
        assertTrue(bindings.getValue("writable").remoteWritable)
        assertNull(bindings.getValue("writable").lastFailureCode)
        coVerify { fixture.mutations.copyListAsLocal(2L, any()) }
        coVerify(exactly = 0) { fixture.mutations.acknowledgePushed(listOf("writable-change")) }
    }

    @Test
    fun `syncAll propagates cancellation and skips later bindings`() = runTest {
        val first = binding("writable")
        val later = binding("readonly")
        val bindings = linkedMapOf(first.collectionId to first, later.collectionId to later)
        val itemBindings = linkedMapOf(
            first.collectionId to itemBinding(first.collectionId, "writable-item"),
            later.collectionId to itemBinding(later.collectionId, "readonly-item"),
        )
        val lists = mapOf(
            first.collectionId to list(first.collectionId),
            later.collectionId to list(later.collectionId),
        )
        val rows = mapOf(
            first.collectionId to listItem(first.collectionId, "writable-item"),
            later.collectionId to listItem(later.collectionId, "readonly-item"),
        )
        val transport = RecordingTransport(cancelOnReportCollection = first.collectionId)
        val fixture = adapter(bindings, itemBindings, lists, rows, emptyList(), transport)

        var cancellation: CancellationException? = null
        try {
            fixture.adapter.syncAll()
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertEquals("cancelled", cancellation?.message)
        assertEquals(setOf(first.collectionId), transport.reportedCollections)
    }

    // ── Per-list sync lifecycle (#1551) ─────────────────────────────────────────────────────────

    @Test
    fun `stopping sync affects only the selected list and keeps its remote association`() = runTest {
        val stopped = binding("writable")
        val other = binding("readonly")
        val bindings = linkedMapOf(stopped.collectionId to stopped, other.collectionId to other)
        val fixture = adapter(bindings, linkedMapOf(), emptyMap(), emptyMap(), emptyList(), RecordingTransport())

        assertTrue(fixture.adapter.stopSync(stopped.collectionId))

        assertFalse(bindings.getValue(stopped.collectionId).syncEnabled)
        assertEquals(stopped.remoteHref, bindings.getValue(stopped.collectionId).remoteHref)
        assertTrue(bindings.getValue(other.collectionId).syncEnabled, "another list keeps syncing")
    }

    @Test
    fun `a stopped list is skipped while other bound lists continue syncing`() = runTest {
        val stopped = binding("readonly").copy(syncEnabled = false)
        val active = binding("writable")
        val bindings = linkedMapOf(stopped.collectionId to stopped, active.collectionId to active)
        val itemBindings = linkedMapOf(
            stopped.collectionId to itemBinding(stopped.collectionId, "readonly-item"),
            active.collectionId to itemBinding(active.collectionId, "writable-item"),
        )
        val lists = mapOf(
            stopped.collectionId to list(stopped.collectionId),
            active.collectionId to list(active.collectionId),
        )
        val rows = mapOf(
            stopped.collectionId to listItem(stopped.collectionId, "readonly-item"),
            active.collectionId to listItem(active.collectionId, "writable-item"),
        )
        val pending = listOf(
            change("readonly", "readonly-change"),
            change("writable", "writable-change"),
        )
        val transport = RecordingTransport()
        val fixture = adapter(bindings, itemBindings, lists, rows, pending, transport)

        val result = fixture.adapter.syncAll()

        assertTrue(result is NextcloudSyncResult.Success)
        assertEquals(setOf("writable"), transport.reportedCollections, "only the enabled list is synchronized")
        coVerify(exactly = 0) { fixture.mutations.acknowledgePushed(listOf("readonly-change")) }
        coVerify { fixture.mutations.acknowledgePushed(listOf("writable-change")) }
    }

    @Test
    fun `a stopped list ignores a targeted sync`() = runTest {
        val stopped = binding("writable").copy(syncEnabled = false)
        val bindings = linkedMapOf(stopped.collectionId to stopped)
        val transport = RecordingTransport()
        val fixture = adapter(
            bindings,
            linkedMapOf(stopped.collectionId to itemBinding(stopped.collectionId, "writable-item")),
            mapOf(stopped.collectionId to list(stopped.collectionId)),
            mapOf(stopped.collectionId to listItem(stopped.collectionId, "writable-item")),
            listOf(change("writable", "writable-change")),
            transport,
        )

        val result = fixture.adapter.syncCollection(stopped.collectionId)

        assertTrue(result is NextcloudSyncResult.Success)
        assertTrue(transport.reportedCollections.isEmpty())
    }

    @Test
    fun `resuming sync reuses the retained association and creates no second collection`() = runTest {
        val stopped = binding("writable").copy(syncEnabled = false)
        val originalHref = stopped.remoteHref
        val bindings = linkedMapOf(stopped.collectionId to stopped)
        val transport = RecordingTransport()
        val fixture = adapter(
            bindings,
            linkedMapOf(stopped.collectionId to itemBinding(stopped.collectionId, "writable-item")),
            mapOf(stopped.collectionId to list(stopped.collectionId)),
            mapOf(stopped.collectionId to listItem(stopped.collectionId, "writable-item")),
            emptyList(),
            transport,
        )

        val result = fixture.adapter.resumeSync(stopped.collectionId)

        assertTrue(result is NextcloudSyncResult.Success)
        assertTrue(bindings.getValue(stopped.collectionId).syncEnabled)
        assertEquals(originalHref, bindings.getValue(stopped.collectionId).remoteHref)
        assertEquals(setOf("writable"), transport.reportedCollections)
        assertTrue(transport.mkcalendarCalls.isEmpty(), "resume must not create a remote collection")
    }

    @Test
    fun `repeated stop and resume operations are idempotent`() = runTest {
        val binding = binding("writable")
        val bindings = linkedMapOf(binding.collectionId to binding)
        val transport = RecordingTransport()
        val fixture = adapter(
            bindings,
            linkedMapOf(binding.collectionId to itemBinding(binding.collectionId, "writable-item")),
            mapOf(binding.collectionId to list(binding.collectionId)),
            mapOf(binding.collectionId to listItem(binding.collectionId, "writable-item")),
            emptyList(),
            transport,
        )

        fixture.adapter.stopSync(binding.collectionId)
        val firstStop = bindings.getValue(binding.collectionId)
        fixture.adapter.stopSync(binding.collectionId)

        assertEquals(firstStop, bindings.getValue(binding.collectionId), "a second stop changes nothing")
        assertEquals(false, firstStop.syncEnabled)

        fixture.adapter.resumeSync(binding.collectionId)
        fixture.adapter.resumeSync(binding.collectionId)

        assertEquals(true, bindings.getValue(binding.collectionId).syncEnabled)
        assertEquals(binding.remoteHref, bindings.getValue(binding.collectionId).remoteHref)
        assertTrue(transport.mkcalendarCalls.isEmpty())
    }

    @Test
    fun `a successful sync clears a recorded failure`() = runTest {
        val binding = binding("writable").copy(
            lastFailureCode = NextcloudFailure.Code.NETWORK.name,
            lastFailureAt = 1L,
        )
        val bindings = linkedMapOf(binding.collectionId to binding)
        val transport = RecordingTransport()
        val fixture = adapter(
            bindings,
            linkedMapOf(binding.collectionId to itemBinding(binding.collectionId, "writable-item")),
            mapOf(binding.collectionId to list(binding.collectionId)),
            mapOf(binding.collectionId to listItem(binding.collectionId, "writable-item")),
            emptyList(),
            transport,
        )

        fixture.adapter.syncAll()

        assertNull(bindings.getValue(binding.collectionId).lastFailureCode)
    }

    @Test
    fun `a failed sync records the failure for that list only`() = runTest {
        val failing = binding("readonly")
        val healthy = binding("writable")
        val bindings = linkedMapOf(failing.collectionId to failing, healthy.collectionId to healthy)
        val itemBindings = linkedMapOf(
            failing.collectionId to itemBinding(failing.collectionId, "readonly-item"),
            healthy.collectionId to itemBinding(healthy.collectionId, "writable-item"),
        )
        val lists = mapOf(
            failing.collectionId to list(failing.collectionId),
            healthy.collectionId to list(healthy.collectionId),
        )
        val rows = mapOf(
            failing.collectionId to listItem(failing.collectionId, "readonly-item"),
            healthy.collectionId to listItem(healthy.collectionId, "writable-item"),
        )
        val transport = RecordingTransport()
        val fixture = adapter(bindings, itemBindings, lists, rows, emptyList(), transport)

        fixture.adapter.syncAll()

        assertEquals(
            NextcloudFailure.Code.PERMISSION.name,
            bindings.getValue(failing.collectionId).lastFailureCode,
        )
        assertNull(bindings.getValue(healthy.collectionId).lastFailureCode)
    }

    @Test
    fun `a stop that lands during an in-flight sync survives the reconciliation`() = runTest {
        val binding = binding("writable")
        val bindings = linkedMapOf(binding.collectionId to binding)
        val gate = CompletableDeferred<Unit>()
        val transport = RecordingTransport(reportGate = gate)
        val fixture = adapter(
            bindings,
            linkedMapOf(binding.collectionId to itemBinding(binding.collectionId, "writable-item")),
            mapOf(binding.collectionId to list(binding.collectionId)),
            mapOf(binding.collectionId to listItem(binding.collectionId, "writable-item")),
            emptyList(),
            transport,
        )

        val sync = launch { fixture.adapter.syncAll() }
        transport.reportStarted.await()   // the reconciliation is now in flight and holds the lock

        val stop = launch { fixture.adapter.stopSync(binding.collectionId) }
        advanceUntilIdle()
        assertFalse(stop.isCompleted, "Stop waits for the in-flight reconciliation instead of racing it")

        gate.complete(Unit)               // let the reconciliation finish
        sync.join()
        stop.join()

        assertTrue(stop.isCompleted)
        assertFalse(
            bindings.getValue(binding.collectionId).syncEnabled,
            "the durable binding stays stopped after the in-flight reconciliation persists",
        )
        assertEquals(binding.remoteHref, bindings.getValue(binding.collectionId).remoteHref)

        // A later trigger must skip the stopped list entirely.
        val reportsBefore = transport.reportedCollections.size
        val result = fixture.adapter.syncAll()
        assertTrue(result is NextcloudSyncResult.Success)
        assertEquals(reportsBefore, transport.reportedCollections.size)
    }

    @Test
    fun `a reconciliation that starts after a stop leaves the stopped binding alone`() = runTest {
        val binding = binding("writable")
        val bindings = linkedMapOf(binding.collectionId to binding)
        val transport = RecordingTransport()
        val fixture = adapter(
            bindings,
            linkedMapOf(binding.collectionId to itemBinding(binding.collectionId, "writable-item")),
            mapOf(binding.collectionId to list(binding.collectionId)),
            mapOf(binding.collectionId to listItem(binding.collectionId, "writable-item")),
            emptyList(),
            transport,
        )

        fixture.adapter.stopSync(binding.collectionId)
        // A caller that snapshotted the enabled binding before the stop must still not sync it.
        val result = fixture.adapter.syncCollection(binding.collectionId)

        assertTrue(result is NextcloudSyncResult.Success)
        assertTrue(transport.reportedCollections.isEmpty())
        assertFalse(bindings.getValue(binding.collectionId).syncEnabled)
    }

    @Test
    fun `push preserves multiline item descriptions in VTODO`() = runTest {
        val binding = binding("writable")
        val description = "line one\nhttps://example.com/a?query=full\nline three"
        val transport = RecordingTransport()
        val fixture = adapter(
            linkedMapOf(binding.collectionId to binding),
            linkedMapOf(binding.collectionId to itemBinding(binding.collectionId, "writable-item")),
            mapOf(binding.collectionId to list(binding.collectionId)),
            mapOf(
                binding.collectionId to listItem(binding.collectionId, "writable-item").copy(
                    description = description,
                    descriptionLogicalClock = 1L,
                    descriptionStampActorId = "local",
                ),
            ),
            emptyList(),
            transport,
        )

        val result = fixture.adapter.syncAll()

        assertTrue(result is NextcloudSyncResult.Success)
        val body = transport.putBodies.last { it.contains("BEGIN:VTODO") }
        assertEquals(description, VTodoDocument.parse(body).decoded("DESCRIPTION"))
    }

    @Test
    fun `first sync backfills a bound description without destructive push`() = runTest {
        val binding = binding("writable")
        val description = "legacy line\nhttps://example.com/legacy?full=true\nlast line"
        val remoteDocument = VTodoDocument.new(
            uid = "writable-item",
            summary = "Item",
            checked = false,
            dueAt = null,
            parentUid = null,
            orderKey = "0",
            description = description,
        ).also { it.replaceSingle("X-NEXTCLOUD-UNKNOWN", "keep") }.render()
        val transport = RecordingTransport(reportDocument = { remoteDocument })
        val fixture = adapter(
            linkedMapOf(binding.collectionId to binding),
            linkedMapOf(
                binding.collectionId to itemBinding(binding.collectionId, "writable-item")
                    .copy(rawVtodo = remoteDocument),
            ),
            mapOf(binding.collectionId to list(binding.collectionId)),
            mapOf(binding.collectionId to listItem(binding.collectionId, "writable-item")),
            emptyList(),
            transport,
        )

        val result = fixture.adapter.syncAll()

        assertTrue(result is NextcloudSyncResult.Success)
        assertTrue(
            transport.putBodies.all { VTodoDocument.parse(it).decoded("DESCRIPTION") == description },
            "an upgrade sync must never PUT a bound VTODO with DESCRIPTION removed",
        )
        coVerify {
            fixture.mutations.importSnapshot(
                match { snapshot ->
                    snapshot.items.single().description == description &&
                        snapshot.items.single().descriptionStamp.logicalClock > 0L &&
                        snapshot.items.single().descriptionStamp.actorId == "nextcloud-caldav"
                },
                any(),
            )
        }
    }

    @Test
    fun `initialized local description clear removes remote DESCRIPTION but keeps unknown properties`() = runTest {
        val binding = binding("writable")
        val remoteDocument = VTodoDocument.new(
            uid = "writable-item",
            summary = "Item",
            checked = false,
            dueAt = null,
            parentUid = null,
            orderKey = "0",
            description = "remove me\nhttps://example.com/remove?full=true",
        ).also { it.replaceSingle("X-NEXTCLOUD-UNKNOWN", "keep") }.render()
        val transport = RecordingTransport()
        val fixture = adapter(
            linkedMapOf(binding.collectionId to binding),
            linkedMapOf(
                binding.collectionId to itemBinding(binding.collectionId, "writable-item")
                    .copy(rawVtodo = remoteDocument),
            ),
            mapOf(binding.collectionId to list(binding.collectionId)),
            mapOf(
                binding.collectionId to listItem(binding.collectionId, "writable-item").copy(
                    description = "",
                    descriptionLogicalClock = 1L,
                    descriptionStampActorId = "local",
                ),
            ),
            emptyList(),
            transport,
        )

        val result = fixture.adapter.syncAll()

        assertTrue(result is NextcloudSyncResult.Success)
        val body = transport.putBodies.last { it.contains("BEGIN:VTODO") }
        assertNull(VTodoDocument.parse(body).decoded("DESCRIPTION"))
        assertTrue(body.contains("X-NEXTCLOUD-UNKNOWN:keep"))
    }

    @Test
    fun `pull imports a changed remote description with its own version stamp`() = runTest {
        val binding = binding("writable")
        val description = "remote line\nhttps://example.com/remote?query=full"
        val transport = RecordingTransport(
            reportDocument = {
                VTodoDocument.new(
                    uid = it,
                    summary = "Item",
                    checked = false,
                    dueAt = null,
                    parentUid = null,
                    orderKey = "0",
                    description = description,
                ).render()
            },
        )
        val fixture = adapter(
            linkedMapOf(binding.collectionId to binding),
            linkedMapOf(binding.collectionId to itemBinding(binding.collectionId, "writable-item").copy(etag = "\"old-etag\"")),
            mapOf(binding.collectionId to list(binding.collectionId)),
            mapOf(binding.collectionId to listItem(binding.collectionId, "writable-item")),
            emptyList(),
            transport,
        )

        fixture.adapter.syncAll()

        coVerify {
            fixture.mutations.importSnapshot(
                match { snapshot ->
                    snapshot.items.single().description == description &&
                        snapshot.items.single().descriptionStamp.actorId == "nextcloud-caldav"
                },
                any(),
            )
        }
    }

    // ── First-time binding rollback (#1551) ─────────────────────────────────────────────────────

    @Test
    fun `a failed first push leaves no partial binding for a newly created collection`() = runTest {
        val target = list("writable")
        val bindings = linkedMapOf<String, NextcloudCollectionBindingEntity>()
        val itemBindings = linkedMapOf<String, NextcloudItemBindingEntity>()
        val transport = RecordingTransport(failPut = true)
        val fixture = adapter(
            bindings,
            itemBindings,
            mapOf(target.collectionId to target),
            mapOf(target.collectionId to listItem(target.collectionId, "writable-item")),
            emptyList(),
            transport,
        )
        coEvery { fixture.listNameDao.getById(any()) } returns target

        val result = fixture.adapter.publishCollection(target.id)

        assertTrue(result.isFailure)
        assertEquals(NextcloudFailure.Code.SERVER, (result.exceptionOrNull() as NextcloudConnectionException).code)
        assertEquals(1, transport.mkcalendarCalls.size, "the remote collection really was created first")
        assertTrue(bindings.isEmpty(), "no collection binding may remain after a failed first push")
        assertTrue(itemBindings.isEmpty(), "no item bindings may remain after a failed first push")
    }

    @Test
    fun `a failed push for an already bound list keeps its binding`() = runTest {
        val target = list("writable")
        val existing = binding(target.collectionId)
        val bindings = linkedMapOf(existing.collectionId to existing)
        val itemBindings = linkedMapOf(
            // A stale remote copy so this sync really pushes and then fails.
            existing.collectionId to itemBinding(existing.collectionId, "writable-item")
                .copy(rawVtodo = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"),
        )
        val transport = RecordingTransport(failPut = true)
        val fixture = adapter(
            bindings,
            itemBindings,
            mapOf(target.collectionId to target),
            mapOf(target.collectionId to listItem(target.collectionId, "writable-item")),
            emptyList(),
            transport,
        )
        coEvery { fixture.listNameDao.getById(any()) } returns target

        val result = fixture.adapter.publishCollection(target.id)

        assertTrue(result.isFailure)
        assertEquals(existing, bindings.getValue(existing.collectionId), "a pre-existing binding is never rolled back")
        assertEquals(1, itemBindings.size)
        assertTrue(transport.mkcalendarCalls.isEmpty(), "an existing association never creates another collection")
    }

    @Test
    fun `existing all-day item rewrite keeps compatible date semantics`() = runTest {
        val target = list("writable")
        val existing = binding(target.collectionId)
        val allDay = """
            BEGIN:VCALENDAR
            BEGIN:VTODO
            UID:writable-item
            DTSTART;VALUE=DATE:20260930
            DUE;VALUE=DATE:20260930
            SUMMARY:Remote title
            STATUS:NEEDS-ACTION
            X-NEXTCLOUD-UNKNOWN:keep-me
            END:VTODO
            END:VCALENDAR
        """.trimIndent()
        val bindings = linkedMapOf(existing.collectionId to existing)
        val itemBindings = linkedMapOf(
            existing.collectionId to itemBinding(existing.collectionId, "writable-item")
                .copy(rawVtodo = allDay),
        )
        val transport = RecordingTransport(reportDocument = { allDay })
        val fixture = adapter(
            bindings,
            itemBindings,
            mapOf(target.collectionId to target),
            mapOf(
                target.collectionId to listItem(target.collectionId, "writable-item")
                    .copy(text = "Local title", dueAt = parseUtcMillis("20260930")),
            ),
            emptyList(),
            transport,
        )

        val result = fixture.adapter.syncCollection(existing.collectionId)

        assertTrue(result is NextcloudSyncResult.Success)
        assertEquals(2, transport.putBodies.size)
        transport.putBodies.forEach { rendered ->
            assertTrue(rendered.contains("DTSTART;VALUE=DATE:20260930"))
            assertTrue(rendered.contains("DUE;VALUE=DATE:20260930"))
            assertTrue(rendered.contains("X-NEXTCLOUD-UNKNOWN:keep-me"))
            assertTrue(!rendered.contains("DUE:20260930T000000Z"))
        }
    }

    @Test
    fun `a concurrent sync cannot observe or resurrect a provisional first-time binding`() = runTest {
        val target = list("writable")
        val bindings = linkedMapOf<String, NextcloudCollectionBindingEntity>()
        val itemBindings = linkedMapOf<String, NextcloudItemBindingEntity>()
        val putGate = CompletableDeferred<Unit>()
        val transport = RecordingTransport(failPut = true, putGate = putGate)
        val fixture = adapter(
            bindings,
            itemBindings,
            mapOf(target.collectionId to target),
            mapOf(target.collectionId to listItem(target.collectionId, "writable-item")),
            emptyList(),
            transport,
        )
        coEvery { fixture.listNameDao.getById(any()) } returns target

        val publish = async { fixture.adapter.publishCollection(target.id) }
        transport.putStarted.await()   // discovery and MKCALENDAR succeeded, the initial PUT is in flight

        assertTrue(
            bindings.isEmpty(),
            "a first-time association must not be durable before its initial push succeeds",
        )

        // The background path runs while the publish is still in flight; it has nothing to observe.
        val background = launch { fixture.adapter.syncAll() }
        advanceUntilIdle()
        assertTrue(
            transport.reportedCollections.isEmpty(),
            "the background sync must not reconcile the provisional collection",
        )

        putGate.complete(Unit)         // now let the initial PUT fail
        val published = publish.await()
        background.join()

        assertTrue(published.isFailure)
        assertEquals(NextcloudFailure.Code.SERVER, (published.exceptionOrNull() as NextcloudConnectionException).code)
        assertEquals(1, transport.mkcalendarCalls.size)
        assertTrue(bindings.isEmpty(), "no collection binding may survive a failed first-time publish")
        assertTrue(itemBindings.isEmpty(), "no item binding from the failed attempt may survive")
        assertEquals(1, transport.putCalls.size, "only the initial push attempted a write")
        assertTrue(transport.reportedCollections.isEmpty(), "no REPORT was ever issued for that collection")
        coVerify(exactly = 0) { fixture.listNameDao.upsert(any()) }
    }

    private data class Fixture(
        val adapter: NextcloudSyncAdapter,
        val mutations: ListMutationRepository,
        val listNameDao: ListNameDao,
        val pendingChanges: MutableList<ListChange>,
    )

    private fun adapter(
        bindings: MutableMap<String, NextcloudCollectionBindingEntity>,
        itemBindings: MutableMap<String, NextcloudItemBindingEntity>,
        lists: Map<String, ListNameEntity>,
        rows: Map<String, ListItemEntity>,
        pending: List<ListChange>,
        transport: RecordingTransport,
        onImportSnapshot: suspend (SharedCollectionSnapshot) -> Unit = {},
    ): Fixture {
        val pendingChanges = pending.toMutableList()
        val accountStore = mockk<NextcloudCredentialStore>()
        every { accountStore.read() } returns NextcloudAccountCredentials(
            NextcloudAccount("https://cloud.example", "alice"),
            "app-password",
        )
        val collectionDao = mockk<NextcloudCollectionBindingDao>()
        coEvery { collectionDao.getAll() } answers { bindings.values.toList() }
        coEvery { collectionDao.get(any()) } answers { bindings[firstArg()] }
        coEvery { collectionDao.upsert(any()) } answers { bindings[firstArg<NextcloudCollectionBindingEntity>().collectionId] = firstArg() }
        coEvery { collectionDao.recordSyncOutcome(any(), any(), any()) } answers {
            val binding = bindings[firstArg<String>()]
            if (binding != null) {
                bindings[firstArg()] = binding.copy(lastFailureCode = secondArg<String?>())
            }
        }
        coEvery { collectionDao.setSyncEnabled(any(), any(), any()) } answers {
            val binding = bindings[firstArg<String>()]
            if (binding != null) {
                bindings[firstArg()] = binding.copy(syncEnabled = secondArg<Boolean>())
            }
        }
        coEvery { collectionDao.delete(any()) } answers { bindings.remove(firstArg<String>()) }

        val itemDao = mockk<NextcloudItemBindingDao>()
        coEvery { itemDao.getAll(any()) } answers { itemBindings.values.filter { it.collectionId == firstArg() } }
        coEvery { itemDao.upsert(any()) } answers { itemBindings[firstArg<NextcloudItemBindingEntity>().collectionId] = firstArg() }
        coEvery { itemDao.delete(any()) } answers { itemBindings.remove(firstArg<String>()) }
        coEvery { itemDao.deleteForCollection(any()) } answers {
            val collectionId = firstArg<String>()
            itemBindings.keys.filter { itemBindings.getValue(it).collectionId == collectionId }
                .forEach { itemBindings.remove(it) }
        }

        val listItemDao = mockk<ListItemDao>()
        coEvery { listItemDao.getByItemId(any()) } returns null
        coEvery { listItemDao.getAllByListAnyLifecycle(any()) } answers { listOfNotNull(rows.values.firstOrNull { it.listId == firstArg<Long>() }) }

        val listNameDao = mockk<ListNameDao>()
        coEvery { listNameDao.getByCollectionId(any()) } answers { lists[firstArg()] }

        val mutations = mockk<ListMutationRepository>()
        coEvery { mutations.pendingChanges() } answers { pendingChanges.toList() }
        coEvery { mutations.importSnapshot(any(), any(), any()) } coAnswers {
            onImportSnapshot(firstArg())
            mockk(relaxed = true)
        }
        coEvery { mutations.acknowledgePushed(any()) } coAnswers {
            val pushedIds = firstArg<List<String>>().toSet()
            pendingChanges.removeAll { it.changeId in pushedIds }
            Unit
        }
        coEvery { mutations.discardPendingChanges(any()) } coAnswers {
            val collectionId = firstArg<String>()
            pendingChanges.removeAll { it.collectionId == collectionId }
            Unit
        }
        coEvery { mutations.copyListAsLocal(any(), any()) } returns 99L
        return Fixture(
            adapter = NextcloudSyncAdapter(
                accountStore,
                transport,
                collectionDao,
                itemDao,
                listItemDao,
                listNameDao,
                mutations,
            ),
            mutations = mutations,
            listNameDao = listNameDao,
            pendingChanges = pendingChanges,
        )
    }

    private fun binding(id: String) = NextcloudCollectionBindingEntity(
        collectionId = id,
        remoteHref = "https://cloud.example/calendars/$id/",
        remoteTitle = id,
        remoteEtag = null,
        remoteLogicalClock = 1L,
        updatedAt = 1L,
    )

    /** The projection the Lists surfaces render, derived exactly like the DAO query does. */
    private fun summaryOf(binding: NextcloudCollectionBindingEntity) = NextcloudListSyncSummary(
        collectionId = binding.collectionId,
        remoteHref = binding.remoteHref,
        syncEnabled = binding.syncEnabled,
        lastFailureCode = binding.lastFailureCode,
        remoteWritable = binding.remoteWritable,
        remoteAvailable = binding.remoteAvailable,
        blockedUnsyncedAt = binding.blockedUnsyncedAt,
    )
    private fun change(collectionId: String, changeId: String) = ListChange(
        changeId = changeId,
        collectionId = collectionId,
        targetId = "$collectionId-item",
        actorId = "test",
        sourceSequence = 1L,
        stamp = VersionStamp(1L, "test"),
        operation = ListChangeOperation.SET_ITEM_TEXT,
    )

    private fun list(collectionId: String) = ListNameEntity(
        id = if (collectionId == "readonly") 1L else 2L,
        name = collectionId,
        collectionId = collectionId,
        canonicalTitle = collectionId,
    )

    private fun document(uid: String): String = VTodoDocument.new(
        uid = uid,
        summary = "Item",
        checked = false,
        dueAt = null,
        parentUid = null,
        orderKey = "0",
    ).render()

    private fun itemBinding(collectionId: String, uid: String) = NextcloudItemBindingEntity(
        itemId = "$collectionId-item",
        collectionId = collectionId,
        remoteUid = uid,
        remoteHref = "https://cloud.example/calendars/$collectionId/$uid.ics",
        etag = "\"$uid-etag\"",
        rawVtodo = document(uid),
        remoteLogicalClock = 1L,
        deletedRemotely = false,
        updatedAt = 1L,
    )

    private fun listItem(collectionId: String, uid: String) = ListItemEntity(
        id = if (collectionId == "readonly") 1L else 2L,
        listId = if (collectionId == "readonly") 1L else 2L,
        text = if (collectionId == "readonly") "Local change" else "Item",
        itemId = "$collectionId-item",
        collectionId = collectionId,
    )
    private class RecordingTransport(
        private val cancelOnReportCollection: String? = null,
        private val failPut: Boolean = false,
        /** Held open by the test to suspend a reconciliation while it is in flight. */
        private val reportGate: CompletableDeferred<Unit>? = null,
        /** Held open by the test to suspend an initial push while it is in flight. */
        private val putGate: CompletableDeferred<Unit>? = null,
        private val reportDocument: ((String) -> String)? = null,
        private val readOnlyCollections: Set<String> = emptySet(),
        /** Collections the owner removed: they no longer appear in discovery (#1548). */
        private val absentCollections: Set<String> = emptySet(),
    ) : CalDavTransport {
        val reportedCollections = mutableSetOf<String>()
        val mkcalendarCalls = mutableListOf<String>()
        val putCalls = mutableListOf<String>()
        val putBodies = mutableListOf<String>()

        /** Completes once a REPORT is in flight, so the test never has to sleep. */
        val reportStarted = CompletableDeferred<String>()

        /** Completes once a PUT is in flight, so the test never has to sleep. */
        val putStarted = CompletableDeferred<String>()

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): CalDavResponse {
            val collection = url.substringAfter("/calendars/").substringBefore('/')
            return when {
                method == "PUT" -> {
                    putCalls += url
                    putBodies += body.orEmpty()
                    putStarted.complete(url)
                    putGate?.await()
                    when {
                        failPut -> CalDavResponse(500, emptyMap(), "", url)
                        collection == "readonly" -> CalDavResponse(403, emptyMap(), "", url)
                        else -> CalDavResponse(204, emptyMap(), "", url)
                    }
                }
                method == "GET" ->
                    CalDavResponse(200, emptyMap(), "", "https://cloud.example/remote.php/dav")
                method == "PROPFIND" && url.endsWith("/remote.php/dav") ->
                    response(
                        """
                        <d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "PROPFIND" && url.contains("/principals/users/alice/") ->
                    response(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop><c:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></c:calendar-home-set></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "PROPFIND" && url.contains("/calendars/alice/") -> response(discoveryXml())
                method == "MKCALENDAR" -> {
                    mkcalendarCalls += url
                    CalDavResponse(201, emptyMap(), "", url)
                }
                method == "REPORT" -> {
                    reportedCollections += collection
                    reportStarted.complete(collection)
                    reportGate?.await()
                    if (collection == cancelOnReportCollection) {
                        throw CancellationException("cancelled")
                    }
                    val uid = "$collection-item"
                    val document = reportDocument?.invoke(uid) ?: remoteDocument(uid)
                    response(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/calendars/$collection/$uid.ics</d:href><d:propstat><d:prop><d:getetag>\"$uid-etag\"</d:getetag><c:calendar-data>${document.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</c:calendar-data></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                }
                else -> error("Unexpected CalDAV request: $method $url")
            }
        }

        private fun response(xml: String) = CalDavResponse(207, emptyMap(), xml.trimIndent(), "")

        /**
         * Discovery as Nextcloud serves it: every collection the sharee can see. A collection listed
         * in [absentCollections] is left out, which is what a removed share looks like (#1548).
         */
        private fun discoveryXml(): String = buildString {
            append("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" xmlns:oc=\"http://owncloud.org/ns\">")
            listOf("readonly", "writable")
                .filter { it !in absentCollections }
                .forEach { append(calendarResponse(it)) }
            append("</d:multistatus>")
        }

        private fun calendarResponse(id: String): String {
            val capability = if (id in readOnlyCollections) {
                "<oc:read-only>1</oc:read-only><d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:write-properties/></d:privilege></d:current-user-privilege-set>"
            } else {
                "<oc:read-only/><d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:write/></d:privilege><d:privilege><d:write-content/></d:privilege></d:current-user-privilege-set>"
            }
            return "<d:response><d:href>/calendars/$id/</d:href><d:propstat><d:prop><d:displayname>$id</d:displayname>" +
                "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>" +
                "<c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>" +
                "$capability</d:prop></d:propstat></d:response>"
        }
        private fun remoteDocument(uid: String): String = VTodoDocument.new(
            uid = uid,
            summary = "Item",
            checked = false,
            dueAt = null,
            parentUid = null,
            orderKey = "0",
        ).render()
    }

}
