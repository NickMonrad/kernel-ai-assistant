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
