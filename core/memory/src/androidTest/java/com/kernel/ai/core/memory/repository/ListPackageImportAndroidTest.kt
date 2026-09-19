package com.kernel.ai.core.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.lists.EffectiveHierarchyNormalizer
import com.kernel.ai.core.memory.lists.HierarchyItem
import com.kernel.ai.core.memory.lists.ListChange
import com.kernel.ai.core.memory.lists.ListChangeOperation
import com.kernel.ai.core.memory.lists.ListChangePayload
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.ListItemLifecycleTransition
import com.kernel.ai.core.memory.lists.ListPackageException
import com.kernel.ai.core.memory.lists.ListPackageFailure
import com.kernel.ai.core.memory.lists.SharedCollectionSnapshot
import com.kernel.ai.core.memory.lists.SharedItemSnapshot
import com.kernel.ai.core.memory.lists.VersionStamp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Import goes through the same authoritative seam as any other remote state.
 *
 * Every test starts from a real export of a real list, so the snapshot under test is the one the
 * export path produces rather than a hand-written fixture.
 */
class ListPackageImportAndroidTest {
    private lateinit var database: KernelDatabase
    private lateinit var repository: ListMutationRepository
    private lateinit var remoteDatabase: KernelDatabase
    private lateinit var remoteRepository: ListMutationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        remoteDatabase = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = repositoryOn(database)
        remoteRepository = repositoryOn(remoteDatabase)
    }

    @After
    fun tearDown() {
        database.close()
        remoteDatabase.close()
    }

    @Test
    fun `an exported list imports with the same items hierarchy and order`() = runBlocking {
        val listId = remoteRepository.createCollection("groceries")
        val parent = remoteRepository.addItem(listId, "Milk")
        val firstChild = remoteRepository.addItem(listId, "Bread")
        val secondChild = remoteRepository.addItem(listId, "Eggs")
        remoteRepository.makeSubItem(firstChild, stableId(remoteDatabase, parent))
        remoteRepository.makeSubItem(secondChild, stableId(remoteDatabase, firstChild))

        val result = repository.importSnapshot(remoteRepository.exportSnapshot(listId))

        assertEquals(3, result.itemsCreated)
        assertTrue(result.collectionCreated)
        assertEquals(
            remoteHierarchy("groceries"),
            localHierarchy("groceries"),
        )
        val imported = requireNotNull(database.listNameDao().getByCollectionId(
            requireNotNull(remoteDatabase.listNameDao().getById(listId)).collectionId,
        ))
        assertEquals("groceries", imported.canonicalTitle)
        remoteDatabase.listItemDao().getAllByListAnyLifecycle(listId)
            .sortedBy { it.itemId }
            .forEach { remote ->
                val local = requireNotNull(localRowByStableId(imported.id, remote.itemId))
                assertEquals(remote.text, local.text)
                assertEquals(remote.checked, local.checked)
                assertEquals(remote.parentItemId, local.parentItemId)
                assertEquals(remote.orderKey, local.orderKey)
                assertEquals(remote.lifecycle, local.lifecycle)
            }
    }

    @Test
    fun `importing the same package twice changes nothing`() = runBlocking {
        val listId = remoteRepository.createCollection("groceries")
        remoteRepository.addItem(listId, "Milk")
        remoteRepository.addItem(listId, "Bread")
        val snapshot = remoteRepository.exportSnapshot(listId)

        val first = repository.importSnapshot(snapshot)
        val pendingAfterFirst = database.listChangeDao().getPending().size
        val rowsAfterFirst = requireNotNull(database.listNameDao().getByCollectionId(snapshot.collectionId))
            .let { list -> database.listItemDao().getAllByListAnyLifecycle(list.id) }

        val second = repository.importSnapshot(snapshot)

        assertFalse(second.collectionCreated)
        assertEquals(0, second.itemsCreated)
        assertEquals(0, second.itemsUpdated)
        assertTrue(second.checkedStateMutation.checkedIds.isEmpty())
        assertTrue(second.checkedStateMutation.uncheckedIds.isEmpty())
        assertEquals(pendingAfterFirst, database.listChangeDao().getPending().size)
        val rowsAfterSecond = database.listItemDao().getAllByListAnyLifecycle(first.listId)
        assertEquals(rowsAfterFirst, rowsAfterSecond)
    }

    @Test
    fun `hidden tombstones import and an explicit restore reactivates the same identity`() = runBlocking {
        val listId = remoteRepository.createCollection("groceries")
        val item = remoteRepository.addItem(listId, "Milk")
        val stableItemId = stableId(remoteDatabase, item)
        val initialSnapshot = remoteRepository.exportSnapshot(listId)
        repository.importSnapshot(initialSnapshot)

        val importedList = requireNotNull(database.listNameDao().getByCollectionId(initialSnapshot.collectionId))
        val reminderAt = System.currentTimeMillis() + 60_000L
        val localBeforeDelete = requireNotNull(localRowByStableId(importedList.id, stableItemId))
        database.listItemDao().upsert(
            localBeforeDelete.copy(notificationTime = reminderAt),
        )

        remoteRepository.deleteItem(item)
        val snapshot = remoteRepository.exportSnapshot(listId)
        val tombstoneImport = repository.importSnapshot(snapshot)

        val tombstone = requireNotNull(localRowByStableId(importedList.id, stableItemId))
        assertEquals(
            listOf(ListItemLifecycleTransition(tombstone.id, wasActive = true, isActive = false)),
            tombstoneImport.lifecycleTransitions,
        )
        assertEquals(ListLifecycle.DELETED.name, tombstone.lifecycle)
        assertTrue(database.listItemDao().getAllByList(importedList.id).isEmpty())

        val restored = snapshot.copy(
            items = snapshot.items.map { incoming ->
                if (incoming.itemId == stableItemId) {
                    incoming.copy(lifecycle = ListLifecycle.ACTIVE, lifecycleStamp = VersionStamp(99, "remote"))
                } else {
                    incoming
                }
            },
        )
        val restoreImport = repository.importSnapshot(restored)

        val reactivated = requireNotNull(localRowByStableId(importedList.id, stableItemId))
        assertEquals(
            listOf(ListItemLifecycleTransition(tombstone.id, wasActive = false, isActive = true)),
            restoreImport.lifecycleTransitions,
        )
        assertEquals(ListLifecycle.ACTIVE.name, reactivated.lifecycle)
        assertEquals(reminderAt, reactivated.notificationTime)
        assertEquals(listOf("Milk"), database.listItemDao().getAllByList(importedList.id).map { it.text })
    }

    @Test
    fun `import keeps device-local state and applies neutral defaults`() = runBlocking {
        val listId = remoteRepository.createCollection("groceries")
        remoteRepository.addItem(listId, "Milk")

        // The receiving device already knows this item and owns its own favourite/reminder state.
        val snapshot = remoteRepository.exportSnapshot(listId)
        repository.importSnapshot(snapshot)
        val list = requireNotNull(database.listNameDao().getByCollectionId(snapshot.collectionId))
        val localRow = requireNotNull(database.listItemDao().getAllByList(list.id).single())
        database.listItemDao().upsert(localRow.copy(isFavourite = true, notificationTime = 1_234L))

        val newerRemoteText = snapshot.copy(
            items = snapshot.items.map { it.copy(text = "Oat milk", textStamp = VersionStamp(500, "remote")) },
        )
        repository.importSnapshot(newerRemoteText)

        val merged = requireNotNull(database.listItemDao().getById(localRow.id))
        assertEquals("Oat milk", merged.text)
        assertTrue("import must not clear a local favourite", merged.isFavourite)
        assertEquals(1_234L, merged.notificationTime)

        // A newly imported collection starts with the contract's neutral device-local defaults.
        val freshRemoteList = remoteRepository.createCollection("hardware")
        remoteRepository.addItem(freshRemoteList, "Screws")
        repository.importSnapshot(remoteRepository.exportSnapshot(freshRemoteList))
        val fresh = requireNotNull(
            database.listNameDao().getByCollectionId(
                requireNotNull(remoteDatabase.listNameDao().getById(freshRemoteList)).collectionId,
            ),
        )
        assertFalse(fresh.pinned)
        assertNull(fresh.archivedAt)
        val freshItem = database.listItemDao().getAllByList(fresh.id).single()
        assertFalse(freshItem.isFavourite)
        assertNull(freshItem.notificationTime)
    }

    @Test
    fun `import merges field by field and keeps the newer local edit`() = runBlocking {
        val listId = remoteRepository.createCollection("groceries")
        remoteRepository.addItem(listId, "Milk")
        val snapshot = remoteRepository.exportSnapshot(listId)
        repository.importSnapshot(snapshot)

        val list = requireNotNull(database.listNameDao().getByCollectionId(snapshot.collectionId))
        val localRow = requireNotNull(database.listItemDao().getAllByList(list.id).single())
        repository.setItemText(localRow.id, "Local milk")

        repository.importSnapshot(snapshot)

        assertEquals(
            "a stale import must not overwrite a newer local edit",
            "Local milk",
            requireNotNull(database.listItemDao().getById(localRow.id)).text,
        )

        val newerRemote = snapshot.copy(
            items = snapshot.items.map { it.copy(text = "Remote milk", textStamp = VersionStamp(500, "remote")) },
        )
        repository.importSnapshot(newerRemote)

        assertEquals("Remote milk", requireNotNull(database.listItemDao().getById(localRow.id)).text)
    }

    @Test
    fun `import reports the parent completion it causes`() = runBlocking {
        val listId = remoteRepository.createCollection("groceries")
        val parent = remoteRepository.addItem(listId, "Milk")
        val child = remoteRepository.addItem(listId, "Bread")
        remoteRepository.makeSubItem(child, stableId(remoteDatabase, parent))
        val snapshot = remoteRepository.exportSnapshot(listId)
        repository.importSnapshot(snapshot)

        val localList = requireNotNull(database.listNameDao().getByCollectionId(snapshot.collectionId))
        val localParent = requireNotNull(localRowByStableId(localList.id, stableId(remoteDatabase, parent)))
        assertFalse(localParent.checked)

        val childChecked = snapshot.copy(
            items = snapshot.items.map { incoming ->
                if (incoming.itemId == stableId(remoteDatabase, child)) {
                    incoming.copy(checked = true, checkedStamp = VersionStamp(500, "remote"))
                } else {
                    incoming
                }
            },
        )
        val result = repository.importSnapshot(childChecked)

        assertTrue(
            "completing the last child must report the parent transition",
            result.checkedStateMutation.checkedIds.contains(localParent.id),
        )
        assertTrue(requireNotNull(database.listItemDao().getById(localParent.id)).checked)
    }

    @Test
    fun `an identity owned by another collection is rejected without partial import`() = runBlocking {
        val localListId = repository.createCollection("groceries")
        val localItem = repository.addItem(localListId, "Milk")
        val localCollectionId = requireNotNull(database.listNameDao().getById(localListId)).collectionId
        val conflicting = SharedCollectionSnapshot(
            collectionId = "11111111-2222-4333-8444-555555555555",
            canonicalTitle = "hardware",
            lifecycle = ListLifecycle.ACTIVE,
            createdAt = 1L,
            titleStamp = VersionStamp(5, "remote"),
            lifecycleStamp = VersionStamp(5, "remote"),
            items = listOf(remoteItemSnapshot(stableId(database, localItem))),
            checkpoints = emptyList(),
        )

        val failure = runCatching { repository.importSnapshot(conflicting) }.exceptionOrNull()

        assertTrue("expected a typed identity failure, got $failure", failure is ListPackageException)
        assertEquals(ListPackageFailure.IDENTITY_MISMATCH, (failure as ListPackageException).reason)

        assertNull(
            "a rejected import must not create the collection",
            database.listNameDao().getByCollectionId(conflicting.collectionId),
        )
        assertEquals("Milk", requireNotNull(database.listItemDao().getById(localItem)).text)
        assertEquals(1, database.listNameDao().getAll().size)
        assertEquals(
            localCollectionId,
            requireNotNull(database.listNameDao().getById(localListId)).collectionId,
        )
    }

    @Test
    fun `a colliding title imports beside the local list with a local alias`() = runBlocking {
        val localListId = repository.createCollection("groceries")
        val remoteListId = remoteRepository.createCollection("groceries")
        remoteRepository.addItem(remoteListId, "Milk")
        val snapshot = remoteRepository.exportSnapshot(remoteListId)

        repository.importSnapshot(snapshot)

        val imported = requireNotNull(database.listNameDao().getByCollectionId(snapshot.collectionId))
        val local = requireNotNull(database.listNameDao().getById(localListId))
        assertEquals("groceries", imported.canonicalTitle)
        assertNotEquals("groceries", imported.name)
        assertEquals(imported.name, imported.localDisplayAlias)
        assertEquals("groceries", local.canonicalTitle)
        assertEquals("groceries", local.name)
        assertNotEquals(local.collectionId, imported.collectionId)
        assertEquals(1, database.listItemDao().getAllByList(imported.id).size)
    }

    @Test
    fun `checkpoints import per collection without pulling in other collections`() = runBlocking {
        val firstListId = remoteRepository.createCollection("groceries")
        val otherListId = remoteRepository.createCollection("hardware")
        val firstCollectionId = requireNotNull(remoteDatabase.listNameDao().getById(firstListId)).collectionId
        val otherCollectionId = requireNotNull(remoteDatabase.listNameDao().getById(otherListId)).collectionId
        remoteRepository.addItem(firstListId, "Milk")
        listOf(firstCollectionId, otherCollectionId).forEachIndexed { index, collectionId ->
            remoteRepository.applyRemote(
                ListChange(
                    changeId = "peer-$index",
                    collectionId = collectionId,
                    targetId = collectionId,
                    actorId = "peer",
                    sourceSequence = 1L,
                    stamp = VersionStamp(10L, "peer"),
                    operation = ListChangeOperation.CREATE_ITEM,
                    payload = ListChangePayload(text = "Peer item", orderKey = "${index + 5}"),
                ),
            )
        }

        val snapshot = remoteRepository.exportSnapshot(firstListId)
        val localActor = requireNotNull(remoteDatabase.listActorStateDao().get()).actorId
        val localSequence = requireNotNull(
            remoteDatabase.listSourceSequenceDao().get(localActor, firstCollectionId),
        ).sourceSequence
        val checkpoints = snapshot.checkpoints.associate {
            it.actorId to it.highestContiguousSourceSequence
        }
        assertEquals(snapshot.checkpoints.size, checkpoints.size)
        assertEquals(1L, checkpoints["peer"])
        assertEquals(localSequence, checkpoints[localActor])

        repository.importSnapshot(snapshot)

        assertEquals(
            1L,
            database.listCheckpointDao().get(firstCollectionId, "peer")?.highestContiguousSourceSequence,
        )
        assertEquals(
            localSequence,
            database.listCheckpointDao().get(firstCollectionId, localActor)?.highestContiguousSourceSequence,
        )
        assertNull(database.listCheckpointDao().get(otherCollectionId, "peer"))
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

    private suspend fun stableId(database: KernelDatabase, rowId: Long): String =
        requireNotNull(database.listItemDao().getById(rowId)).itemId

    private suspend fun localRowByStableId(listId: Long, itemId: String) =
        database.listItemDao().getAllByListAnyLifecycle(listId).firstOrNull { it.itemId == itemId }

    private suspend fun localHierarchy(title: String): Map<String, String> {
        val list = requireNotNull(database.listNameDao().getAll().firstOrNull { it.canonicalTitle == title })
        return hierarchyOf(database, list.id)
    }

    private suspend fun remoteHierarchy(title: String): Map<String, String> {
        val list = requireNotNull(remoteDatabase.listNameDao().getAll().firstOrNull { it.canonicalTitle == title })
        return hierarchyOf(remoteDatabase, list.id)
    }

    private suspend fun hierarchyOf(database: KernelDatabase, listId: Long): Map<String, String> =
        EffectiveHierarchyNormalizer.derive(
            database.listItemDao().getAllByListAnyLifecycle(listId).map {
                HierarchyItem(
                    itemId = it.itemId,
                    parentItemId = it.parentItemId,
                    orderKey = it.orderKey,
                    placementStamp = VersionStamp(it.placementLogicalClock, it.placementStampActorId),
                    active = it.lifecycle == ListLifecycle.ACTIVE.name,
                )
            },
        ).parentByChild

    private fun remoteItemSnapshot(itemId: String) = SharedItemSnapshot(
        itemId = itemId,
        text = "Conflicting",
        checked = false,
        dueAt = null,
        parentItemId = null,
        orderKey = "0",
        lifecycle = ListLifecycle.ACTIVE,
        createdAt = 1L,
        textStamp = VersionStamp(5, "remote"),
        checkedStamp = VersionStamp(5, "remote"),
        dueAtStamp = VersionStamp(5, "remote"),
        placementStamp = VersionStamp(5, "remote"),
        lifecycleStamp = VersionStamp(5, "remote"),
    )
}
