package com.kernel.ai.core.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.lists.EffectiveHierarchyNormalizer
import com.kernel.ai.core.memory.lists.HierarchyItem
import com.kernel.ai.core.memory.lists.ListChange
import com.kernel.ai.core.memory.lists.ListChangeOperation
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.ListPackageExchange
import com.kernel.ai.core.memory.lists.VersionStamp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bounded paired-device proof for #1494.
 *
 * Each [Peer] is a separate Room store with its own actor identity. The transport is deliberately
 * disposable: it moves the existing #1493 encrypted snapshot or the existing #1492 pending changes
 * between stores, then the receiving repository applies them through its authoritative seam.
 */
class ListPairedDeviceConvergenceAndroidTest {
    private lateinit var first: Peer
    private lateinit var second: Peer

    @Before
    fun setUp() {
        first = peer()
        second = peer()
    }

    @After
    fun tearDown() {
        first.database.close()
        second.database.close()
    }

    @Test
    fun `paired peers converge bidirectionally through offline edits conflicts replay delete and restore`() = runBlocking {
        val firstListId = first.repository.createCollection("Groceries")
        val sharedItemId = first.repository.addItem(firstListId, "Milk")
        val staleItemId = first.repository.addItem(firstListId, "Coffee")
        val collectionId = collectionId(first, firstListId)

        shipSnapshot(first, second, firstListId)
        acknowledgeAll(first)
        val secondListId = listId(second, collectionId)
        val sharedItemStableId = stableItemId(first, sharedItemId)
        val staleItemStableId = stableItemId(first, staleItemId)

        // Device-local presentation and reminder state must not be overwritten by peer delivery.
        val localList = requireNotNull(second.database.listNameDao().getById(secondListId))
        second.database.listNameDao().upsert(localList.copy(pinned = true, archivedAt = 123L))
        val localSharedItem = requireNotNull(second.database.listItemDao().getByItemId(sharedItemStableId))
        second.database.listItemDao().upsert(
            localSharedItem.copy(isFavourite = true, notificationTime = System.currentTimeMillis() + 60_000L),
        )

        // Both peers diverge while the transport is unavailable, then exchange independent additions.
        first.repository.addItem(firstListId, "Bread")
        second.repository.addItem(secondListId, "Eggs")
        val firstAddition = first.repository.pendingChanges()
        val secondAddition = second.repository.pendingChanges()
        sendChanges(first, second, firstAddition)
        sendChanges(second, first, secondAddition)

        val duplicateBefore = sharedState(second, secondListId)
        for (change in firstAddition) {
            second.repository.applyRemote(change)
        }
        assertEquals("duplicate delivery must be a no-op", duplicateBefore, sharedState(second, secondListId))

        // Independent fields on the same item survive the exchange.
        first.repository.setItemText(sharedItemId, "Milk and bread")
        second.repository.setItemDueAt(
            requireNotNull(second.database.listItemDao().getByItemId(sharedItemStableId)).id,
            1_725_000_000_000L,
        )
        second.repository.setItemChecked(
            requireNotNull(second.database.listItemDao().getByItemId(sharedItemStableId)).id,
            true,
        )
        val firstIndependent = first.repository.pendingChanges()
        val secondIndependent = second.repository.pendingChanges()
        sendChanges(first, second, firstIndependent)
        sendChanges(second, first, secondIndependent)

        val mergedFirstItem = requireNotNull(first.database.listItemDao().getByItemId(sharedItemStableId))
        val mergedSecondItem = requireNotNull(second.database.listItemDao().getByItemId(sharedItemStableId))
        assertEquals("Milk and bread", mergedFirstItem.text)
        assertEquals(1_725_000_000_000L, mergedFirstItem.dueAt)
        assertTrue(mergedFirstItem.checked)
        assertEquals(sharedState(first, firstListId), sharedState(second, secondListId))
        first.repository.setItemChecked(sharedItemId, false)
        val firstUncheck = first.repository.pendingChanges()
        sendChanges(first, second, firstUncheck)
        assertFalse(requireNotNull(first.database.listItemDao().getByItemId(sharedItemStableId)).checked)
        assertFalse(requireNotNull(second.database.listItemDao().getByItemId(sharedItemStableId)).checked)

        // Same-field conflict is resolved by the existing VersionStamp ordering, not delivery order.

        first.repository.setItemText(sharedItemId, "First conflict")
        second.repository.setItemText(mergedSecondItem.id, "Second conflict")
        val firstConflict = latestChange(first, sharedItemStableId, ListChangeOperation.SET_ITEM_TEXT)
        val secondConflict = latestChange(second, sharedItemStableId, ListChangeOperation.SET_ITEM_TEXT)
        val expectedText = listOf(firstConflict, secondConflict).maxWithOrNull(compareBy { it.stamp })!!.payload.text
        sendChanges(first, second, listOf(firstConflict))
        sendChanges(second, first, listOf(secondConflict))
        assertEquals(expectedText, requireNotNull(first.database.listItemDao().getByItemId(sharedItemStableId)).text)
        assertEquals(expectedText, requireNotNull(second.database.listItemDao().getByItemId(sharedItemStableId)).text)

        // A stale edit delivered after a tombstone remains hidden; explicit restore is required.
        val secondStaleItem = requireNotNull(second.database.listItemDao().getByItemId(staleItemStableId))
        second.database.listItemDao().upsert(
            secondStaleItem.copy(isFavourite = true, notificationTime = System.currentTimeMillis() + 120_000L),
        )
        second.repository.setItemText(secondStaleItem.id, "Offline stale edit")
        val staleEdit = latestChange(second, staleItemStableId, ListChangeOperation.SET_ITEM_TEXT)
        first.repository.deleteItem(staleItemId)
        val deleteChange = latestChange(first, staleItemStableId, ListChangeOperation.DELETE_ITEM)
        sendChanges(first, second, listOf(deleteChange))
        sendChanges(second, first, listOf(staleEdit))
        assertEquals(ListLifecycle.DELETED.name, requireNotNull(first.database.listItemDao().getByItemId(staleItemStableId)).lifecycle)
        assertEquals(ListLifecycle.DELETED.name, requireNotNull(second.database.listItemDao().getByItemId(staleItemStableId)).lifecycle)
        assertTrue(second.database.listItemDao().getByList(secondListId).none { it.itemId == staleItemStableId })

        first.repository.restoreItem(staleItemId)
        val restoreChange = latestChange(first, staleItemStableId, ListChangeOperation.RESTORE_ITEM)
        sendChanges(first, second, listOf(restoreChange))

        // Snapshot fallback reconciles retained field state after the tombstone/restore boundary.
        shipSnapshot(second, first, secondListId)
        shipSnapshot(first, second, firstListId)
        assertEquals(sharedState(first, firstListId), sharedState(second, secondListId))

        val preservedLocalList = requireNotNull(second.database.listNameDao().getById(secondListId))
        val preservedLocalItem = requireNotNull(second.database.listItemDao().getByItemId(staleItemStableId))
        assertTrue(preservedLocalList.pinned)
        assertEquals(123L, preservedLocalList.archivedAt)
        assertTrue(preservedLocalItem.isFavourite)
        assertTrue(requireNotNull(preservedLocalItem.notificationTime) > System.currentTimeMillis())
    }

