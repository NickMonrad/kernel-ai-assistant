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

private fun ListItemEntity.hasUninitializedDescription() =
    descriptionLogicalClock == 0L && descriptionStampActorId.isEmpty()

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
) : NextcloudSharingOperations {
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
                    remoteWritable = summary.remoteWritable,
                    remoteAvailable = summary.remoteAvailable,
                    unsyncedChanges = summary.unsyncedChanges,
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

    /**
     * Completes browser authentication without persisting credentials. Login Flow validates the
     * returned identity and scope; the caller owns secure persistence and CalDAV discovery.
     */
    suspend fun loginFlow(
        serverUrl: String,
        allowInsecureHttp: Boolean = false,
        onLoginUrl: suspend (String) -> Unit = {},
    ): Result<NextcloudAccountCredentials> {
        return try {
            val result = NextcloudLoginFlowClient(transport).authenticate(
                serverUrl = serverUrl,
                allowInsecureHttp = allowInsecureHttp,
                onLoginUrl = onLoginUrl,
            )
            Result.success(
                NextcloudAccountCredentials(
                    account = NextcloudAccount(
                        serverUrl = result.serverUrl,
                        username = result.username,
                        allowInsecureHttp = allowInsecureHttp,
                    ),
                    appPassword = result.appPassword,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

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
                remoteWritable = collection.writable,
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

    override suspend fun listShares(collectionId: String): Result<NextcloudShareListing> = guarded {
        val binding = requireBoundBinding(collectionId)
        val listing = client().listShares(binding.remoteHref)
        persistWritable(binding, listing.writable)
        listing
    }

    override suspend fun searchSharees(collectionId: String, query: String): Result<List<NextcloudSharee>> = guarded {
        requireBoundBinding(collectionId)
        val client = client()
        client.searchSharees(query, client.discover().davRootHref)
    }

    override suspend fun setShare(
        collectionId: String,
        principal: String,
        permission: NextcloudSharePermission,
    ): Result<Unit> = guarded {
        val binding = requireBoundBinding(collectionId)
        val client = client()
        val listing = client.listShares(binding.remoteHref)
        persistWritable(binding, listing.writable)
        if (!listing.writable) throw permissionFailure()
        client.setShare(binding.remoteHref, principal, permission)
    }

    override suspend fun removeShare(collectionId: String, principal: String): Result<Unit> = guarded {
        val binding = requireBoundBinding(collectionId)
        val client = client()
        val listing = client.listShares(binding.remoteHref)
        persistWritable(binding, listing.writable)
        if (!listing.writable) throw permissionFailure()
        client.removeShare(binding.remoteHref, principal)
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
                val current = quarantineUnsyncedWork(refreshCollectionAccess(client, binding))
                if (!current.remoteAvailable) throw accessRemovedFailure(current)
                if (current.blockedUnsyncedAt != null) throw unsyncedChangesFailure()
                if (!current.remoteWritable) throw permissionFailure()
                withActiveSync(list.collectionId) { pushCollection(client, list.collectionId, current) }
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

    /**
     * Explicitly preserves a read-only or unavailable shared list as an unbound local list (#1548).
     *
     * For a read-only share this is the proactive path: a new independent Jandal list receives the
     * visible local content while the shared list keeps following its owner. When access was already
     * removed there is no owner state left to follow, so the list itself becomes the local copy
     * instead of producing a duplicate.
     *
     * Either way the preserved list has no Nextcloud binding, no provider item metadata and no
     * pending provider changes, so it can never be pushed back or silently re-associated if the
     * owner shares the collection again.
     */
    suspend fun createLocalCopy(collectionId: String): Result<Long> = guarded {
        val binding = requireBoundBinding(collectionId)
        if (binding.remoteWritable && binding.remoteAvailable && binding.blockedUnsyncedAt == null) {
            throw localStateFailure("This Nextcloud list is still editable, so a local copy is not needed.")
        }
        syncMutex.withLock {
            preserveLocalState(binding, revertSharedList = binding.blockedUnsyncedAt != null)
        }
    }

    /**
     * Resolves local work stranded by a discovered read-only downgrade or a removed share (#1548).
     *
     * [NextcloudLocalWorkResolution.KEEP_LOCAL_COPY] preserves the local content in an unbound local
     * list and then brings the shared list back to the owner's state; when access is already gone the
     * shared list itself becomes the local copy, because there is no owner state left to follow.
     * [NextcloudLocalWorkResolution.DISCARD_LOCAL_CHANGES] drops the stranded edits and returns the
     * shared list to the owner's representation. Either way the stranded provider changes are
     * removed, so they can never auto-push later.
     *
     * @return the local list holding the preserved visible content.
     */
    suspend fun resolveLocalWork(
        collectionId: String,
        resolution: NextcloudLocalWorkResolution,
    ): Result<Long> = guarded {
        val binding = requireBoundBinding(collectionId)
        if (binding.blockedUnsyncedAt == null) {
            throw localStateFailure("This Nextcloud list has no unsynced changes to resolve.")
        }
        syncMutex.withLock {
            if (resolution == NextcloudLocalWorkResolution.KEEP_LOCAL_COPY) {
                preserveLocalState(binding, revertSharedList = true)
            } else {
                revertStrandedLocalWork(binding)
                // With access gone the association has nothing left to follow, so the explicit
                // discard is also the cleanup step that releases it.
                if (!binding.remoteAvailable) releaseBinding(collectionId)
                localListId(binding)
            }
        }
    }

    /**
     * Keeps the visible local content: a new unbound copy for a reachable share, or the list itself
     * once access is gone. [revertSharedList] returns the shared list to the owner's state, which is
     * what makes the shared copy read-only and owner-following again.
     */
    private suspend fun preserveLocalState(
        binding: NextcloudCollectionBindingEntity,
        revertSharedList: Boolean,
    ): Long {
        val listId = localListId(binding)
        if (!binding.remoteAvailable) {
            mutations.discardPendingChanges(binding.collectionId)
            releaseBinding(binding.collectionId)
            return listId
        }
        val copyListId = copyToLocalList(binding)
        if (revertSharedList) revertStrandedLocalWork(binding)
        return copyListId
    }

    private suspend fun localListId(binding: NextcloudCollectionBindingEntity): Long =
        listNameDao.getByCollectionId(binding.collectionId)?.id
            ?: throw localStateFailure("This list is no longer available locally.")

    /** Copies the visible content of one bound list into a new unbound local list (#1548). */
    private suspend fun copyToLocalList(binding: NextcloudCollectionBindingEntity): Long {
        val list = listNameDao.getByCollectionId(binding.collectionId)
            ?: throw localStateFailure("This list is no longer available locally.")
        return mutations.copyListAsLocal(list.id, "${list.canonicalTitle} (local copy)")
    }

    /**
     * Returns one collection to the provider representation Jandal last saw (#1548).
     *
     * The stranded local edits are replaced by the retained remote VTODO documents, locally created
     * rows are removed, and the collection's pending provider changes are dropped — that is the
     * "discard" half of both resolutions. No network call is needed: item bindings already hold the
     * last complete remote document per item.
     */
    private suspend fun revertStrandedLocalWork(binding: NextcloudCollectionBindingEntity) {
        providerRepresentation(binding)?.let { snapshot ->
            mutations.importSnapshot(snapshot, displayAliasLabel = PROVIDER_ALIAS, pruneLocalItems = true)
        }
        mutations.discardPendingChanges(binding.collectionId)
        val current = collectionBindings.get(binding.collectionId) ?: return
        if (current.blockedUnsyncedAt != null) {
            collectionBindings.upsert(current.copy(blockedUnsyncedAt = null, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Releases the provider association once the user has explicitly resolved a removed share. */
    private suspend fun releaseBinding(collectionId: String) {
        itemBindings.deleteForCollection(collectionId)
        collectionBindings.delete(collectionId)
    }

    /**
     * Builds the provider-authoritative snapshot of one collection from the retained remote
     * documents (#1548).
     *
     * Field stamps are newer than every local stamp so the merge in `importSnapshot` restores the
     * provider values, and the collection title follows the last discovered remote title.
     */
    private suspend fun providerRepresentation(
        binding: NextcloudCollectionBindingEntity,
    ): SharedCollectionSnapshot? {
        val list = listNameDao.getByCollectionId(binding.collectionId) ?: return null
        val bindings = itemBindings.getAll(binding.collectionId)
        val itemIdByUid = bindings.associate { it.remoteUid to it.itemId }
        val newestLocalClock = listItemDao.getAllByListAnyLifecycle(list.id)
            .maxOfOrNull { row ->
                maxOf(
                    row.textLogicalClock,
                    row.descriptionLogicalClock,
                    row.checkedLogicalClock,
                    row.dueAtLogicalClock,
                    row.placementLogicalClock,
                    row.lifecycleLogicalClock,
                )
            } ?: 0L
        val revision = maxOf(newestLocalClock, binding.remoteLogicalClock) + 1L
        val items = bindings.mapIndexedNotNull { index, itemBinding ->
            val document = runCatching { VTodoDocument.parse(itemBinding.rawVtodo) }.getOrNull()
                ?: return@mapIndexedNotNull null
            val remote = RemoteVTodo(itemBinding.remoteHref, itemBinding.etag, document)
            val snapshot = remote.toSnapshot(
                itemId = itemBinding.itemId,
                parentItemId = remote.parentUid()?.let(itemIdByUid::get),
                revision = revision,
                fallbackOrder = index.toString(),
            )
            // An item the owner already removed stays removed: the retained document is only the
            // last representation Jandal saw, not a live remote resource.
            if (itemBinding.deletedRemotely) snapshot.copy(lifecycle = ListLifecycle.DELETED) else snapshot
        }
        return SharedCollectionSnapshot(
            collectionId = binding.collectionId,
            canonicalTitle = binding.remoteTitle,
            lifecycle = ListLifecycle.ACTIVE,
            createdAt = list.createdAt,
            titleStamp = VersionStamp(revision, PROVIDER_ACTOR),
            lifecycleStamp = VersionStamp(revision, PROVIDER_ACTOR),
            items = items,
            checkpoints = emptyList(),
        )
    }

    private suspend fun syncBoundCollection(client: NextcloudCalDavClient, original: NextcloudCollectionBindingEntity): Boolean {
        return syncMutex.withLock {
            // Re-read under the lock: a Stop may have landed since the caller snapshotted bindings,
            // in which case this list must not be synchronized at all.
            val persisted = collectionBindings.get(original.collectionId) ?: original
            if (!persisted.syncEnabled) return@withLock false
            val binding = quarantineUnsyncedWork(refreshCollectionAccess(client, persisted))
            if (!binding.remoteAvailable) {
                // The owner removed the share (or deleted the collection). Nothing can be pulled or
                // pushed; the local list is preserved for the user's Keep as local copy / Discard
                // decision, and content changes stay rejected meanwhile (#1548).
                throw accessRemovedFailure(binding)
            }
            if (binding.blockedUnsyncedAt != null) {
                // Owner changes keep flowing while access still exists, but work accepted under the
                // stale cached permission is never published; it waits for explicit user resolution.
                pullCollection(client, binding)
                throw unsyncedChangesFailure()
            }
            if (!binding.remoteWritable) {
                // A read-only share still follows the owner; only the provider push path is closed.
                return@withLock pullCollection(client, binding)
            }
            try {
                pushCollection(client, binding.collectionId, binding)
            } catch (_: NextcloudConflictException) {
                pullCollection(client, binding)
                pushCollection(client, binding.collectionId, binding)
            }
            val pulled = pullCollection(client, collectionBindings.get(binding.collectionId) ?: binding)
            val latest = collectionBindings.get(binding.collectionId) ?: binding
            if (latest.remoteWritable && latest.blockedUnsyncedAt == null) {
                pushCollection(client, binding.collectionId, latest)
            }
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
                        description = row.description,
                    )
                } else {
                    VTodoDocument.parse(itemBinding.rawVtodo).also { doc ->
                        doc.setUid(itemBinding.remoteUid)
                        doc.replaceSingle("SUMMARY", row.text, escapeText = true)
                        doc.replaceSingle("STATUS", if (row.checked) "COMPLETED" else "NEEDS-ACTION")
                        if (row.checked && doc.first("COMPLETED") == null) doc.replaceSingle("COMPLETED", formatUtcMillis(row.updatedAt))
                        if (!row.checked) doc.remove("COMPLETED")
                        if (row.dueAt == null) doc.remove("DUE") else doc.replaceDueAt(row.dueAt)
                        if (!row.hasUninitializedDescription()) {
                            if (row.description.isEmpty()) doc.remove("DESCRIPTION")
                            else doc.replaceSingle("DESCRIPTION", row.description, escapeText = true)
                        }
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
        val remoteTitle = binding.remoteTitle
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
            val base = old?.rawVtodo?.let(VTodoDocument::parse)
            val descriptionNeedsBackfill = current?.hasUninitializedDescription() == true &&
                (remote.document.first("DESCRIPTION") != null || base?.first("DESCRIPTION") != null)
            val changed = old == null || old.etag != remote.etag || descriptionNeedsBackfill
            if (!changed && current != null) return@forEachIndexed
            revision += 1L
            snapshots += remote.toSnapshot(
                itemId = itemIds.getValue(remote.uid()),
                parentItemId = remote.parentUid()?.let(itemIds::get),
                revision = revision,
                fallbackOrder = index.toString(),
                current = current,
                base = base,
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

    private suspend fun requireBoundBinding(collectionId: String): NextcloudCollectionBindingEntity =
        collectionBindings.get(collectionId)
            ?: throw NextcloudConnectionException(
                NextcloudFailure.Code.PERMISSION,
                "This list is not connected to Nextcloud.",
            )

    private fun permissionFailure() = NextcloudConnectionException(
        NextcloudFailure.Code.PERMISSION,
        "Nextcloud denied write access to this task collection. Check its permissions and try again.",
    )

    /** The owner removed the share, so neither pull nor push is possible any more (#1548). */
    private fun accessRemovedFailure(binding: NextcloudCollectionBindingEntity) = NextcloudConnectionException(
        NextcloudFailure.Code.PERMISSION,
        if (binding.blockedUnsyncedAt != null) {
            "Nextcloud access to this shared list was removed. Keep it as a local copy to save the local changes, or discard them."
        } else {
            "Nextcloud access to this shared list was removed. Save it as a local copy to keep editing it."
        },
    )

    /** Local work accepted under a stale cached permission waits for explicit resolution (#1548). */
    private fun unsyncedChangesFailure() = NextcloudConnectionException(
        NextcloudFailure.Code.PERMISSION,
        "Local changes to this shared list are not synced because it became read-only. Keep them as a local copy or discard them.",
    )

    private fun localStateFailure(message: String) = NextcloudConnectionException(
        NextcloudFailure.Code.PERMISSION,
        message,
    )
    private suspend fun persistWritable(
        binding: NextcloudCollectionBindingEntity,
        writable: Boolean,
    ) {
        if (binding.remoteWritable != writable) {
            collectionBindings.upsert(
                binding.copy(
                    remoteWritable = writable,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Refreshes the durable provider metadata for one binding from CalDAV discovery.
     *
     * A bound collection that discovery no longer offers is recorded as unavailable rather than
     * silently kept: removal of the share is the only way the owner can take access away, so the list
     * must stop behaving like a synchronized one (#1548). Availability is restored the same way when
     * the collection comes back.
     */
    private suspend fun refreshCollectionAccess(
        client: NextcloudCalDavClient,
        binding: NextcloudCollectionBindingEntity,
    ): NextcloudCollectionBindingEntity {
        val collection = client.discover().collections.firstOrNull { it.href == binding.remoteHref }
        if (collection == null) {
            if (!binding.remoteAvailable) return binding
            val removed = binding.copy(remoteAvailable = false, updatedAt = System.currentTimeMillis())
            collectionBindings.upsert(removed)
            return removed
        }
        val remoteMetadataChanged =
            collection.displayName != binding.remoteTitle ||
                collection.writable != binding.remoteWritable ||
                !binding.remoteAvailable
        val refreshed = binding.copy(
            remoteTitle = collection.displayName,
            remoteWritable = collection.writable,
            remoteAvailable = true,
            updatedAt = if (remoteMetadataChanged) System.currentTimeMillis() else binding.updatedAt,
        )
        if (refreshed != binding) collectionBindings.upsert(refreshed)
        return refreshed
    }

    /**
     * Quarantines local work that the provider can no longer publish (#1548).
     *
     * A content mutation accepted under a stale cached permission is already durable when the
     * downgrade — or the share removal — is discovered at the next synchronization. Recording that
     * stranded state on the binding is what stops the push path permanently: even if write access
     * returns, nothing is published until the user explicitly keeps a local copy or discards the
     * changes, so the edit is neither pushed nor silently dropped.
     */
    private suspend fun quarantineUnsyncedWork(
        binding: NextcloudCollectionBindingEntity,
    ): NextcloudCollectionBindingEntity {
        if (binding.blockedUnsyncedAt != null) return binding
        if (binding.remoteAvailable && binding.remoteWritable) return binding
        if (mutations.pendingChanges().none { it.collectionId == binding.collectionId }) return binding
        val blocked = binding.copy(blockedUnsyncedAt = System.currentTimeMillis())
        collectionBindings.upsert(blocked)
        return blocked
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
        val description = document.decoded("DESCRIPTION").orEmpty()
        val remoteOrder = document.first("X-JANDAL-ORDER")?.value?.takeIf { it.toBigDecimalOrNull() != null } ?: fallbackOrder
        val changedText = base == null || base.decoded("SUMMARY") != document.decoded("SUMMARY")
        val descriptionNeedsBackfill = current?.hasUninitializedDescription() == true &&
            (document.first("DESCRIPTION") != null || base?.first("DESCRIPTION") != null)
        val changedDescription = base == null ||
            base.decoded("DESCRIPTION").orEmpty() != description ||
            descriptionNeedsBackfill
        val changedChecked = base == null || !base.first("STATUS")?.value.equals(document.first("STATUS")?.value, ignoreCase = true)
        val changedDue = base == null || parseUtcMillis(base.first("DUE")?.value) != dueAt
        val changedParent = base == null || base.all("RELATED-TO").firstOrNull { it.hasParameter("RELTYPE", "PARENT") }?.value != parentUid()
        val changedOrder = base == null || base.first("X-JANDAL-ORDER")?.value != document.first("X-JANDAL-ORDER")?.value
        val provider = VersionStamp(revision, PROVIDER_ACTOR)
        val local = current
        return SharedItemSnapshot(
            itemId = itemId,
            text = if (changedText || local == null) text else local.text,
            description = if (changedDescription || local == null) description else local.description,
            checked = if (changedChecked || local == null) checked else local.checked,
            dueAt = if (changedDue || local == null) dueAt else local.dueAt,
            parentItemId = if (changedParent || local == null) parentItemId else local.parentItemId,
            orderKey = if (changedOrder || local == null) remoteOrder else local.orderKey,
            lifecycle = ListLifecycle.ACTIVE,
            createdAt = local?.createdAt ?: System.currentTimeMillis(),
            textStamp = if (changedText || local == null) provider else VersionStamp(local.textLogicalClock, local.textStampActorId),
            descriptionStamp = if (changedDescription || local == null) provider else VersionStamp(local.descriptionLogicalClock, local.descriptionStampActorId),
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
        description = description,
        checked = checked,
        dueAt = dueAt,
        parentItemId = parentItemId,
        orderKey = orderKey,
        lifecycle = lifecycle,
        createdAt = createdAt,
        textStamp = stamp,
        descriptionStamp = stamp,
        checkedStamp = stamp,
        dueAtStamp = stamp,
        placementStamp = stamp,
        lifecycleStamp = stamp,
    )
}
