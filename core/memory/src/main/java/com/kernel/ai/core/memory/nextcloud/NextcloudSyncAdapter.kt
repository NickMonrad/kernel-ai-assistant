package com.kernel.ai.core.memory.nextcloud

import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.NextcloudCollectionBindingDao
import com.kernel.ai.core.memory.dao.NextcloudItemBindingDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.entity.NextcloudItemBindingEntity
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.OrderKey
import com.kernel.ai.core.memory.lists.SharedCollectionSnapshot
import com.kernel.ai.core.memory.lists.SharedItemSnapshot
import com.kernel.ai.core.memory.lists.VersionStamp
import com.kernel.ai.core.memory.repository.ListMutationRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val PROVIDER_ACTOR = "nextcloud-caldav"
private const val PROVIDER_ALIAS = "Nextcloud"

sealed interface NextcloudSyncResult {
    data class Success(val changed: Boolean = false) : NextcloudSyncResult
    data class Failure(val error: NextcloudFailure) : NextcloudSyncResult
}

/**
 * One-account provider adapter. Room/ListMutationRepository remain authoritative; this class only
 * translates CalDAV resources to snapshots and persists provider metadata separately.
 */
@Singleton
class NextcloudSyncAdapter @Inject constructor(
    private val accountStore: NextcloudCredentialStore,
    private val transport: CalDavTransport,
    private val collectionBindings: NextcloudCollectionBindingDao,
    private val itemBindings: NextcloudItemBindingDao,
    private val listItemDao: ListItemDao,
    private val listNameDao: ListNameDao,
    private val mutations: ListMutationRepository,
) {
    private val syncMutex = Mutex()

    fun account(): NextcloudAccount? = accountStore.read()?.account

    fun saveAccount(serverUrl: String, username: String, appPassword: String) =
        accountStore.save(serverUrl, username, appPassword)

    fun clearAccount() = accountStore.clear()

    suspend fun testConnection(): Result<NextcloudDiscovery> = runCatching {
        val credentials = accountStore.read() ?: throw NextcloudConnectionException(
            NextcloudFailure.Code.INVALID_ACCOUNT,
            "Configure a Nextcloud server, username, and app password first.",
        )
        NextcloudCalDavClient(credentials, transport).discover()
    }

    suspend fun testConnection(credentials: NextcloudAccountCredentials): Result<NextcloudDiscovery> =
        runCatching { NextcloudCalDavClient(credentials, transport).discover() }

    suspend fun discoverCollections(): Result<List<NextcloudCalendarCollection>> =
        testConnection().map { it.collections }

    suspend fun discoverCollections(credentials: NextcloudAccountCredentials): Result<List<NextcloudCalendarCollection>> =
        testConnection(credentials).map { it.collections }

    suspend fun importCollection(collection: NextcloudCalendarCollection): Result<Long> = guarded {
        val existingBinding = collectionBindings.getByRemoteHref(collection.href)
        if (existingBinding != null) {
            return@guarded requireNotNull(
                listNameDao.getByCollectionId(existingBinding.collectionId),
            ).id
        }
        val client = client()
        val remoteItems = client.fetchTasks(collection.href)
        require(remoteItems.map { it.uid() }.toSet().size == remoteItems.size) {
            "Nextcloud returned duplicate VTODO UIDs"
        }
        val collectionId = UUID.randomUUID().toString()
        val itemIds = remoteItems.associate { remote -> remote.uid() to UUID.randomUUID().toString() }
        val snapshots = remoteItems.mapIndexed { index, remote ->
            remote.toSnapshot(
                itemId = itemIds.getValue(remote.uid()),
                parentItemId = remote.parentUid()?.let(itemIds::get),
                revision = index.toLong() + 1L,
                fallbackOrder = index.toString(),
            )
        }
        val now = System.currentTimeMillis()
        val imported = mutations.importSnapshot(
            SharedCollectionSnapshot(
                collectionId = collectionId,
                canonicalTitle = collection.displayName,
                lifecycle = ListLifecycle.ACTIVE,
                createdAt = now,
                titleStamp = VersionStamp(1, PROVIDER_ACTOR),
                lifecycleStamp = VersionStamp(1, PROVIDER_ACTOR),
                items = snapshots,
                checkpoints = emptyList(),
            ),
            displayAliasLabel = PROVIDER_ALIAS,
        )
        collectionBindings.upsert(
            NextcloudCollectionBindingEntity(
                collectionId = collectionId,
                remoteHref = collection.href,
                remoteTitle = collection.displayName,
                remoteEtag = null,
                remoteLogicalClock = remoteItems.size.toLong().coerceAtLeast(1L),
                updatedAt = now,
            ),
        )
        remoteItems.forEach { remote ->
            val localItemId = itemIds.getValue(remote.uid())
            itemBindings.upsert(
                remote.toBinding(collectionId, localItemId, revision = 1L, deletedRemotely = false),
            )
        }
        imported.listId
    }

    suspend fun publishCollection(listId: Long): Result<Unit> = guarded {
        val list = requireNotNull(listNameDao.getById(listId)) { "Unknown list" }
        val existingBinding = collectionBindings.get(list.collectionId)
        val client = client()
        val binding = existingBinding ?: run {
            val discovery = client.discover()
            val collection = client.createCollection(list.canonicalTitle, discovery.calendarHomeHref)
            NextcloudCollectionBindingEntity(
                collectionId = list.collectionId,
                remoteHref = collection.href,
                remoteTitle = collection.displayName,
                remoteEtag = null,
                remoteLogicalClock = 0L,
                updatedAt = System.currentTimeMillis(),
            ).also { collectionBindings.upsert(it) }
        }
        pushCollection(client, list.collectionId, binding)
    }

    suspend fun syncAll(): NextcloudSyncResult = guardedResult {
        val client = client()
        var changed = false
        var failure: NextcloudFailure? = null
        collectionBindings.getAll().forEach { binding ->
            try {
                changed = syncBoundCollection(client, binding) || changed
            } catch (error: NextcloudFailure) {
                if (failure == null) failure = error
            } catch (error: Exception) {
                if (failure == null) {
                    failure = NextcloudConnectionException(
                        NextcloudFailure.Code.NETWORK,
                        "Nextcloud sync could not complete. Try again when the server is reachable.",
                        error,
                    )
                }
            }
        }
        val firstFailure = failure
        if (firstFailure != null) NextcloudSyncResult.Failure(firstFailure) else NextcloudSyncResult.Success(changed)
    }

    suspend fun syncCollection(collectionId: String): NextcloudSyncResult = guardedResult {
        val binding = collectionBindings.get(collectionId) ?: return@guardedResult NextcloudSyncResult.Success()
        NextcloudSyncResult.Success(syncBoundCollection(client(), binding))
    }

    private suspend fun syncBoundCollection(client: NextcloudCalDavClient, original: NextcloudCollectionBindingEntity): Boolean {
        return syncMutex.withLock {
            try {
                pushCollection(client, original.collectionId, original)
            } catch (_: NextcloudConflictException) {
                pullCollection(client, original)
                pushCollection(client, original.collectionId, original)
            } catch (error: NextcloudConnectionException) {
                if (error.code != NextcloudFailure.Code.PERMISSION) throw error
                // A read-only collection must still pull remote changes. Keep the original
                // permission failure so the caller reports it and pending local changes remain
                // retryable after the server-side permission is restored.
                pullCollection(client, original)
                throw error
            }
            val pulled = pullCollection(client, collectionBindings.get(original.collectionId) ?: original)
            val latest = collectionBindings.get(original.collectionId) ?: original
            pushCollection(client, original.collectionId, latest)
            pulled
        }
    }

    private suspend fun pushCollection(
        client: NextcloudCalDavClient,
        collectionId: String,
        binding: NextcloudCollectionBindingEntity,
    ) {
        val list = listNameDao.getByCollectionId(collectionId) ?: return
        val rows = listItemDao.getAllByListAnyLifecycle(list.id)
        val existing = itemBindings.getAll(collectionId).associateBy { it.itemId }.toMutableMap()
        val uidByItem = rows.associate { row ->
            row.itemId to (existing[row.itemId]?.remoteUid ?: UUID.randomUUID().toString())
        }
        val now = System.currentTimeMillis()
        rows.filter { it.lifecycle == ListLifecycle.ACTIVE.name }
            .sortedWith(compareBy<ListItemEntity> { it.parentItemId != null }.thenBy { OrderKey.canonical(it.orderKey) })
            .forEach { row ->
                val itemBinding = existing[row.itemId]
                val href = itemBinding?.remoteHref ?: URI(binding.remoteHref).resolve("${uidByItem.getValue(row.itemId)}.ics").toString()
                val document = if (itemBinding == null) {
                    VTodoDocument.new(
                        uid = uidByItem.getValue(row.itemId),
                        summary = row.text,
                        checked = row.checked,
                        dueAt = row.dueAt,
                        parentUid = row.parentItemId?.let(uidByItem::get),
                        orderKey = row.orderKey,
                    )
                } else {
                    VTodoDocument.parse(itemBinding.rawVtodo).also { doc ->
                        doc.setUid(itemBinding.remoteUid)
                        doc.replaceSingle("SUMMARY", row.text, escapeText = true)
                        doc.replaceSingle("STATUS", if (row.checked) "COMPLETED" else "NEEDS-ACTION")
                        if (row.checked && doc.first("COMPLETED") == null) doc.replaceSingle("COMPLETED", formatUtcMillis(row.updatedAt))
                        if (!row.checked) doc.remove("COMPLETED")
                        if (row.dueAt == null) doc.remove("DUE") else doc.replaceSingle("DUE", formatUtcMillis(row.dueAt))
                        doc.replaceParent(row.parentItemId?.let(uidByItem::get))
                        doc.replaceSingle("X-JANDAL-ORDER", row.orderKey)
                    }
                }
                val rendered = document.render()
                if (itemBinding == null || rendered != itemBinding.rawVtodo || itemBinding.deletedRemotely) {
                    val etag = client.putTask(
                        href,
                        rendered,
                        itemBinding?.etag?.takeUnless { itemBinding.deletedRemotely },
                    )
                    val updated = NextcloudItemBindingEntity(
                        itemId = row.itemId,
                        collectionId = collectionId,
                        remoteUid = uidByItem.getValue(row.itemId),
                        remoteHref = href,
                        etag = etag ?: itemBinding?.etag,
                        rawVtodo = rendered,
                        remoteLogicalClock = (itemBinding?.remoteLogicalClock ?: 0L) + 1L,
                        deletedRemotely = false,
                        updatedAt = now,
                    )
                    itemBindings.upsert(updated)
                    existing[row.itemId] = updated
                }
            }
        rows.filter { it.lifecycle == ListLifecycle.DELETED.name }.forEach { row ->
            val itemBinding = existing[row.itemId] ?: return@forEach
            if (!itemBinding.deletedRemotely) {
                client.deleteTask(itemBinding.remoteHref, itemBinding.etag)
                itemBindings.upsert(itemBinding.copy(deletedRemotely = true, updatedAt = now))
            }
        }
        val pending = mutations.pendingChanges().filter { it.collectionId == collectionId }
        mutations.acknowledgePushed(pending.map { it.changeId })
    }

    private suspend fun pullCollection(
        client: NextcloudCalDavClient,
        binding: NextcloudCollectionBindingEntity,
    ): Boolean {
        val list = listNameDao.getByCollectionId(binding.collectionId) ?: return false
        val remoteTitle = client.discover().collections
            .firstOrNull { it.href == binding.remoteHref }?.displayName
            ?: binding.remoteTitle
        val remoteItems = client.fetchTasks(binding.remoteHref)
        val oldBindings = itemBindings.getAll(binding.collectionId).associateBy { it.remoteUid }
        val remoteByUid = remoteItems.associateBy { it.uid() }
        var revision = binding.remoteLogicalClock
        val snapshots = mutableListOf<SharedItemSnapshot>()
        val updates = mutableListOf<NextcloudItemBindingEntity>()
        val itemIds = oldBindings.values.associate { it.remoteUid to it.itemId }.toMutableMap()
        remoteItems.forEach { remote -> itemIds.putIfAbsent(remote.uid(), UUID.randomUUID().toString()) }
        remoteItems.forEachIndexed { index, remote ->
            val old = oldBindings[remote.uid()]
            val current = listItemDao.getByItemId(itemIds.getValue(remote.uid()))
            val changed = old == null || old.etag != remote.etag
            if (!changed && current != null) return@forEachIndexed
            revision += 1L
            snapshots += remote.toSnapshot(
                itemId = itemIds.getValue(remote.uid()),
                parentItemId = remote.parentUid()?.let(itemIds::get),
                revision = revision,
                fallbackOrder = index.toString(),
                current = current,
                base = old?.rawVtodo?.let(VTodoDocument::parse),
            )
            updates += remote.toBinding(binding.collectionId, itemIds.getValue(remote.uid()), revision, false)
        }
        oldBindings.values.filter { it.remoteUid !in remoteByUid && !it.deletedRemotely }.forEach { old ->
            val current = listItemDao.getByItemId(old.itemId) ?: return@forEach
            revision += 1L
            snapshots += current.toSnapshot(ListLifecycle.DELETED, VersionStamp(revision, PROVIDER_ACTOR))
            updates += old.copy(remoteLogicalClock = revision, deletedRemotely = true, updatedAt = System.currentTimeMillis())
        }
        val remoteTitleChanged = remoteTitle != binding.remoteTitle
        if (snapshots.isNotEmpty() || remoteTitleChanged) {
            mutations.importSnapshot(
                SharedCollectionSnapshot(
                    collectionId = binding.collectionId,
                    canonicalTitle = remoteTitle,
                    lifecycle = ListLifecycle.ACTIVE,
                    createdAt = list.createdAt,
                    titleStamp = VersionStamp(revision.coerceAtLeast(1L), PROVIDER_ACTOR),
                    lifecycleStamp = VersionStamp(revision.coerceAtLeast(1L), PROVIDER_ACTOR),
                    items = snapshots,
                    checkpoints = emptyList(),
                ),
                displayAliasLabel = PROVIDER_ALIAS,
            )
        }
        updates.forEach { itemBindings.upsert(it) }
        collectionBindings.upsert(binding.copy(remoteTitle = remoteTitle, remoteLogicalClock = revision, updatedAt = System.currentTimeMillis()))
        return snapshots.isNotEmpty()
    }

    private suspend fun client(): NextcloudCalDavClient = NextcloudCalDavClient(
        accountStore.read() ?: throw NextcloudConnectionException(
            NextcloudFailure.Code.INVALID_ACCOUNT,
            "Configure a Nextcloud server, username, and app password first.",
        ),
        transport,
    )

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = runCatching { block() }

    private suspend fun guardedResult(block: suspend () -> NextcloudSyncResult): NextcloudSyncResult =
        runCatching { block() }.getOrElse { error ->
            NextcloudSyncResult.Failure(
                error as? NextcloudFailure ?: NextcloudConnectionException(
                    NextcloudFailure.Code.NETWORK,
                    "Nextcloud sync could not complete. Try again when the server is reachable.",
                ),
            )
        }

    private fun RemoteVTodo.uid(): String = document.first("UID")?.value?.trim().orEmpty().ifBlank { href }

    private fun RemoteVTodo.parentUid(): String? = document.all("RELATED-TO")
        .firstOrNull { it.hasParameter("RELTYPE", "PARENT") }
        ?.value
        ?.trim()

    private fun RemoteVTodo.toSnapshot(
        itemId: String,
        parentItemId: String?,
        revision: Long,
        fallbackOrder: String,
        current: ListItemEntity? = null,
        base: VTodoDocument? = null,
    ): SharedItemSnapshot {
        val checked = document.first("STATUS")?.value.equals("COMPLETED", ignoreCase = true)
        val dueAt = parseUtcMillis(document.first("DUE")?.value)
        val text = document.decoded("SUMMARY").orEmpty()
        val remoteOrder = document.first("X-JANDAL-ORDER")?.value?.takeIf { it.toBigDecimalOrNull() != null } ?: fallbackOrder
        val changedText = base == null || base.decoded("SUMMARY") != document.decoded("SUMMARY")
        val changedChecked = base == null || !base.first("STATUS")?.value.equals(document.first("STATUS")?.value, ignoreCase = true)
        val changedDue = base == null || parseUtcMillis(base.first("DUE")?.value) != dueAt
        val changedParent = base == null || base.all("RELATED-TO").firstOrNull { it.hasParameter("RELTYPE", "PARENT") }?.value != parentUid()
        val changedOrder = base == null || base.first("X-JANDAL-ORDER")?.value != document.first("X-JANDAL-ORDER")?.value
        val provider = VersionStamp(revision, PROVIDER_ACTOR)
        val local = current
        return SharedItemSnapshot(
            itemId = itemId,
            text = if (changedText || local == null) text else local.text,
            checked = if (changedChecked || local == null) checked else local.checked,
            dueAt = if (changedDue || local == null) dueAt else local.dueAt,
            parentItemId = if (changedParent || local == null) parentItemId else local.parentItemId,
            orderKey = if (changedOrder || local == null) remoteOrder else local.orderKey,
            lifecycle = ListLifecycle.ACTIVE,
            createdAt = local?.createdAt ?: System.currentTimeMillis(),
            textStamp = if (changedText || local == null) provider else VersionStamp(local.textLogicalClock, local.textStampActorId),
            checkedStamp = if (changedChecked || local == null) provider else VersionStamp(local.checkedLogicalClock, local.checkedStampActorId),
            dueAtStamp = if (changedDue || local == null) provider else VersionStamp(local.dueAtLogicalClock, local.dueAtStampActorId),
            placementStamp = if (changedParent || changedOrder || local == null) provider else VersionStamp(local.placementLogicalClock, local.placementStampActorId),
            lifecycleStamp = if (local == null || local.lifecycle == ListLifecycle.DELETED.name) provider else VersionStamp(local.lifecycleLogicalClock, local.lifecycleStampActorId),
        )
    }

    private fun RemoteVTodo.toBinding(collectionId: String, itemId: String, revision: Long, deletedRemotely: Boolean) =
        NextcloudItemBindingEntity(
            itemId = itemId,
            collectionId = collectionId,
            remoteUid = uid(),
            remoteHref = href,
            etag = etag,
            rawVtodo = document.render(),
            remoteLogicalClock = revision,
            deletedRemotely = deletedRemotely,
            updatedAt = System.currentTimeMillis(),
        )

    private fun ListItemEntity.toSnapshot(lifecycle: ListLifecycle, stamp: VersionStamp) = SharedItemSnapshot(
        itemId = itemId,
        text = text,
        checked = checked,
        dueAt = dueAt,
        parentItemId = parentItemId,
        orderKey = orderKey,
        lifecycle = lifecycle,
        createdAt = createdAt,
        textStamp = stamp,
        checkedStamp = stamp,
        dueAtStamp = stamp,
        placementStamp = stamp,
        lifecycleStamp = stamp,
    )
}