    @Test
    fun `paired peers converge hierarchy reparent unparent sibling order and parent promotion`() = runBlocking {
        val firstListId = first.repository.createCollection("Hierarchy")
        val parentId = first.repository.addItem(firstListId, "Parent")
        val destinationId = first.repository.addItem(firstListId, "Destination")
        val firstChildId = first.repository.addItem(firstListId, "First child")
        val secondChildId = first.repository.addItem(firstListId, "Second child")
        val survivingChildId = first.repository.addItem(firstListId, "Surviving child")
        val collectionId = collectionId(first, firstListId)
        shipSnapshot(first, second, firstListId)
        acknowledgeAll(first)
        val secondListId = listId(second, collectionId)

        val parentStableId = stableItemId(first, parentId)
        val destinationStableId = stableItemId(first, destinationId)
        val firstChildStableId = stableItemId(first, firstChildId)
        val secondChildStableId = stableItemId(first, secondChildId)
        val survivingChildStableId = stableItemId(first, survivingChildId)

        first.repository.moveItem(firstChildId, parentStableId, "1")
        first.repository.moveItem(secondChildId, parentStableId, "2")
        sendChanges(first, second, first.repository.pendingChanges())
        assertEquals(
            effectiveHierarchy(first, firstListId),
            effectiveHierarchy(second, secondListId),
        )
        second.repository.moveItem(
            requireNotNull(second.database.listItemDao().getByItemId(secondChildStableId)).id,
            parentStableId,
            "3",
        )
        sendChanges(second, first, second.repository.pendingChanges())
        assertEquals(
            effectiveHierarchy(first, firstListId),
            effectiveHierarchy(second, secondListId),
        )

        // Reparent one child to another group, then promote the other child to top level.
        second.repository.moveItem(
            requireNotNull(second.database.listItemDao().getByItemId(secondChildStableId)).id,
            destinationStableId,
            "1",
        )
        sendChanges(second, first, second.repository.pendingChanges())
        first.repository.moveToTopLevel(firstChildId)
        sendChanges(first, second, first.repository.pendingChanges())

        // Delete the parent; the remaining child must be promoted rather than deleted.
        first.repository.deleteItem(parentId)
        sendChanges(first, second, first.repository.pendingChanges())

        val firstHierarchy = effectiveHierarchy(first, firstListId)
        val secondHierarchy = effectiveHierarchy(second, secondListId)
        assertEquals(firstHierarchy, secondHierarchy)
        assertFalse(firstHierarchy.containsKey(firstChildStableId))
        assertEquals(destinationStableId, firstHierarchy[secondChildStableId])
        assertFalse(firstHierarchy.containsKey(survivingChildStableId))
        assertEquals(sharedState(first, firstListId), sharedState(second, secondListId))
    }

