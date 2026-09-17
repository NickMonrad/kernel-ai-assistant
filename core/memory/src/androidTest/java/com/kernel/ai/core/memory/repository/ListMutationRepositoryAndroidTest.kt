package com.kernel.ai.core.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.OrderKey
import com.kernel.ai.core.memory.lists.ListChange
import com.kernel.ai.core.memory.lists.ListChangeOperation
import com.kernel.ai.core.memory.lists.ListChangePayload
import com.kernel.ai.core.memory.lists.VersionStamp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        val mutation = repository.setItemChecked(itemId, true)
        assertEquals(setOf(itemId), mutation.checkedIds)
        assertTrue(database.listNameDao().getById(listId)!!.updatedAt > beforeLocal)
    }
    @Test
    fun `hierarchy completion cascades and parent deletion promotes surviving children`() = runBlocking {
        val listId = repository.createCollection("Hierarchy")
        val parentId = repository.addItem(listId, "Parent")
        val firstId = repository.addItem(listId, "First")
        val secondId = repository.addItem(listId, "Second")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(firstId, parent.itemId, "1")
        repository.setItemPlacement(secondId, parent.itemId, "2")

        repository.setItemChecked(firstId, true)
        val completedMutation = repository.setItemChecked(secondId, true)
        assertEquals(setOf(secondId, parentId), completedMutation.checkedIds)
        assertTrue(database.listItemDao().getById(parentId)!!.checked)

        val reopenedMutation = repository.setItemChecked(firstId, false)
        assertEquals(setOf(firstId, parentId), reopenedMutation.uncheckedIds)
        assertTrue(!database.listItemDao().getById(parentId)!!.checked)

        val parentMutation = repository.setItemChecked(parentId, true)
        assertEquals(setOf(firstId, secondId, parentId), parentMutation.checkedIds)
        assertTrue(database.listItemDao().getById(firstId)!!.checked)
        assertTrue(database.listItemDao().getById(secondId)!!.checked)
        assertTrue(database.listItemDao().getById(parentId)!!.checked)

        repository.deleteItem(parentId)
        val surviving = database.listItemDao().getAllByList(listId)
        assertEquals(listOf("First", "Second"), surviving.map { it.text })
        assertTrue(surviving.all { it.parentItemId == null })
    }

    @Test
    fun `swipe right indents a standalone item under the group above it`() = runBlocking {
        val listId = repository.createCollection("Indent standalone")
        val aId = repository.addItem(listId, "A")
        val bId = repository.addItem(listId, "B")
        val a = database.listItemDao().getById(aId)!!
        val changesBefore = repository.pendingChanges().size

        assertTrue(repository.indentItem(bId, a.itemId))

        assertEquals(a.itemId, database.listItemDao().getById(bId)!!.parentItemId)
        val emitted = repository.pendingChanges().drop(changesBefore)
        assertEquals(1, emitted.size)
        assertEquals(ListChangeOperation.SET_ITEM_PLACEMENT, emitted.single().operation)
    }

    @Test
    fun `swipe right after an existing child joins that child's parent`() = runBlocking {
        val listId = repository.createCollection("Indent after child")
        val parentId = repository.addItem(listId, "A")
        val childId = repository.addItem(listId, "B")
        val cId = repository.addItem(listId, "C")
        val parent = database.listItemDao().getById(parentId)!!
        val child = database.listItemDao().getById(childId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")

        assertTrue(repository.indentItem(cId, child.itemId))

        assertEquals(parent.itemId, database.listItemDao().getById(cId)!!.parentItemId)
        assertTrue(
            OrderKey.compare(
                database.listItemDao().getById(childId)!!.orderKey,
                database.listItemDao().getById(cId)!!.orderKey,
            ) < 0,
        )
    }

    @Test
    fun `a parent with children cannot be indented`() = runBlocking {
        val listId = repository.createCollection("Indent rejected")
        val otherId = repository.addItem(listId, "Other")
        val parentId = repository.addItem(listId, "Parent")
        val childId = repository.addItem(listId, "Child")
        val parent = database.listItemDao().getById(parentId)!!
        val other = database.listItemDao().getById(otherId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")
        val changesBefore = repository.pendingChanges().size

        assertFalse(repository.indentItem(parentId, other.itemId))

        assertEquals(null, database.listItemDao().getById(parentId)!!.parentItemId)
        assertEquals(changesBefore, repository.pendingChanges().size)
    }

    @Test
    fun `swipe right moves an incomplete item into a completed group and reopens its parent`() = runBlocking {
        val listId = repository.createCollection("Indent completion")
        val parentId = repository.addItem(listId, "Parent")
        val childId = repository.addItem(listId, "Child")
        val newcomerId = repository.addItem(listId, "Newcomer")
        val parent = database.listItemDao().getById(parentId)!!
        val child = database.listItemDao().getById(childId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")
        repository.setItemChecked(parentId, true)
        assertTrue(database.listItemDao().getById(parentId)!!.checked)

        assertTrue(repository.indentItem(newcomerId, child.itemId))

        assertEquals(parent.itemId, database.listItemDao().getById(newcomerId)!!.parentItemId)
        assertFalse(database.listItemDao().getById(parentId)!!.checked)
    }

    @Test
    fun `swipe left outdents only the selected child and leaves later siblings behind`() = runBlocking {
        val listId = repository.createCollection("Outdent middle")
        val parentId = repository.addItem(listId, "Parent")
        val aId = repository.addItem(listId, "A")
        val bId = repository.addItem(listId, "B")
        val cId = repository.addItem(listId, "C")
        val dId = repository.addItem(listId, "D")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(aId, parent.itemId, "1")
        repository.setItemPlacement(bId, parent.itemId, "2")
        repository.setItemPlacement(cId, parent.itemId, "3")
        repository.setItemPlacement(dId, parent.itemId, "4")
        val changesBefore = repository.pendingChanges().size

        assertTrue(repository.outdentItem(bId))

        val b = database.listItemDao().getById(bId)!!
        assertEquals(null, b.parentItemId)
        assertEquals(parent.itemId, database.listItemDao().getById(aId)!!.parentItemId)
        assertEquals(parent.itemId, database.listItemDao().getById(cId)!!.parentItemId)
        assertEquals(parent.itemId, database.listItemDao().getById(dId)!!.parentItemId)
        assertTrue(OrderKey.compare(b.orderKey, parent.orderKey) > 0)
        val emitted = repository.pendingChanges().drop(changesBefore)
        assertEquals(1, emitted.size)
        assertEquals(ListChangeOperation.SET_ITEM_PLACEMENT, emitted.single().operation)
    }

    @Test
    fun `outdenting the last incomplete child makes the parent incomplete`() = runBlocking {
        val listId = repository.createCollection("Outdent completion")
        val parentId = repository.addItem(listId, "Parent")
        val completeId = repository.addItem(listId, "Complete child")
        val openId = repository.addItem(listId, "Open child")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(completeId, parent.itemId, "1")
        repository.setItemPlacement(openId, parent.itemId, "2")
        repository.setItemChecked(completeId, true)
        assertFalse(database.listItemDao().getById(parentId)!!.checked)

        assertTrue(repository.outdentItem(openId))

        assertTrue("remaining child is complete", database.listItemDao().getById(parentId)!!.checked)
        assertTrue("promoted item keeps its own state", database.listItemDao().getById(openId)!!.checked)
    }

    @Test
    fun `outdenting the only child keeps the parent's checked state`() = runBlocking {
        val listId = repository.createCollection("Outdent last child")
        val parentId = repository.addItem(listId, "Parent")
        val childId = repository.addItem(listId, "Only child")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")
        repository.setItemChecked(parentId, true)
        assertTrue(database.listItemDao().getById(childId)!!.checked)

        assertTrue(repository.outdentItem(childId))

        assertEquals(null, database.listItemDao().getById(childId)!!.parentItemId)
        assertTrue("childless parent is unchanged", database.listItemDao().getById(parentId)!!.checked)
        assertEquals(listOf("Parent", "Only child"), database.listItemDao().getByList(listId).map { it.text })
    }

    @Test
    fun `outdenting a top-level item changes nothing`() = runBlocking {
        val listId = repository.createCollection("Outdent no-op")
        val aId = repository.addItem(listId, "A")
        val changesBefore = repository.pendingChanges().size

        assertFalse(repository.outdentItem(aId))

        assertEquals(changesBefore, repository.pendingChanges().size)
    }

    @Test
    fun `bulk deletion promotes only untargeted children`() = runBlocking {
        val listId = repository.createCollection("Bulk hierarchy")
        val parentId = repository.addItem(listId, "Parent")
        val firstId = repository.addItem(listId, "Keep first")
        val middleId = repository.addItem(listId, "Delete middle")
        val lastId = repository.addItem(listId, "Keep last")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(firstId, parent.itemId, "1")
        repository.setItemPlacement(middleId, parent.itemId, "2")
        repository.setItemPlacement(lastId, parent.itemId, "3")

        repository.deleteItems(listOf(parentId, middleId))

        val first = database.listItemDao().getById(firstId)!!
        val middle = database.listItemDao().getById(middleId)!!
        val last = database.listItemDao().getById(lastId)!!
        assertEquals(ListLifecycle.ACTIVE.name, first.lifecycle)
        assertEquals(ListLifecycle.DELETED.name, middle.lifecycle)
        assertEquals(ListLifecycle.ACTIVE.name, last.lifecycle)
        assertEquals(null, first.parentItemId)
        assertEquals(null, last.parentItemId)
        assertTrue(OrderKey.compare(first.orderKey, last.orderKey) < 0)
    }

    @Test
    fun `moving an item under an existing child is rejected atomically`() = runBlocking {
        val listId = repository.createCollection("Validation")
        val parentId = repository.addItem(listId, "Parent")
        val childId = repository.addItem(listId, "Child")
        val grandchildId = repository.addItem(listId, "Grandchild")
        val parent = database.listItemDao().getById(parentId)!!
        val child = database.listItemDao().getById(childId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")

        val changesBeforeInvalidMove = repository.pendingChanges().size
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.setItemPlacement(grandchildId, child.itemId, "1") }
        }
        assertEquals(null, database.listItemDao().getById(grandchildId)!!.parentItemId)
        assertEquals(changesBeforeInvalidMove, repository.pendingChanges().size)

    }
    @Test
    fun `reparenting a checked child recomputes both parent completion states`() = runBlocking {
        val listId = repository.createCollection("Reparent")
        val oldParentId = repository.addItem(listId, "Old parent")
        val newParentId = repository.addItem(listId, "New parent")
        val childId = repository.addItem(listId, "Child")
        val oldParent = database.listItemDao().getById(oldParentId)!!
        val newParent = database.listItemDao().getById(newParentId)!!
        val child = database.listItemDao().getById(childId)!!

        repository.setItemPlacement(childId, oldParent.itemId, "1")
        repository.setItemChecked(childId, true)
        repository.setItemPlacement(childId, newParent.itemId, "1")

        assertTrue(database.listItemDao().getById(oldParentId)!!.checked)
        assertTrue(database.listItemDao().getById(newParentId)!!.checked)
        assertEquals(newParent.itemId, database.listItemDao().getById(childId)!!.parentItemId)
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
