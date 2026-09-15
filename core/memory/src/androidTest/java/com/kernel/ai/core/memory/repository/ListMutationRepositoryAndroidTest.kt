package com.kernel.ai.core.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.ListChange
import com.kernel.ai.core.memory.lists.ListChangeOperation
import com.kernel.ai.core.memory.lists.ListChangePayload
import com.kernel.ai.core.memory.lists.VersionStamp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ListMutationRepositoryAndroidTest {
    private lateinit var database: KernelDatabase
    private lateinit var repository: ListMutationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ListMutationRepository(
            database = database,
            listItemDao = database.listItemDao(),
            listNameDao = database.listNameDao(),
            actorDao = database.listActorStateDao(),
            appliedDao = database.listAppliedChangeDao(),
            changeDao = database.listChangeDao(),
            sourceDao = database.listSourceSequenceDao(),
            checkpointDao = database.listCheckpointDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `remote changes reconcile by field stamp and advance contiguous checkpoint`() = runBlocking {
        val listId = repository.createCollection("Groceries")
        val collectionId = requireNotNull(database.listNameDao().getById(listId)).collectionId
        val itemId = repository.addItem(listId, "Milk")
        val remoteActor = "remote-actor"

        repository.applyRemote(
            change(
                collectionId = collectionId,
                targetId = database.listItemDao().getById(itemId)!!.itemId,
                actorId = remoteActor,
                sourceSequence = 2L,
                logicalClock = 10L,
                operation = ListChangeOperation.SET_ITEM_TEXT,
                payload = ListChangePayload(text = "Remote milk"),
            ),
        )
        assertEquals(
            0L,
            database.listCheckpointDao().get(collectionId, remoteActor)?.highestContiguousSourceSequence ?: 0L,
        )

        repository.applyRemote(
            change(
                collectionId = collectionId,
                targetId = database.listItemDao().getById(itemId)!!.itemId,
                actorId = remoteActor,
                sourceSequence = 1L,
                logicalClock = 9L,
                operation = ListChangeOperation.SET_ITEM_TEXT,
                payload = ListChangePayload(text = "Older milk"),
            ),
        )
        val persisted = requireNotNull(database.listItemDao().getById(itemId))
        assertEquals("Remote milk", persisted.text)
        assertEquals(2L, database.listCheckpointDao().get(collectionId, remoteActor)?.highestContiguousSourceSequence)

        repository.applyRemote(
            change(
                collectionId = collectionId,
                targetId = persisted.itemId,
                actorId = remoteActor,
                sourceSequence = 3L,
                logicalClock = 11L,
                operation = ListChangeOperation.DELETE_ITEM,
            ),
        )
        repository.applyRemote(
            change(
                collectionId = collectionId,
                targetId = persisted.itemId,
                actorId = remoteActor,
                sourceSequence = 4L,
                logicalClock = 12L,
                operation = ListChangeOperation.SET_ITEM_TEXT,
                payload = ListChangePayload(text = "Must stay tombstoned"),
            ),
        )

        val tombstone = requireNotNull(database.listItemDao().getById(itemId))
        assertEquals(ListLifecycle.DELETED.name, tombstone.lifecycle)
        assertEquals("Remote milk", tombstone.text)
        assertEquals(4L, database.listCheckpointDao().get(collectionId, remoteActor)?.highestContiguousSourceSequence)
        assertTrue(database.listAppliedChangeDao().exists(changeId = "remote-actor-1") != null)
    }

    @Test
    fun `remote field and item create can arrive before collection create`() = runBlocking {
        val collectionId = "remote-collection"
        val itemId = "remote-item"
        val actorId = "remote-order"

        repository.applyRemote(
            change(
                collectionId = collectionId,
                targetId = itemId,
                actorId = actorId,
                sourceSequence = 3L,
                logicalClock = 10L,
                operation = ListChangeOperation.SET_ITEM_TEXT,
                payload = ListChangePayload(text = "Newest text"),
            ),
        )
        repository.applyRemote(
            change(
                collectionId = collectionId,
                targetId = itemId,
                actorId = actorId,
                sourceSequence = 2L,
                logicalClock = 2L,
                operation = ListChangeOperation.CREATE_ITEM,
                payload = ListChangePayload(text = "Initial text", orderKey = "1"),
            ),
        )
        repository.applyRemote(
            change(
                collectionId = collectionId,
                targetId = collectionId,
                actorId = actorId,
                sourceSequence = 1L,
                logicalClock = 1L,
                operation = ListChangeOperation.CREATE_COLLECTION,
                payload = ListChangePayload(canonicalTitle = "Remote groceries"),
            ),
        )

        assertEquals("Remote groceries", database.listNameDao().getByCollectionId(collectionId)!!.canonicalTitle)
        assertEquals("Newest text", database.listItemDao().getByItemId(itemId)!!.text)
        assertEquals(3L, database.listCheckpointDao().get(collectionId, actorId)!!.highestContiguousSourceSequence)
    }


    @Test
    fun `restore before create and delete converges to active`() = runBlocking {
        val collectionId = "restore-first"
        val actorId = "remote-lifecycle"

        repository.applyRemote(
            change(collectionId, collectionId, actorId, 3L, 30L, ListChangeOperation.RESTORE_COLLECTION),
        )
        repository.applyRemote(
            change(collectionId, collectionId, actorId, 1L, 10L, ListChangeOperation.CREATE_COLLECTION, ListChangePayload(canonicalTitle = "Converged")),
        )
        repository.applyRemote(
            change(collectionId, collectionId, actorId, 2L, 20L, ListChangeOperation.DELETE_COLLECTION),
        )

        val list = requireNotNull(database.listNameDao().getByCollectionId(collectionId))
        assertEquals(ListLifecycle.ACTIVE.name, list.lifecycle)

        repository.applyRemote(
            change(collectionId, collectionId, actorId, 3L, 30L, ListChangeOperation.RESTORE_COLLECTION),
        )
        assertEquals(ListLifecycle.ACTIVE.name, database.listNameDao().getByCollectionId(collectionId)!!.lifecycle)
    }

    @Test
    fun `create delete restore in normal order converges to active`() = runBlocking {
        val collectionId = "restore-normal"
        val actorId = "remote-lifecycle"

        repository.applyRemote(
            change(collectionId, collectionId, actorId, 1L, 10L, ListChangeOperation.CREATE_COLLECTION, ListChangePayload(canonicalTitle = "Converged")),
        )
        repository.applyRemote(
            change(collectionId, collectionId, actorId, 2L, 20L, ListChangeOperation.DELETE_COLLECTION),
        )
        repository.applyRemote(
            change(collectionId, collectionId, actorId, 3L, 30L, ListChangeOperation.RESTORE_COLLECTION),
        )

        assertEquals(ListLifecycle.ACTIVE.name, database.listNameDao().getByCollectionId(collectionId)!!.lifecycle)
    }

    @Test
    fun `older restore does not override newer delete`() = runBlocking {
        val collectionId = "restore-stale"
        val actorId = "remote-lifecycle"

        repository.applyRemote(
            change(collectionId, collectionId, actorId, 1L, 10L, ListChangeOperation.CREATE_COLLECTION, ListChangePayload(canonicalTitle = "Stale restore")),
        )
        repository.applyRemote(
            change(collectionId, collectionId, actorId, 2L, 30L, ListChangeOperation.DELETE_COLLECTION),
        )
        repository.applyRemote(
            change(collectionId, collectionId, actorId, 3L, 20L, ListChangeOperation.RESTORE_COLLECTION),
        )

        assertEquals(ListLifecycle.DELETED.name, database.listNameDao().getByCollectionId(collectionId)!!.lifecycle)
    }

    @Test
    fun `collection deletion tombstones only collection and newer restore is explicit`() = runBlocking {
        val listId = repository.createCollection("Archive")
        val collectionId = database.listNameDao().getById(listId)!!.collectionId
        val itemId = repository.addItem(listId, "Keep this item")

        repository.deleteCollection(listId)

        assertEquals(ListLifecycle.DELETED.name, database.listNameDao().getById(listId)!!.lifecycle)
        assertEquals(ListLifecycle.ACTIVE.name, database.listItemDao().getById(itemId)!!.lifecycle)

        repository.applyRemote(
            change(collectionId, collectionId, "remote-lifecycle", 1L, 20L, ListChangeOperation.CREATE_COLLECTION, ListChangePayload(canonicalTitle = "Stale create")),
        )
        assertEquals(ListLifecycle.DELETED.name, database.listNameDao().getById(listId)!!.lifecycle)

        repository.applyRemote(
            change(collectionId, collectionId, "remote-lifecycle", 2L, 21L, ListChangeOperation.RESTORE_COLLECTION),
        )
        assertEquals(ListLifecycle.ACTIVE.name, database.listNameDao().getById(listId)!!.lifecycle)
        assertEquals(ListLifecycle.ACTIVE.name, database.listItemDao().getById(itemId)!!.lifecycle)

        val deletedAgain = repository.createCollection("Repeat")
        repository.deleteCollection(deletedAgain)
        val replacement = repository.createCollection("Repeat")
        assertTrue(deletedAgain != replacement)
    }

    @Test
    fun `exact order keys survive remote placement and local reorder`() = runBlocking {
        val listId = repository.createCollection("Ordered")
        val collectionId = database.listNameDao().getById(listId)!!.collectionId
        val firstId = repository.addItem(listId, "First")
        val secondId = repository.addItem(listId, "Second")
        val first = database.listItemDao().getById(firstId)!!
        val second = database.listItemDao().getById(secondId)!!

        repository.applyRemote(
            change(collectionId, first.itemId, "remote-order", 1L, 10L, ListChangeOperation.SET_ITEM_PLACEMENT, ListChangePayload(orderKey = "9007199254740992.1000000001")),
        )
        repository.applyRemote(
            change(collectionId, second.itemId, "remote-order", 2L, 11L, ListChangeOperation.SET_ITEM_PLACEMENT, ListChangePayload(orderKey = "9007199254740992.1000000002")),
        )

        assertEquals(listOf("First", "Second"), database.listItemDao().getByList(listId).map { it.text })
        assertEquals("9007199254740992.1000000001", database.listItemDao().getById(firstId)!!.orderKey)

        repository.reorderItems(listId, listOf(secondId, firstId))
        assertEquals(listOf("Second", "First"), database.listItemDao().getByList(listId).map { it.text })
    }

    @Test
    fun `accepted item mutations advance parent timestamp but stale and duplicate do not`() = runBlocking {
        val listId = repository.createCollection("Timestamped")
        val collectionId = database.listNameDao().getById(listId)!!.collectionId
        val itemId = repository.addItem(listId, "Original")
        val item = database.listItemDao().getById(itemId)!!
        database.listNameDao().updateTimestamp(listId, 1L)

        repository.applyRemote(
            change(collectionId, item.itemId, "remote-timestamp", 1L, 10L, ListChangeOperation.SET_ITEM_TEXT, ListChangePayload(text = "Accepted")),
        )
        val acceptedAt = database.listNameDao().getById(listId)!!.updatedAt
        assertTrue(acceptedAt > 1L)

        repository.applyRemote(
            change(collectionId, item.itemId, "remote-timestamp", 1L, 10L, ListChangeOperation.SET_ITEM_TEXT, ListChangePayload(text = "Duplicate")),
        )
        assertEquals(acceptedAt, database.listNameDao().getById(listId)!!.updatedAt)

        repository.applyRemote(
            change(collectionId, item.itemId, "remote-timestamp", 2L, 9L, ListChangeOperation.SET_ITEM_TEXT, ListChangePayload(text = "Stale")),
        )
        assertEquals(acceptedAt, database.listNameDao().getById(listId)!!.updatedAt)

        val beforeLocal = database.listNameDao().getById(listId)!!.updatedAt
        repository.setItemChecked(itemId, true)
        assertTrue(database.listNameDao().getById(listId)!!.updatedAt > beforeLocal)
    }
    private fun change(
        collectionId: String,
        targetId: String,
        actorId: String,
        sourceSequence: Long,
        logicalClock: Long,
        operation: ListChangeOperation,
        payload: ListChangePayload = ListChangePayload(),
    ): ListChange = ListChange(
        changeId = "$actorId-$sourceSequence",
        collectionId = collectionId,
        targetId = targetId,
        actorId = actorId,
        sourceSequence = sourceSequence,
        stamp = VersionStamp(logicalClock, actorId),
        operation = operation,
        payload = payload,
    )
}