    @Test
    fun `conflicting placements converge acyclically regardless of delivery order`() = runBlocking {
        val firstListId = first.repository.createCollection("Conflicts")
        val firstItemId = first.repository.addItem(firstListId, "A")
        val secondItemId = first.repository.addItem(firstListId, "B")
        val thirdItemId = first.repository.addItem(firstListId, "C")
        val collectionId = collectionId(first, firstListId)
        shipSnapshot(first, second, firstListId)
        acknowledgeAll(first)
        val secondListId = listId(second, collectionId)

        val firstStableId = stableItemId(first, firstItemId)
        val secondStableId = stableItemId(first, secondItemId)
        val thirdStableId = stableItemId(first, thirdItemId)

        first.repository.moveItem(firstItemId, secondStableId, "1")
        second.repository.moveItem(
            requireNotNull(second.database.listItemDao().getByItemId(secondStableId)).id,
            firstStableId,
            "1",
        )
        val firstCycle = latestChange(first, firstStableId, ListChangeOperation.SET_ITEM_PLACEMENT)
        val secondCycle = latestChange(second, secondStableId, ListChangeOperation.SET_ITEM_PLACEMENT)
        first.repository.applyRemote(secondCycle)
        second.repository.applyRemote(firstCycle)
        second.repository.applyRemote(firstCycle)

        assertEquals(effectiveHierarchy(first, firstListId), effectiveHierarchy(second, secondListId))
        assertAcyclicTwoLevel(first, firstListId)
        assertAcyclicTwoLevel(second, secondListId)

        // A concurrent grandparent candidate is normalised to the same two-level forest.
        first.repository.moveItem(firstItemId, null, "1")
        val secondItemRow = requireNotNull(second.database.listItemDao().getByItemId(secondStableId)).id
        second.repository.moveToTopLevel(secondItemRow)
        second.repository.moveItem(
            requireNotNull(second.database.listItemDao().getByItemId(thirdStableId)).id,
            secondStableId,
            "2",
        )
        val firstDepthChange = latestChange(first, firstStableId, ListChangeOperation.SET_ITEM_PLACEMENT)
        val secondResetChange = latestChange(second, secondStableId, ListChangeOperation.SET_ITEM_PLACEMENT)
        val secondDepthChange = latestChange(second, thirdStableId, ListChangeOperation.SET_ITEM_PLACEMENT)
        second.repository.applyRemote(firstDepthChange)
        first.repository.applyRemote(secondResetChange)
        first.repository.applyRemote(secondDepthChange)

        assertEquals(effectiveHierarchy(first, firstListId), effectiveHierarchy(second, secondListId))
        assertAcyclicTwoLevel(first, firstListId)
        assertAcyclicTwoLevel(second, secondListId)
    }

    @Test
    fun `collection checkpoints remain independent across interleaved local changes`() = runBlocking {
        val firstListId = first.repository.createCollection("First")
        val secondListId = first.repository.createCollection("Second")
        first.repository.addItem(firstListId, "First item")
        first.repository.addItem(secondListId, "Second item")
        first.repository.addItem(firstListId, "Later first item")
        val firstCollectionId = collectionId(first, firstListId)
        val secondCollectionId = collectionId(first, secondListId)

        val firstCollectionChanges = first.repository.pendingChanges()
            .filter { it.collectionId == firstCollectionId }
        sendChanges(first, second, firstCollectionChanges)

        assertEquals(
            3L,
            second.database.listCheckpointDao()
                .get(firstCollectionId, requireNotNull(first.database.listActorStateDao().get()).actorId)
                ?.highestContiguousSourceSequence,
        )
        assertEquals(null, second.database.listCheckpointDao().get(secondCollectionId, requireNotNull(first.database.listActorStateDao().get()).actorId))
        assertEquals(2, second.database.listItemDao().getAllByList(listId(second, firstCollectionId)).size)
    }

