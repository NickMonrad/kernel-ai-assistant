package com.kernel.ai.core.memory.repository

import androidx.room.withTransaction
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.ListActorStateDao
import com.kernel.ai.core.memory.dao.ListAppliedChangeDao
import com.kernel.ai.core.memory.dao.ListCheckpointDao
import com.kernel.ai.core.memory.dao.ListChangeDao
import com.kernel.ai.core.memory.dao.ListSourceSequenceDao
import com.kernel.ai.core.memory.entity.ListActorStateEntity
import com.kernel.ai.core.memory.entity.ListAppliedChangeEntity
import com.kernel.ai.core.memory.entity.ListChangeEntity
import com.kernel.ai.core.memory.entity.ListCheckpointEntity
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.ListSourceSequenceEntity
import com.kernel.ai.core.memory.lists.ListChange
import com.kernel.ai.core.memory.lists.ListChangeOperation
import com.kernel.ai.core.memory.lists.ListChangePayload
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.OrderKey
import com.kernel.ai.core.memory.lists.VersionStamp
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListMutationRepository @Inject constructor(
    private val database: KernelDatabase,
    private val listItemDao: ListItemDao,
    private val listNameDao: ListNameDao,
    private val actorDao: ListActorStateDao,
    private val appliedDao: ListAppliedChangeDao,
    private val changeDao: ListChangeDao,
    private val sourceDao: ListSourceSequenceDao,
    private val checkpointDao: ListCheckpointDao,
) {
    suspend fun pendingChanges(): List<ListChange> = changeDao.getPending().map { it.toModel() }
    suspend fun acknowledgePushed(changeIds: List<String>) = database.withTransaction {
        if (changeIds.isNotEmpty()) changeDao.markPushed(changeIds)
        changeDao.pruneAcknowledged()
    }

    suspend fun createCollection(title: String): Long = database.withTransaction {
        createCollectionInternal(title)
    }

    private suspend fun createCollectionInternal(title: String): Long {
        val existing = listNameDao.getByNameAnyLifecycle(title)
        if (existing != null) {
            if (existing.lifecycle == ListLifecycle.DELETED.name) {
                restoreCollectionInternal(existing)
            }
            return existing.id
        }
        val collectionId = UUID.randomUUID().toString()
        val stamp = nextStamp(collectionId)
        val name = uniqueDisplayName(title, stableLabel = collectionId.take(8))
        val now = System.currentTimeMillis()
        listNameDao.insertAndGet(
            ListNameEntity(
                name = name,
                canonicalTitle = title,
                localDisplayAlias = name.takeIf { it != title },
                collectionId = collectionId,
                createdAt = now,
                updatedAt = now,
                titleLogicalClock = stamp.logicalClock,
                titleStampActorId = stamp.actorId,
                lifecycleLogicalClock = stamp.logicalClock,
                lifecycleStampActorId = stamp.actorId,
            ),
        )
        recordLocal(collectionId, collectionId, stamp, ListChangeOperation.CREATE_COLLECTION, ListChangePayload(canonicalTitle = title))
        return listNameDao.getByCollectionId(collectionId)?.id ?: error("Failed to create collection")
    }

    private suspend fun restoreCollectionInternal(list: ListNameEntity) {
        val stamp = nextStamp(list.collectionId)
        listNameDao.upsert(list.copy(lifecycle = ListLifecycle.ACTIVE.name, updatedAt = System.currentTimeMillis(), lifecycleLogicalClock = stamp.logicalClock, lifecycleStampActorId = stamp.actorId))
        recordLocal(list.collectionId, list.collectionId, stamp, ListChangeOperation.RESTORE_COLLECTION)
    }

    suspend fun createCollectionWithItems(title: String, items: List<String>): Long = database.withTransaction {
        val listId = createCollectionInternal(title)
        items.forEach { addItemInternal(listId, it, null, false, null) }
        listId
    }

    suspend fun addItem(listId: Long, text: String, dueAt: Long? = null, notificationTime: Long? = null): Long =
        database.withTransaction { addItemInternal(listId, text, dueAt, false, notificationTime) }

    suspend fun addItems(listId: Long, texts: List<String>): List<Long> = database.withTransaction {
        texts.map { addItemInternal(listId, it, null, false, null) }
    }

    suspend fun setItemChecked(itemId: Long, checked: Boolean) = database.withTransaction {
        val item = requireItem(itemId)
        if (item.checked == checked || item.lifecycle != ListLifecycle.ACTIVE.name) return@withTransaction
        val stamp = nextStamp(item.collectionId)
        listItemDao.upsert(item.copy(checked = checked, updatedAt = System.currentTimeMillis(), checkedLogicalClock = stamp.logicalClock, checkedStampActorId = stamp.actorId))
        recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.SET_ITEM_CHECKED, ListChangePayload(checked = checked))
    }

    suspend fun setItemText(itemId: Long, text: String) = database.withTransaction {
        val item = requireItem(itemId)
        if (item.text == text || item.lifecycle != ListLifecycle.ACTIVE.name) return@withTransaction
        val stamp = nextStamp(item.collectionId)
        listItemDao.upsert(item.copy(text = text, updatedAt = System.currentTimeMillis(), textLogicalClock = stamp.logicalClock, textStampActorId = stamp.actorId))
        recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.SET_ITEM_TEXT, ListChangePayload(text = text))
    }

    suspend fun setItemDueAt(itemId: Long, dueAt: Long?) = database.withTransaction {
        val item = requireItem(itemId)
        if (item.dueAt == dueAt || item.lifecycle != ListLifecycle.ACTIVE.name) return@withTransaction
        val stamp = nextStamp(item.collectionId)
        listItemDao.upsert(item.copy(dueAt = dueAt, updatedAt = System.currentTimeMillis(), dueAtLogicalClock = stamp.logicalClock, dueAtStampActorId = stamp.actorId))
        recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.SET_ITEM_DUE_AT, ListChangePayload(dueAt = dueAt))
    }

    suspend fun updateItem(itemId: Long, text: String, dueAt: Long?, favourite: Boolean, notificationTime: Long?) = database.withTransaction {
        var item = requireItem(itemId)
        if (item.lifecycle != ListLifecycle.ACTIVE.name) return@withTransaction
        if (item.text != text) {
            val stamp = nextStamp(item.collectionId)
            item = item.copy(text = text, updatedAt = System.currentTimeMillis(), textLogicalClock = stamp.logicalClock, textStampActorId = stamp.actorId)
            listItemDao.upsert(item)
            recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.SET_ITEM_TEXT, ListChangePayload(text = text))
        }
        if (item.dueAt != dueAt) {
            val stamp = nextStamp(item.collectionId)
            item = item.copy(dueAt = dueAt, updatedAt = System.currentTimeMillis(), dueAtLogicalClock = stamp.logicalClock, dueAtStampActorId = stamp.actorId)
            listItemDao.upsert(item)
            recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.SET_ITEM_DUE_AT, ListChangePayload(dueAt = dueAt))
        }
        if (item.isFavourite != favourite || item.notificationTime != notificationTime) {
            listItemDao.upsert(item.copy(isFavourite = favourite, notificationTime = notificationTime, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun setItemPlacement(itemId: Long, parentItemId: String?, orderKey: String) = database.withTransaction {
        val item = requireItem(itemId)
        val canonicalOrderKey = OrderKey.canonical(orderKey)
        if (item.parentItemId == parentItemId && OrderKey.canonical(item.orderKey) == canonicalOrderKey) return@withTransaction
        val stamp = nextStamp(item.collectionId)
        listItemDao.upsert(item.copy(parentItemId = parentItemId, orderKey = canonicalOrderKey, displayOrder = canonicalOrderKey.toLongOrNull() ?: item.displayOrder, updatedAt = System.currentTimeMillis(), placementLogicalClock = stamp.logicalClock, placementStampActorId = stamp.actorId))
        recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.SET_ITEM_PLACEMENT, ListChangePayload(parentItemId = parentItemId, orderKey = canonicalOrderKey))
    }

    suspend fun reorderItems(listId: Long, itemIds: List<Long>) = database.withTransaction {
        itemIds.forEachIndexed { index, id ->
            val item = requireItem(id)
            if (item.listId != listId) error("Item does not belong to list")
            setItemPlacementInternal(item, null, OrderKey.forIndex(index))
        }
    }

    suspend fun deleteItem(itemId: Long) = database.withTransaction {
        val item = requireItem(itemId)
        if (item.lifecycle == ListLifecycle.DELETED.name) return@withTransaction
        val stamp = nextStamp(item.collectionId)
        listItemDao.upsert(item.copy(lifecycle = ListLifecycle.DELETED.name, updatedAt = System.currentTimeMillis(), lifecycleLogicalClock = stamp.logicalClock, lifecycleStampActorId = stamp.actorId))
        recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.DELETE_ITEM)
    }

    suspend fun deleteItems(itemIds: List<Long>) = database.withTransaction { itemIds.forEach { deleteItemInternal(it) } }

    suspend fun deleteCollection(listId: Long) = database.withTransaction {
        deleteCollectionInternal(requireList(listId))
    }

    private suspend fun deleteCollectionInternal(list: ListNameEntity) {
        if (list.lifecycle == ListLifecycle.DELETED.name) return
        listItemDao.getAllByList(list.id).forEach { deleteItemInternal(it.id) }
        val stamp = nextStamp(list.collectionId)
        listNameDao.upsert(list.copy(lifecycle = ListLifecycle.DELETED.name, updatedAt = System.currentTimeMillis(), lifecycleLogicalClock = stamp.logicalClock, lifecycleStampActorId = stamp.actorId))
        recordLocal(list.collectionId, list.collectionId, stamp, ListChangeOperation.DELETE_COLLECTION)
    }

    suspend fun restoreCollection(listId: Long) = database.withTransaction {
        val list = requireList(listId)
        if (list.lifecycle != ListLifecycle.DELETED.name) return@withTransaction
        restoreCollectionInternal(list)
    }


    suspend fun renameCollection(listId: Long, title: String) = database.withTransaction {
        val list = requireList(listId)
        if (list.canonicalTitle == title || list.lifecycle != ListLifecycle.ACTIVE.name) return@withTransaction
        val stamp = nextStamp(list.collectionId)
        val name = uniqueDisplayName(title, list.id)
        listNameDao.upsert(list.copy(name = name, canonicalTitle = title, localDisplayAlias = name.takeIf { it != title }, updatedAt = System.currentTimeMillis(), titleLogicalClock = stamp.logicalClock, titleStampActorId = stamp.actorId))
        recordLocal(list.collectionId, list.collectionId, stamp, ListChangeOperation.SET_COLLECTION_TITLE, ListChangePayload(canonicalTitle = title))
    }

    suspend fun deleteCollectionByName(name: String) = database.withTransaction {
        listNameDao.getByNameAnyLifecycle(name)?.let { deleteCollectionInternal(it) }
    }

    suspend fun applyRemote(change: ListChange) = database.withTransaction {
        require(change.formatVersion == 1) { "Unsupported Lists change format ${change.formatVersion}" }
        if (appliedDao.exists(change.changeId) != null || changeDao.getById(change.changeId) != null) return@withTransaction
        val actor = actorDao.get() ?: ListActorStateEntity(UUID.randomUUID().toString(), 0L)
        actorDao.upsert(actor.copy(logicalClock = maxOf(actor.logicalClock, change.stamp.logicalClock) + 1L))
        when (change.operation) {
            ListChangeOperation.CREATE_COLLECTION -> applyCreateCollection(change)
            ListChangeOperation.SET_COLLECTION_TITLE -> applyCollectionTitle(change)
            ListChangeOperation.DELETE_COLLECTION, ListChangeOperation.RESTORE_COLLECTION -> applyCollectionLifecycle(change)
            ListChangeOperation.CREATE_ITEM -> applyCreateItem(change)
            ListChangeOperation.SET_ITEM_TEXT, ListChangeOperation.SET_ITEM_CHECKED, ListChangeOperation.SET_ITEM_DUE_AT, ListChangeOperation.SET_ITEM_PLACEMENT -> applyItemField(change)
            ListChangeOperation.DELETE_ITEM, ListChangeOperation.RESTORE_ITEM -> applyItemLifecycle(change)
        }
        appliedDao.insert(ListAppliedChangeEntity(change.changeId, change.collectionId, change.actorId, change.sourceSequence))
        advanceCheckpoint(change)
    }

    private suspend fun addItemInternal(listId: Long, text: String, dueAt: Long?, checked: Boolean, notificationTime: Long?): Long {
        val list = requireList(listId)
        require(list.lifecycle == ListLifecycle.ACTIVE.name) { "Cannot add to deleted collection" }
        val stamp = nextStamp(list.collectionId)
        val orderKey = OrderKey.forIndex(listItemDao.getAllByList(listId).size)
        val itemId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val item = ListItemEntity(
            listId = listId,
            text = text,
            dueAt = dueAt,
            checked = checked,
            notificationTime = notificationTime,
            createdAt = now,
            updatedAt = now,
            itemId = itemId,
            collectionId = list.collectionId,
            orderKey = orderKey,
            displayOrder = orderKey.toLong(),
            textLogicalClock = stamp.logicalClock,
            textStampActorId = stamp.actorId,
            checkedLogicalClock = stamp.logicalClock,
            checkedStampActorId = stamp.actorId,
            dueAtLogicalClock = stamp.logicalClock,
            dueAtStampActorId = stamp.actorId,
            placementLogicalClock = stamp.logicalClock,
            placementStampActorId = stamp.actorId,
        )
        listItemDao.insert(item)
        recordLocal(list.collectionId, itemId, stamp, ListChangeOperation.CREATE_ITEM, ListChangePayload(text = text, checked = checked, dueAt = dueAt, orderKey = orderKey))
        return listItemDao.getByItemId(itemId)?.id ?: error("Failed to create item")
    }

    private suspend fun setItemPlacementInternal(item: ListItemEntity, parentItemId: String?, orderKey: String) {
        val canonical = OrderKey.canonical(orderKey)
        if (item.parentItemId == parentItemId && OrderKey.canonical(item.orderKey) == canonical) return
        val stamp = nextStamp(item.collectionId)
        listItemDao.upsert(item.copy(parentItemId = parentItemId, orderKey = canonical, displayOrder = canonical.toLongOrNull() ?: item.displayOrder, updatedAt = System.currentTimeMillis(), placementLogicalClock = stamp.logicalClock, placementStampActorId = stamp.actorId))
        recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.SET_ITEM_PLACEMENT, ListChangePayload(parentItemId = parentItemId, orderKey = canonical))
    }

    private suspend fun deleteItemInternal(itemId: Long) {
        val item = requireItem(itemId)
        if (item.lifecycle == ListLifecycle.DELETED.name) return
        val stamp = nextStamp(item.collectionId)
        listItemDao.upsert(item.copy(lifecycle = ListLifecycle.DELETED.name, updatedAt = System.currentTimeMillis(), lifecycleLogicalClock = stamp.logicalClock, lifecycleStampActorId = stamp.actorId))
        recordLocal(item.collectionId, item.itemId, stamp, ListChangeOperation.DELETE_ITEM)
    }

    private suspend fun nextStamp(collectionId: String): VersionStamp {
        val state = actorDao.get() ?: ListActorStateEntity(UUID.randomUUID().toString(), 0L).also { actorDao.upsert(it) }
        val nextClock = state.logicalClock + 1L
        actorDao.upsert(state.copy(logicalClock = nextClock))
        val currentSequence = sourceDao.get(state.actorId, collectionId)?.sourceSequence ?: 0L
        sourceDao.upsert(ListSourceSequenceEntity(state.actorId, collectionId, currentSequence + 1L))
        return VersionStamp(nextClock, state.actorId)
    }

    private suspend fun recordLocal(collectionId: String, targetId: String, stamp: VersionStamp, operation: ListChangeOperation, payload: ListChangePayload = ListChangePayload()) {
        val state = actorDao.get() ?: error("Actor state not initialized")
        val sequence = sourceDao.get(state.actorId, collectionId)?.sourceSequence ?: error("Source sequence not initialized")
        val change = ListChange(changeId = UUID.randomUUID().toString(), collectionId = collectionId, targetId = targetId, actorId = state.actorId, sourceSequence = sequence, stamp = stamp, operation = operation, payload = payload)
        changeDao.insert(change.toEntity(isPending = true))
    }

    private suspend fun applyCreateCollection(change: ListChange) {
        val title = requireNotNull(change.payload.canonicalTitle) { "Collection title is required" }
        val existing = listNameDao.getByCollectionId(change.collectionId)
        if (existing == null) {
            val stamp = change.stamp
            val now = System.currentTimeMillis()
            val name = uniqueDisplayName(title, stableLabel = change.collectionId.take(8))
            listNameDao.insert(
                ListNameEntity(
                    name = name,
                    canonicalTitle = title,
                    localDisplayAlias = name.takeIf { it != title },
                    collectionId = change.collectionId,
                    createdAt = now,
                    updatedAt = now,
                    titleLogicalClock = stamp.logicalClock,
                    titleStampActorId = stamp.actorId,
                    lifecycleLogicalClock = stamp.logicalClock,
                    lifecycleStampActorId = stamp.actorId,
                ),
            )
            return
        }

        var merged = existing
        if (change.stamp > VersionStamp(existing.titleLogicalClock, existing.titleStampActorId)) {
            val name = uniqueDisplayName(title, existing.id, change.collectionId.take(8))
            merged = merged.copy(
                name = name,
                canonicalTitle = title,
                localDisplayAlias = name.takeIf { it != title },
                titleLogicalClock = change.stamp.logicalClock,
                titleStampActorId = change.stamp.actorId,
            )
        }
        if (existing.lifecycle == ListLifecycle.DELETED.name &&
            change.stamp > VersionStamp(existing.lifecycleLogicalClock, existing.lifecycleStampActorId)
        ) {
            merged = merged.copy(
                lifecycle = ListLifecycle.ACTIVE.name,
                lifecycleLogicalClock = change.stamp.logicalClock,
                lifecycleStampActorId = change.stamp.actorId,
            )
        }
        if (merged != existing) listNameDao.upsert(merged)
    }

    private suspend fun ensureRemoteCollection(change: ListChange): ListNameEntity {
        listNameDao.getByCollectionId(change.collectionId)?.let { return it }
        val title = change.payload.canonicalTitle ?: "Untitled collection"
        val titleStamp = if (change.operation == ListChangeOperation.SET_COLLECTION_TITLE) change.stamp else VersionStamp(0L, "")
        val name = uniqueDisplayName(title, stableLabel = change.collectionId.take(8))
        val now = System.currentTimeMillis()
        listNameDao.insert(
            ListNameEntity(
                name = name,
                canonicalTitle = title,
                localDisplayAlias = name.takeIf { it != title },
                collectionId = change.collectionId,
                createdAt = now,
                updatedAt = now,
                titleLogicalClock = titleStamp.logicalClock,
                titleStampActorId = titleStamp.actorId,
                lifecycleLogicalClock = 0L,
                lifecycleStampActorId = "",
            ),
        )
        return requireNotNull(listNameDao.getByCollectionId(change.collectionId))
    }

    private suspend fun ensureRemoteItem(change: ListChange): ListItemEntity {
        listItemDao.getByItemId(change.targetId)?.let { return it }
        val list = ensureRemoteCollection(change)
        val item = ListItemEntity(
            listId = list.id,
            text = "",
            itemId = change.targetId,
            collectionId = change.collectionId,
            orderKey = "0",
        )
        listItemDao.insert(item)
        return requireNotNull(listItemDao.getByItemId(change.targetId))
    }

    private suspend fun applyCollectionTitle(change: ListChange) {
        val list = ensureRemoteCollection(change)
        if (change.stamp <= VersionStamp(list.titleLogicalClock, list.titleStampActorId)) return
        val title = requireNotNull(change.payload.canonicalTitle)
        val name = uniqueDisplayName(title, list.id, list.collectionId.take(8))
        listNameDao.upsert(list.copy(name = name, canonicalTitle = title, localDisplayAlias = name.takeIf { it != title }, updatedAt = System.currentTimeMillis(), titleLogicalClock = change.stamp.logicalClock, titleStampActorId = change.stamp.actorId))
    }

    private suspend fun applyCollectionLifecycle(change: ListChange) {
        val existing = listNameDao.getByCollectionId(change.collectionId)
        if (existing == null) {
            if (change.operation == ListChangeOperation.RESTORE_COLLECTION) return
            val title = change.payload.canonicalTitle ?: "Deleted collection"
            val now = System.currentTimeMillis()
            listNameDao.insert(
                ListNameEntity(
                    name = uniqueDisplayName(title, stableLabel = change.collectionId.take(8)),
                    canonicalTitle = title,
                    localDisplayAlias = title,
                    collectionId = change.collectionId,
                    lifecycle = ListLifecycle.DELETED.name,
                    createdAt = now,
                    updatedAt = now,
                    lifecycleLogicalClock = change.stamp.logicalClock,
                    lifecycleStampActorId = change.stamp.actorId,
                ),
            )
            return
        }
        if (change.stamp <= VersionStamp(existing.lifecycleLogicalClock, existing.lifecycleStampActorId)) return
        listNameDao.upsert(existing.copy(lifecycle = if (change.operation == ListChangeOperation.DELETE_COLLECTION) ListLifecycle.DELETED.name else ListLifecycle.ACTIVE.name, updatedAt = System.currentTimeMillis(), lifecycleLogicalClock = change.stamp.logicalClock, lifecycleStampActorId = change.stamp.actorId))
    }

    private suspend fun applyCreateItem(change: ListChange) {
        val list = ensureRemoteCollection(change)
        val payload = change.payload
        val stamp = change.stamp
        val existing = listItemDao.getByItemId(change.targetId)
        if (existing == null) {
            val orderKey = OrderKey.canonical(payload.orderKey ?: "0")
            listItemDao.insert(ListItemEntity(listId = list.id, text = requireNotNull(payload.text), checked = payload.checked ?: false, dueAt = payload.dueAt, itemId = change.targetId, collectionId = change.collectionId, parentItemId = payload.parentItemId, orderKey = orderKey, displayOrder = orderKey.toLongOrNull() ?: 0L, textLogicalClock = stamp.logicalClock, textStampActorId = stamp.actorId, checkedLogicalClock = stamp.logicalClock, checkedStampActorId = stamp.actorId, dueAtLogicalClock = stamp.logicalClock, dueAtStampActorId = stamp.actorId, placementLogicalClock = stamp.logicalClock, placementStampActorId = stamp.actorId))
            return
        }
        require(existing.collectionId == change.collectionId) { "Item belongs to another collection" }
        var merged = existing
        if (stamp > VersionStamp(existing.textLogicalClock, existing.textStampActorId)) {
            merged = merged.copy(text = requireNotNull(payload.text), textLogicalClock = stamp.logicalClock, textStampActorId = stamp.actorId)
        }
        if (stamp > VersionStamp(existing.checkedLogicalClock, existing.checkedStampActorId)) {
            merged = merged.copy(checked = payload.checked ?: false, checkedLogicalClock = stamp.logicalClock, checkedStampActorId = stamp.actorId)
        }
        if (stamp > VersionStamp(existing.dueAtLogicalClock, existing.dueAtStampActorId)) {
            merged = merged.copy(dueAt = payload.dueAt, dueAtLogicalClock = stamp.logicalClock, dueAtStampActorId = stamp.actorId)
        }
        if (stamp > VersionStamp(existing.placementLogicalClock, existing.placementStampActorId)) {
            val orderKey = OrderKey.canonical(payload.orderKey ?: "0")
            merged = merged.copy(parentItemId = payload.parentItemId, orderKey = orderKey, displayOrder = orderKey.toLongOrNull() ?: existing.displayOrder, placementLogicalClock = stamp.logicalClock, placementStampActorId = stamp.actorId)
        }
        if (merged != existing) listItemDao.upsert(merged.copy(updatedAt = System.currentTimeMillis()))
    }

    private suspend fun applyItemField(change: ListChange) {
        val item = ensureRemoteItem(change)
        require(item.collectionId == change.collectionId) { "Item belongs to another collection" }
        if (item.lifecycle == ListLifecycle.DELETED.name) return
        when (change.operation) {
            ListChangeOperation.SET_ITEM_TEXT -> if (change.stamp > VersionStamp(item.textLogicalClock, item.textStampActorId)) listItemDao.upsert(item.copy(text = requireNotNull(change.payload.text), textLogicalClock = change.stamp.logicalClock, textStampActorId = change.stamp.actorId, updatedAt = System.currentTimeMillis()))
            ListChangeOperation.SET_ITEM_CHECKED -> if (change.stamp > VersionStamp(item.checkedLogicalClock, item.checkedStampActorId)) listItemDao.upsert(item.copy(checked = requireNotNull(change.payload.checked), checkedLogicalClock = change.stamp.logicalClock, checkedStampActorId = change.stamp.actorId, updatedAt = System.currentTimeMillis()))
            ListChangeOperation.SET_ITEM_DUE_AT -> if (change.stamp > VersionStamp(item.dueAtLogicalClock, item.dueAtStampActorId)) listItemDao.upsert(item.copy(dueAt = change.payload.dueAt, dueAtLogicalClock = change.stamp.logicalClock, dueAtStampActorId = change.stamp.actorId, updatedAt = System.currentTimeMillis()))
            ListChangeOperation.SET_ITEM_PLACEMENT -> if (change.stamp > VersionStamp(item.placementLogicalClock, item.placementStampActorId)) listItemDao.upsert(item.copy(parentItemId = change.payload.parentItemId, orderKey = OrderKey.canonical(requireNotNull(change.payload.orderKey)), placementLogicalClock = change.stamp.logicalClock, placementStampActorId = change.stamp.actorId, updatedAt = System.currentTimeMillis()))
            else -> Unit
        }
    }

    private suspend fun applyItemLifecycle(change: ListChange) {
        val existing = listItemDao.getByItemId(change.targetId)
        if (existing == null && change.operation == ListChangeOperation.RESTORE_ITEM) return
        val item = existing ?: ensureRemoteItem(change)
        require(item.collectionId == change.collectionId) { "Item belongs to another collection" }
        if (change.stamp <= VersionStamp(item.lifecycleLogicalClock, item.lifecycleStampActorId)) return
        listItemDao.upsert(item.copy(lifecycle = if (change.operation == ListChangeOperation.DELETE_ITEM) ListLifecycle.DELETED.name else ListLifecycle.ACTIVE.name, lifecycleLogicalClock = change.stamp.logicalClock, lifecycleStampActorId = change.stamp.actorId, updatedAt = System.currentTimeMillis()))
    }



    private suspend fun advanceCheckpoint(change: ListChange) {
        val current = checkpointDao.get(change.collectionId, change.actorId)?.highestContiguousSourceSequence ?: 0L
        val applied = appliedDao.getSourceSequences(change.actorId, change.collectionId).toHashSet()
        var next = current + 1L
        while (next in applied) next += 1L
        if (next - 1L > current) {
            checkpointDao.upsert(ListCheckpointEntity(change.collectionId, change.actorId, next - 1L))
        }
    }
    private suspend fun requireList(id: Long): ListNameEntity = listNameDao.getById(id) ?: error("Unknown list: $id")
    private suspend fun requireItem(id: Long): ListItemEntity = listItemDao.getById(id) ?: error("Unknown item: $id")

    private suspend fun uniqueDisplayName(title: String, exceptId: Long? = null, stableLabel: String? = null): String {
        if (listNameDao.getByNameAnyLifecycle(title)?.let { it.id != exceptId } != true) return title
        val label = stableLabel ?: "local"
        var candidate = "$title ($label)"
        var suffix = 2
        while (true) {
            val existing = listNameDao.getByNameAnyLifecycle(candidate)
            if (existing == null || existing.id == exceptId) return candidate
            candidate = "$title ($label $suffix)"
            suffix += 1
        }
    }
}

private fun ListChangeEntity.toModel(): ListChange = ListChange(
    formatVersion = formatVersion,
    changeId = changeId,
    collectionId = collectionId,
    targetId = targetId,
    actorId = actorId,
    sourceSequence = sourceSequence,
    stamp = VersionStamp(logicalClock, stampActorId),
    operation = ListChangeOperation.valueOf(operation),
    payload = ListChangePayload.decode(payload),
)

private fun ListChange.toEntity(isPending: Boolean): ListChangeEntity = ListChangeEntity(
    changeId = changeId,
    formatVersion = formatVersion,
    collectionId = collectionId,
    targetId = targetId,
    actorId = actorId,
    sourceSequence = sourceSequence,
    logicalClock = stamp.logicalClock,
    stampActorId = stamp.actorId,
    operation = operation.name,
    payload = payload.encode(),
    isPending = isPending,
)
