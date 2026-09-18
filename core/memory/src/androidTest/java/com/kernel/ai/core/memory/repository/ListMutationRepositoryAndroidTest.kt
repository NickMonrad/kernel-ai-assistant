package com.kernel.ai.core.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.lists.CheckedStateMutation
import com.kernel.ai.core.memory.lists.EffectiveHierarchyProjection
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
        assertEquals(
            "the second child was already complete, so it is not a transition",
            setOf(firstId, parentId),
            parentMutation.checkedIds,
        )
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

        val mutation = repository.indentItem(bId, a.itemId)

        assertEquals(a.itemId, database.listItemDao().getById(bId)!!.parentItemId)
        assertEquals("placing an item alone changes no completion state", CheckedStateMutation(), mutation)
        val emitted = repository.pendingChanges().drop(changesBefore)
        assertEquals(1, emitted.size)
        assertEquals(ListChangeOperation.SET_ITEM_PLACEMENT, emitted.single().operation)
    }

    @Test
    fun `swipe right after an existing child joins the same parent`() = runBlocking {
        val listId = repository.createCollection("Indent after child")
        val parentId = repository.addItem(listId, "A")
        val childId = repository.addItem(listId, "B")
        val cId = repository.addItem(listId, "C")
        val parent = database.listItemDao().getById(parentId)!!
        val child = database.listItemDao().getById(childId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")

        val mutation = repository.indentItem(cId, child.itemId)

        assertEquals(parent.itemId, database.listItemDao().getById(cId)!!.parentItemId)
        assertEquals(CheckedStateMutation(), mutation)
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

        val mutation = repository.indentItem(parentId, other.itemId)

        assertEquals(CheckedStateMutation(), mutation)
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

        val mutation = repository.indentItem(newcomerId, child.itemId)

        assertEquals(parent.itemId, database.listItemDao().getById(newcomerId)!!.parentItemId)
        assertFalse(database.listItemDao().getById(parentId)!!.checked)
        assertEquals(setOf(parentId), mutation.uncheckedIds)
        assertTrue(mutation.checkedIds.isEmpty())
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

        val mutation = repository.outdentItem(bId)

        val b = database.listItemDao().getById(bId)!!
        assertEquals(null, b.parentItemId)
        assertEquals(parent.itemId, database.listItemDao().getById(aId)!!.parentItemId)
        assertEquals(parent.itemId, database.listItemDao().getById(cId)!!.parentItemId)
        assertEquals(parent.itemId, database.listItemDao().getById(dId)!!.parentItemId)
        assertTrue(OrderKey.compare(b.orderKey, parent.orderKey) > 0)
        assertEquals(CheckedStateMutation(), mutation)
        val emitted = repository.pendingChanges().drop(changesBefore)
        assertEquals(1, emitted.size)
        assertEquals(ListChangeOperation.SET_ITEM_PLACEMENT, emitted.single().operation)
    }

    @Test
    fun `outdenting the only incomplete child completes parent while promoted child stays incomplete`() = runBlocking {
        val listId = repository.createCollection("Outdent completion")
        val parentId = repository.addItem(listId, "Parent")
        val completeId = repository.addItem(listId, "Complete child")
        val openId = repository.addItem(listId, "Open child")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(completeId, parent.itemId, "1")
        repository.setItemPlacement(openId, parent.itemId, "2")
        repository.setItemChecked(completeId, true)
        assertFalse(database.listItemDao().getById(parentId)!!.checked)

        val mutation = repository.outdentItem(openId)

        assertTrue(database.listItemDao().getById(parentId)!!.checked)
        assertFalse(database.listItemDao().getById(openId)!!.checked)
        assertEquals(setOf(parentId), mutation.checkedIds)
        assertFalse("the promoted child was already incomplete", openId in mutation.checkedIds)
    }

    @Test
    fun `outdenting the only child keeps the parent checked state`() = runBlocking {
        val listId = repository.createCollection("Outdent last child")
        val parentId = repository.addItem(listId, "Parent")
        val childId = repository.addItem(listId, "Only child")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")
        repository.setItemChecked(parentId, true)
        assertTrue(database.listItemDao().getById(childId)!!.checked)

        val mutation = repository.outdentItem(childId)

        assertEquals(null, database.listItemDao().getById(childId)!!.parentItemId)
        assertTrue(database.listItemDao().getById(parentId)!!.checked)
        assertEquals("a childless parent keeps its own state", CheckedStateMutation(), mutation)
        assertEquals(
            listOf("Parent", "Only child"),
            database.listItemDao().getAllByList(listId).map { it.text },
        )
    }

    @Test
    fun `outdenting a top-level item changes nothing`() = runBlocking {
        val listId = repository.createCollection("Outdent no-op")
        val aId = repository.addItem(listId, "A")
        val changesBefore = repository.pendingChanges().size

        val mutation = repository.outdentItem(aId)

        assertEquals(CheckedStateMutation(), mutation)
        assertEquals(changesBefore, repository.pendingChanges().size)
    }

    @Test
    fun `moving an incomplete child between completed groups reports both parent transitions`() = runBlocking {
        val listId = repository.createCollection("Move transitions")
        val oldParentId = repository.addItem(listId, "Old parent")
        val newParentId = repository.addItem(listId, "New parent")
        val settledSiblingId = repository.addItem(listId, "Settled sibling")
        val settledChildId = repository.addItem(listId, "Settled child")
        val openChildId = repository.addItem(listId, "Open child")
        val oldParent = database.listItemDao().getById(oldParentId)!!
        val newParent = database.listItemDao().getById(newParentId)!!
        repository.setItemPlacement(settledSiblingId, oldParent.itemId, "1")
        repository.setItemPlacement(openChildId, oldParent.itemId, "2")
        repository.setItemPlacement(settledChildId, newParent.itemId, "1")
        repository.setItemChecked(settledSiblingId, true)
        repository.setItemChecked(settledChildId, true)
        assertFalse("one child is still open", database.listItemDao().getById(oldParentId)!!.checked)
        assertTrue(database.listItemDao().getById(newParentId)!!.checked)

        val mutation = repository.moveItem(openChildId, newParent.itemId, "2")

        assertTrue("old parent's remaining child is complete", database.listItemDao().getById(oldParentId)!!.checked)
        assertFalse("new parent gained an open child", database.listItemDao().getById(newParentId)!!.checked)
        assertEquals(setOf(oldParentId), mutation.checkedIds)
        assertEquals(setOf(newParentId), mutation.uncheckedIds)
    }

    @Test
    fun `materialising a visible order replaces the stale manual order`() = runBlocking {
        val listId = repository.createCollection("Visible order")
        val aId = repository.addItem(listId, "A")
        val bId = repository.addItem(listId, "B")
        val cId = repository.addItem(listId, "C")
        assertEquals(listOf("A", "B", "C"), database.listItemDao().getAllByList(listId).map { it.text })
        val changesBefore = repository.pendingChanges().size

        val visible = listOf(
            ListMutationRepository.VisibleHierarchyRow(cId, null),
            ListMutationRepository.VisibleHierarchyRow(bId, null),
            ListMutationRepository.VisibleHierarchyRow(aId, null),
        )
        val mutation = repository.applyVisibleHierarchyOrder(listId, visible)

        assertEquals(listOf("C", "B", "A"), effectiveRowTexts(listId))
        assertTrue(database.listItemDao().getAllByListUnordered(listId).all { it.parentItemId == null })
        assertEquals(CheckedStateMutation(), mutation)
        // C and A move to new index keys; B already sits at index 1, so it is not rewritten.
        val emitted = repository.pendingChanges().drop(changesBefore)
        assertEquals(2, emitted.count { it.operation == ListChangeOperation.SET_ITEM_PLACEMENT })
        assertTrue(emitted.all { it.operation == ListChangeOperation.SET_ITEM_PLACEMENT })
    }

    @Test
    fun `materialising a grouped visible order keeps every parent with its children`() = runBlocking {
        val listId = repository.createCollection("Visible groups")
        val firstId = repository.addItem(listId, "P1")
        val aId = repository.addItem(listId, "A")
        val bId = repository.addItem(listId, "B")
        val secondId = repository.addItem(listId, "P2")
        val cId = repository.addItem(listId, "C")
        val first = database.listItemDao().getById(firstId)!!
        val second = database.listItemDao().getById(secondId)!!
        repository.setItemPlacement(aId, first.itemId, "1")
        repository.setItemPlacement(bId, first.itemId, "2")
        repository.setItemPlacement(cId, second.itemId, "1")

        val visible = listOf(
            ListMutationRepository.VisibleHierarchyRow(secondId, null),
            ListMutationRepository.VisibleHierarchyRow(cId, secondId),
            ListMutationRepository.VisibleHierarchyRow(firstId, null),
            ListMutationRepository.VisibleHierarchyRow(aId, firstId),
            ListMutationRepository.VisibleHierarchyRow(bId, firstId),
        )
        repository.applyVisibleHierarchyOrder(listId, visible)

        assertEquals(listOf("P2", "C", "P1", "A", "B"), effectiveRowTexts(listId))
        assertEquals(second.itemId, database.listItemDao().getById(cId)!!.parentItemId)
        assertEquals(first.itemId, database.listItemDao().getById(aId)!!.parentItemId)
        assertEquals(first.itemId, database.listItemDao().getById(bId)!!.parentItemId)
    }

    @Test
    fun `a visible order that already matches the persisted order writes nothing`() = runBlocking {
        val listId = repository.createCollection("Visible no-op")
        val aId = repository.addItem(listId, "A")
        val bId = repository.addItem(listId, "B")
        val changesBefore = repository.pendingChanges().size

        val mutation = repository.applyVisibleHierarchyOrder(
            listId,
            listOf(
                ListMutationRepository.VisibleHierarchyRow(aId, null),
                ListMutationRepository.VisibleHierarchyRow(bId, null),
            ),
        )

        assertEquals(CheckedStateMutation(), mutation)
        assertEquals(changesBefore, repository.pendingChanges().size)
    }

    @Test
    fun `materialising a child into another group reparents it and recomputes both groups`() = runBlocking {
        val listId = repository.createCollection("Visible reparent")
        val oldParentId = repository.addItem(listId, "Old parent")
        val settledId = repository.addItem(listId, "Settled child")
        val openId = repository.addItem(listId, "Open child")
        val newParentId = repository.addItem(listId, "New parent")
        val completeId = repository.addItem(listId, "Complete child")
        val oldParent = database.listItemDao().getById(oldParentId)!!
        val newParent = database.listItemDao().getById(newParentId)!!
        repository.setItemPlacement(settledId, oldParent.itemId, "1")
        repository.setItemPlacement(openId, oldParent.itemId, "2")
        repository.setItemPlacement(completeId, newParent.itemId, "1")
        repository.setItemChecked(settledId, true)
        repository.setItemChecked(completeId, true)
        assertFalse(database.listItemDao().getById(oldParentId)!!.checked)
        assertTrue(database.listItemDao().getById(newParentId)!!.checked)

        val visible = listOf(
            ListMutationRepository.VisibleHierarchyRow(oldParentId, null),
            ListMutationRepository.VisibleHierarchyRow(settledId, oldParentId),
            ListMutationRepository.VisibleHierarchyRow(newParentId, null),
            ListMutationRepository.VisibleHierarchyRow(completeId, newParentId),
            ListMutationRepository.VisibleHierarchyRow(openId, newParentId, reparent = true),
        )
        val mutation = repository.applyVisibleHierarchyOrder(listId, visible)

        assertEquals(newParent.itemId, database.listItemDao().getById(openId)!!.parentItemId)
        assertTrue("old parent's remaining child is complete", database.listItemDao().getById(oldParentId)!!.checked)
        assertFalse("new parent gained an open child", database.listItemDao().getById(newParentId)!!.checked)
        assertEquals(setOf(oldParentId), mutation.checkedIds)
        assertEquals(setOf(newParentId), mutation.uncheckedIds)
    }

    @Test
    fun `a suppressed requested parent survives materialising an unrelated visible order`() = runBlocking {
        val listId = repository.createCollection("Suppressed edge")
        val collectionId = database.listNameDao().getById(listId)!!.collectionId
        val parentId = repository.addItem(listId, "P")
        val childId = repository.addItem(listId, "C")
        val suppressedId = repository.addItem(listId, "X")
        val grandchildId = repository.addItem(listId, "Y")
        val parent = database.listItemDao().getById(parentId)!!
        val child = database.listItemDao().getById(childId)!!
        val suppressed = database.listItemDao().getById(suppressedId)!!

        repository.setItemPlacement(childId, parent.itemId, "1")
        // Y is accepted first, which makes X an effective parent, so the newer remote edge from X to
        // the child C is retained but suppressed. X stays effectively top-level.
        repository.setItemPlacement(grandchildId, suppressed.itemId, "3")
        repository.applyRemote(
            change(
                collectionId = collectionId,
                targetId = suppressed.itemId,
                actorId = "remote-actor",
                sourceSequence = 1L,
                logicalClock = database.listItemDao().getById(grandchildId)!!.placementLogicalClock - 1L,
                operation = ListChangeOperation.SET_ITEM_PLACEMENT,
                payload = ListChangePayload(parentItemId = child.itemId, orderKey = "2"),
            ),
        )

        assertEquals(listOf("P", "C", "X", "Y"), effectiveRowTexts(listId))
        assertEquals(child.itemId, database.listItemDao().getById(suppressedId)!!.parentItemId)
        val changesBefore = repository.pendingChanges().size

        val mutation = repository.applyVisibleHierarchyOrder(
            listId,
            listOf(
                ListMutationRepository.VisibleHierarchyRow(parentId, null),
                ListMutationRepository.VisibleHierarchyRow(childId, parentId),
                ListMutationRepository.VisibleHierarchyRow(suppressedId, null),
                ListMutationRepository.VisibleHierarchyRow(grandchildId, suppressedId),
            ),
        )

        assertEquals(CheckedStateMutation(), mutation)
        assertEquals(
            "a suppressed requested parent is derived state, not a placement to repair",
            child.itemId,
            database.listItemDao().getById(suppressedId)!!.parentItemId,
        )
        assertEquals(changesBefore, repository.pendingChanges().size)
        assertEquals(listOf("P", "C", "X", "Y"), effectiveRowTexts(listId))
    }

    @Test
    fun `moving a suppressed requested edge to a new top level position keeps its requested parent`() =
        runBlocking {
            val listId = repository.createCollection("Suppressed move")
            val collectionId = database.listNameDao().getById(listId)!!.collectionId
            val parentId = repository.addItem(listId, "P")
            val childId = repository.addItem(listId, "C")
            val suppressedId = repository.addItem(listId, "X")
            val firstGrandchildId = repository.addItem(listId, "Y")
            val secondGrandchildId = repository.addItem(listId, "Z")
            val parent = database.listItemDao().getById(parentId)!!
            val child = database.listItemDao().getById(childId)!!
            val suppressed = database.listItemDao().getById(suppressedId)!!

            repository.setItemPlacement(childId, parent.itemId, "1")
            // Y then Z are accepted first, which makes X an effective parent and keeps its newer
            // remote edge to the child C suppressed.
            repository.setItemPlacement(firstGrandchildId, suppressed.itemId, "3")
            repository.setItemPlacement(secondGrandchildId, suppressed.itemId, "5")
            repository.applyRemote(
                change(
                    collectionId = collectionId,
                    targetId = suppressed.itemId,
                    actorId = "remote-actor",
                    sourceSequence = 1L,
                    logicalClock = database.listItemDao().getById(firstGrandchildId)!!.placementLogicalClock - 1L,
                    operation = ListChangeOperation.SET_ITEM_PLACEMENT,
                    payload = ListChangePayload(parentItemId = child.itemId, orderKey = "2"),
                ),
            )
            assertEquals(listOf("P", "C", "X", "Y", "Z"), effectiveRowTexts(listId))
            assertEquals(child.itemId, database.listItemDao().getById(suppressedId)!!.parentItemId)
            val changesBefore = repository.pendingChanges().size

            // The user drags X to the front of the visible top level and swaps its two children.
            // X is not explicitly reparented: its retained requested parent must survive intact.
            val mutation = repository.applyVisibleHierarchyOrder(
                listId,
                listOf(
                    ListMutationRepository.VisibleHierarchyRow(suppressedId, null, reparent = false),
                    ListMutationRepository.VisibleHierarchyRow(secondGrandchildId, suppressedId),
                    ListMutationRepository.VisibleHierarchyRow(firstGrandchildId, suppressedId),
                    ListMutationRepository.VisibleHierarchyRow(parentId, null),
                    ListMutationRepository.VisibleHierarchyRow(childId, parentId),
                ),
            )

            assertEquals(CheckedStateMutation(), mutation)
            assertEquals(listOf("X", "Z", "Y", "P", "C"), effectiveRowTexts(listId))
            assertEquals(
                "the suppressed requested parent survives a top-level reorder",
                child.itemId,
                database.listItemDao().getById(suppressedId)!!.parentItemId,
            )
            val emitted = repository.pendingChanges().drop(changesBefore)
            assertTrue(emitted.isNotEmpty())
            assertTrue(emitted.all { it.operation == ListChangeOperation.SET_ITEM_PLACEMENT })
            val moved = emitted.single { it.targetId == suppressed.itemId }
            assertEquals(child.itemId, moved.payload.parentItemId)
            assertEquals("0", moved.payload.orderKey)
        }

    @Test
    fun `an automatic sort baseline is rolled back when the requested edit fails`() = runBlocking {
        val listId = repository.createCollection("Baseline atomicity")
        val aId = repository.addItem(listId, "A")
        val bId = repository.addItem(listId, "B")
        val cId = repository.addItem(listId, "C")
        val b = database.listItemDao().getById(bId)!!
        val orderBefore = database.listItemDao().getAllByList(listId).map { it.text }
        val changesBefore = repository.pendingChanges().size

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.indentItem(
                    999_999L,
                    b.itemId,
                    ListMutationRepository.VisibleOrderBaseline(
                        listId,
                        listOf(
                            ListMutationRepository.VisibleHierarchyRow(cId, null),
                            ListMutationRepository.VisibleHierarchyRow(bId, null),
                            ListMutationRepository.VisibleHierarchyRow(aId, null),
                        ),
                    ),
                )
            }
        }

        assertEquals(orderBefore, database.listItemDao().getAllByList(listId).map { it.text })
        assertEquals(changesBefore, repository.pendingChanges().size)
    }

    @Test
    fun `a visible order deeper than two levels is rejected without writing`() = runBlocking {
        val listId = repository.createCollection("Visible invalid")
        val parentId = repository.addItem(listId, "Parent")
        val childId = repository.addItem(listId, "Child")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")
        val changesBefore = repository.pendingChanges().size

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.applyVisibleHierarchyOrder(
                    listId,
                    listOf(
                        ListMutationRepository.VisibleHierarchyRow(parentId, null),
                        ListMutationRepository.VisibleHierarchyRow(childId, parentId),
                        ListMutationRepository.VisibleHierarchyRow(parentId, childId),
                    ),
                )
            }
        }
        assertEquals(changesBefore, repository.pendingChanges().size)
        assertEquals(listOf("Parent", "Child"), effectiveRowTexts(listId))
    }

    @Test
    fun `indent from an automatic sort uses the materialised visible order as its baseline`() = runBlocking {
        val listId = repository.createCollection("Visible indent")
        val aId = repository.addItem(listId, "A")
        val bId = repository.addItem(listId, "B")
        val cId = repository.addItem(listId, "C")
        val b = database.listItemDao().getById(bId)!!

        // The automatic sort shows C, B, A while the persisted manual order is A, B, C, so the
        // baseline and the indent have to commit together. Swiping A right uses B, the row the
        // user could see directly above it.
        repository.indentItem(
            aId,
            b.itemId,
            ListMutationRepository.VisibleOrderBaseline(
                listId,
                listOf(
                    ListMutationRepository.VisibleHierarchyRow(cId, null),
                    ListMutationRepository.VisibleHierarchyRow(bId, null),
                    ListMutationRepository.VisibleHierarchyRow(aId, null),
                ),
            ),
        )

        assertEquals(b.itemId, database.listItemDao().getById(aId)!!.parentItemId)
        assertEquals(listOf("C", "B", "A"), effectiveRowTexts(listId))
        assertTrue(database.listItemDao().getById(cId)!!.parentItemId == null)
    }

    @Test
    fun `outdent from an automatic sort places the child after the visible parent group`() = runBlocking {
        val listId = repository.createCollection("Visible outdent")
        val firstId = repository.addItem(listId, "P1")
        val aId = repository.addItem(listId, "A")
        val bId = repository.addItem(listId, "B")
        val secondId = repository.addItem(listId, "P2")
        val cId = repository.addItem(listId, "C")
        val first = database.listItemDao().getById(firstId)!!
        val second = database.listItemDao().getById(secondId)!!
        repository.setItemPlacement(aId, first.itemId, "1")
        repository.setItemPlacement(bId, first.itemId, "2")
        repository.setItemPlacement(cId, second.itemId, "1")

        // The automatic sort shows P2, C, P1, A, B, so the baseline and the outdent commit together.
        repository.outdentItem(
            bId,
            ListMutationRepository.VisibleOrderBaseline(
                listId,
                listOf(
                    ListMutationRepository.VisibleHierarchyRow(secondId, null),
                    ListMutationRepository.VisibleHierarchyRow(cId, secondId),
                    ListMutationRepository.VisibleHierarchyRow(firstId, null),
                    ListMutationRepository.VisibleHierarchyRow(aId, firstId),
                    ListMutationRepository.VisibleHierarchyRow(bId, firstId),
                ),
            ),
        )

        assertEquals(null, database.listItemDao().getById(bId)!!.parentItemId)
        assertEquals(first.itemId, database.listItemDao().getById(aId)!!.parentItemId)
        assertEquals(second.itemId, database.listItemDao().getById(cId)!!.parentItemId)
        assertEquals(listOf("P2", "C", "P1", "A", "B"), effectiveRowTexts(listId))
    }

    @Test
    fun `a placement with no completion effect reports an empty mutation`() = runBlocking {
        val listId = repository.createCollection("Move no-op")
        val parentId = repository.addItem(listId, "Parent")
        val childId = repository.addItem(listId, "Child")
        val otherId = repository.addItem(listId, "Other")
        val parent = database.listItemDao().getById(parentId)!!
        repository.setItemPlacement(childId, parent.itemId, "1")

        val mutation = repository.moveItem(otherId, null, "1")

        assertEquals(CheckedStateMutation(), mutation)
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

    /** The effective projection order the user sees: each top-level row followed by its children. */
    private suspend fun effectiveRowTexts(listId: Long): List<String> {
        val groups = EffectiveHierarchyProjection.derive(
            database.listItemDao().getAllByListUnordered(listId),
            itemId = { it.itemId },
            parentItemId = { it.parentItemId },
            orderKey = { it.orderKey },
            placementStamp = { VersionStamp(it.placementLogicalClock, it.placementStampActorId) },
        )
        return groups.flatMap { group -> listOf(group.parent.text) + group.children.map { it.text } }
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