    private suspend fun shipSnapshot(from: Peer, to: Peer, listId: Long) {
        val raw = ListPackageExchange.export(
            from.repository.exportSnapshot(listId),
            from.repository.localActorId(),
        )
        to.repository.importSnapshot(ListPackageExchange.inspect(raw))
    }

    private suspend fun sendChanges(from: Peer, to: Peer, changes: List<ListChange>) {
        for (change in changes) {
            to.repository.applyRemote(change)
        }
        if (changes.isNotEmpty()) {
            from.repository.acknowledgePushed(changes.map { it.changeId })
        }
    }

    private suspend fun acknowledgeAll(peer: Peer) {
        peer.repository.acknowledgePushed(peer.repository.pendingChanges().map { it.changeId })
    }

    private suspend fun latestChange(peer: Peer, targetId: String, operation: ListChangeOperation): ListChange =
        peer.repository.pendingChanges()
            .filter { it.targetId == targetId && it.operation == operation }
            .maxBy { it.stamp }

    private suspend fun sharedState(peer: Peer, listId: Long): SharedState {
        val snapshot = peer.repository.exportSnapshot(listId)
        return SharedState(
            collectionId = snapshot.collectionId,
            canonicalTitle = snapshot.canonicalTitle,
            lifecycle = snapshot.lifecycle,
            items = snapshot.items.sortedBy { it.itemId }.map {
                SharedItemState(
                    itemId = it.itemId,
                    text = it.text,
                    checked = it.checked,
                    dueAt = it.dueAt,
                    parentItemId = it.parentItemId,
                    orderKey = it.orderKey,
                    lifecycle = it.lifecycle,
                    textStamp = it.textStamp,
                    checkedStamp = it.checkedStamp,
                    dueAtStamp = it.dueAtStamp,
                    placementStamp = it.placementStamp,
                    lifecycleStamp = it.lifecycleStamp,
                )
            },
        )
    }

    private suspend fun effectiveHierarchy(peer: Peer, listId: Long): Map<String, String> =
        EffectiveHierarchyNormalizer.derive(
            peer.database.listItemDao().getAllByListAnyLifecycle(listId).map {
                HierarchyItem(
                    itemId = it.itemId,
                    parentItemId = it.parentItemId,
                    orderKey = it.orderKey,
                    placementStamp = VersionStamp(it.placementLogicalClock, it.placementStampActorId),
                    active = it.lifecycle == ListLifecycle.ACTIVE.name,
                )
            },
        ).parentByChild

    private suspend fun assertAcyclicTwoLevel(peer: Peer, listId: Long) {
        val hierarchy = effectiveHierarchy(peer, listId)
        assertTrue(hierarchy.keys.none { it in hierarchy.values })
    }

    private suspend fun collectionId(peer: Peer, listId: Long): String =
        requireNotNull(peer.database.listNameDao().getById(listId)).collectionId

    private suspend fun listId(peer: Peer, collectionId: String): Long =
        requireNotNull(peer.database.listNameDao().getByCollectionId(collectionId)).id

    private suspend fun stableItemId(peer: Peer, rowId: Long): String =
        requireNotNull(peer.database.listItemDao().getById(rowId)).itemId

    private fun peer(): Peer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return Peer(database, repositoryOn(database))
    }

    private fun repositoryOn(database: KernelDatabase) = ListMutationRepository(
        database = database,
        listItemDao = database.listItemDao(),
        listNameDao = database.listNameDao(),
        actorDao = database.listActorStateDao(),
        appliedDao = database.listAppliedChangeDao(),
        changeDao = database.listChangeDao(),
        sourceDao = database.listSourceSequenceDao(),
        checkpointDao = database.listCheckpointDao(),
    )

    private data class Peer(
        val database: KernelDatabase,
        val repository: ListMutationRepository,
    )

    private data class SharedState(
        val collectionId: String,
        val canonicalTitle: String,
        val lifecycle: ListLifecycle,
        val items: List<SharedItemState>,
    )

    private data class SharedItemState(
        val itemId: String,
        val text: String,
        val checked: Boolean,
        val dueAt: Long?,
        val parentItemId: String?,
        val orderKey: String,
        val lifecycle: ListLifecycle,
        val textStamp: VersionStamp,
        val checkedStamp: VersionStamp,
        val dueAtStamp: VersionStamp,
        val placementStamp: VersionStamp,
        val lifecycleStamp: VersionStamp,
    )
}
