package com.kernel.ai.core.memory.nextcloud

import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.NextcloudCollectionBindingDao
import com.kernel.ai.core.memory.dao.NextcloudItemBindingDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.entity.NextcloudItemBindingEntity
import com.kernel.ai.core.memory.lists.ListLifecycle
import com.kernel.ai.core.memory.lists.OrderKey
import com.kernel.ai.core.memory.lists.SharedCollectionSnapshot
import com.kernel.ai.core.memory.lists.SharedItemSnapshot
import com.kernel.ai.core.memory.lists.VersionStamp
import com.kernel.ai.core.memory.repository.ListMutationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    /** Collection ids with a synchronization in flight, so the UI can show `Syncing…` (#1551). */
    private val activeSyncs = MutableStateFlow<Set<String>>(emptySet())

    private val accountConfigured = MutableStateFlow(accountStore.read() != null)

    /**
     * Per-list Nextcloud state, combining persisted binding metadata with the transient in-flight
     * overlay. Stopped lists keep their binding and report [NextcloudListState.SYNC_OFF]; a recorded
     * failure reports [NextcloudListState.NEEDS_ATTENTION] until the next successful synchronization.
     */
    fun observeListBindings(): Flow<List<NextcloudListBinding>> =
        combine(collectionBindings.observeSyncSummaries(), activeSyncs) { summaries, active ->
            summaries.map { summary ->
                NextcloudListBinding(
                    collectionId = summary.collectionId,
                    remoteHref = summary.remoteHref,
                    state = summary.state(summary.collectionId in active),
                )
            }
        }

    fun account(): NextcloudAccount? = accountStore.read()?.account

    /**
     * Observable "an account is stored" signal. The Lists surfaces use it to decide whether a
     * per-list sync action can run immediately or must first route through Nextcloud setup.
     */
    fun observeAccountConfigured(): StateFlow<Boolean> = accountConfigured

    fun saveAccount(
        serverUrl: String,
        username: String,
        appPassword: String,
        allowInsecureHttp: Boolean = false,
    ) {
        accountStore.save(serverUrl, username, appPassword, allowInsecureHttp)
        accountConfigured.value = true
    }

    fun clearAccount() {
        accountStore.clear()
        accountConfigured.value = false
    }

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
        if (existingBinding == null) {
            publishNewBinding(list)
        } else {
            publishToExistingBinding(list, existingBinding)
        }
    }

    /**
     * Pushes to an association that is already durable.
     *
     * The binding is never removed here: a failed push is recorded on the row and reported, so the
     * list keeps its association and stays retryable.
     */
    private suspend fun publishToExistingBinding(
        list: ListNameEntity,
        binding: NextcloudCollectionBindingEntity,
    ) {
        try {
            val client = client()
            syncMutex.withLock {
                withActiveSync(list.collectionId) { pushCollection(client, list.collectionId, binding) }
            }
            recordSuccess(list.collectionId)
        } catch (error: Exception) {
            (error as? NextcloudFailure)?.let { recordFailure(list.collectionId, it) }
            throw error
        }
    }

    /**
     * Creates the first association for a list (#1551).
     *
     * The collection binding is deliberately **not** persisted until the initial push has succeeded.
     * A provisional row would be visible to [syncAll] — which snapshots bindings and runs
     * concurrently — so a worker could reconcile, or a later `pullCollection()` could re-create, an
     * association whose first-time setup actually failed. Keeping the row in memory until the push
     * succeeds means there is nothing for the background path to observe, and the push plus the
     * cleanup of anything it wrote stay inside [syncMutex].
     *
     * The remote collection is left in place on failure; it simply appears as a Nextcloud-only list.
     */
    private suspend fun publishNewBinding(list: ListNameEntity) {
        val client = client()
        val discovery = client.discover()
        val collection = client.createCollection(list.canonicalTitle, discovery.calendarHomeHref)
        val binding = NextcloudCollectionBindingEntity(
            collectionId = list.collectionId,
            remoteHref = collection.href,
            remoteTitle = collection.displayName,
            remoteEtag = null,
            remoteLogicalClock = 0L,
            updatedAt = System.currentTimeMillis(),
            // A list that was just connected participates in automatic synchronization.
            syncEnabled = true,
        )
        val itemIdsBeforePush = itemBindings.getAll(list.collectionId).mapTo(HashSet()) { it.itemId }
        try {
            syncMutex.withLock {
                withActiveSync(list.collectionId) {
                    pushCollection(client, list.collectionId, binding)
                    // Durable only now, so a failed initial push leaves no association at all.
                    collectionBindings.upsert(binding)
                }
            }
            recordSuccess(list.collectionId)
        } catch (error: Exception) {
            rollbackNewItemBindings(list.collectionId, itemIdsBeforePush)
            throw error
        }
    }

    /**
     * Removes item bindings written by a failed first-time push.
     *
     * Only rows this attempt added are deleted, so a pre-existing association can never lose its
     * item metadata. The local list and its items are untouched.
     */
    private suspend fun rollbackNewItemBindings(collectionId: String, itemIdsBefore: Set<String>) {
        itemBindings.getAll(collectionId)
            .filter { it.itemId !in itemIdsBefore }
            .forEach { itemBindings.delete(it.itemId) }
    }

    suspend fun syncAll(): NextcloudSyncResult = guardedResult {
        val client = client()
        var changed = false
        var failure: NextcloudFailure? = null
        // A list whose sync was stopped keeps its binding and remote collection but is skipped here.
        collectionBindings.getAll().filter { it.syncEnabled }.forEach { binding ->
            try {
                changed = withActiveSync(binding.collectionId) { syncBoundCollection(client, binding) } || changed
                recordSuccess(binding.collectionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NextcloudFailure) {
                recordFailure(binding.collectionId, error)
                if (failure == null) failure = error
            } catch (error: Exception) {
                val mapped = NextcloudConnectionException(
                    NextcloudFailure.Code.NETWORK,
                    "Nextcloud sync could not complete. Try again when the server is reachable.",
                    error,
                )
                recordFailure(binding.collectionId, mapped)
                if (failure == null) failure = mapped
            }
        }
        val firstFailure = failure
        if (firstFailure != null) NextcloudSyncResult.Failure(firstFailure) else NextcloudSyncResult.Success(changed)
    }

    suspend fun syncCollection(collectionId: String): NextcloudSyncResult = guardedResult {
        val binding = collectionBindings.get(collectionId) ?: return@guardedResult NextcloudSyncResult.Success()
        if (!binding.syncEnabled) return@guardedResult NextcloudSyncResult.Success()
        try {
            val changed = withActiveSync(collectionId) { syncBoundCollection(client(), binding) }
            recordSuccess(collectionId)
            NextcloudSyncResult.Success(changed)
        } catch (error: Exception) {
            (error as? NextcloudFailure)?.let { recordFailure(collectionId, it) }
            throw error
        }
    }

    /**
     * Stops Nextcloud synchronization for one list only (#1551).
     *
     * The local list, its items and the existing remote collection are all preserved; only the
     * binding's [NextcloudCollectionBindingEntity.syncEnabled] flag changes, so a later
     * [resumeSync] reuses the same association instead of creating a second remote collection.
     *
     * Serialized with [syncMutex] so an in-flight reconciliation cannot write the binding back as
     * enabled after Stop returns: whichever order they take the lock, the flag ends up false.
     */
    suspend fun stopSync(collectionId: String): Boolean = syncMutex.withLock {
        val binding = collectionBindings.get(collectionId) ?: return@withLock false
        if (binding.syncEnabled) {
            collectionBindings.setSyncEnabled(collectionId, false, System.currentTimeMillis())
        }
        true
    }

    /**
     * Resumes synchronization through the retained provider association and reconciles once so the
     * local and remote copies converge immediately.
     */
    suspend fun resumeSync(collectionId: String): NextcloudSyncResult {
        val binding = collectionBindings.get(collectionId)
            ?: return NextcloudSyncResult.Failure(
                NextcloudConnectionException(
                    NextcloudFailure.Code.INVALID_ACCOUNT,
                    "This list is no longer connected to Nextcloud.",
                ),
            )
        if (!binding.syncEnabled) {
            collectionBindings.setSyncEnabled(collectionId, true, System.currentTimeMillis())
        }
        return syncCollection(collectionId)
    }

    private suspend fun syncBoundCollection(client: NextcloudCalDavClient, original: NextcloudCollectionBindingEntity): Boolean {
        return syncMutex.withLock {
            // Re-read under the lock: a Stop may have landed since the caller snapshotted bindings,
            // in which case this list must not be synchronized at all.
            val binding = collectionBindings.get(original.collectionId) ?: original
            if (!binding.syncEnabled) return@withLock false
            try {
                pushCollection(client, binding.collectionId, binding)
            } catch (_: NextcloudConflictException) {
                pullCollection(client, binding)
                pushCollection(client, binding.collectionId, binding)
            } catch (error: NextcloudConnectionException) {
                if (error.code != NextcloudFailure.Code.PERMISSION) throw error
                // A read-only collection must still pull remote changes. Keep the original
                // permission failure so the caller reports it and pending local changes remain
                // retryable after the server-side permission is restored.
                pullCollection(client, binding)
                throw error
            }
            val pulled = pullCollection(client, collectionBindings.get(binding.collectionId) ?: binding)
            val latest = collectionBindings.get(binding.collectionId) ?: binding
            pushCollection(client, binding.collectionId, latest)
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

    /** Marks a collection as actively synchronizing so the UI can show `Syncing…` (#1551). */
    private suspend fun <T> withActiveSync(collectionId: String, block: suspend () -> T): T {
        activeSyncs.value = activeSyncs.value + collectionId
        return try {
            block()
        } finally {
            activeSyncs.value = activeSyncs.value - collectionId
        }
    }

    private suspend fun recordSuccess(collectionId: String) {
        if (collectionBindings.get(collectionId)?.lastFailureCode != null) {
            collectionBindings.recordSyncOutcome(collectionId, null, null)
        }
    }

    private suspend fun recordFailure(collectionId: String, failure: NextcloudFailure) {
        collectionBindings.recordSyncOutcome(collectionId, failure.code.name, System.currentTimeMillis())
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
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
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
