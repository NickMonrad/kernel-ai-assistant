package com.kernel.ai.nextcloudacceptance

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.BuildConfig
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.MemoryModule
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.nextcloud.NextcloudAccountStore
import com.kernel.ai.core.memory.nextcloud.NextcloudCalendarCollection
import com.kernel.ai.core.memory.nextcloud.NextcloudCalDavClient
import com.kernel.ai.core.memory.nextcloud.NextcloudFailure
import com.kernel.ai.core.memory.nextcloud.NextcloudLocalWorkResolution
import com.kernel.ai.core.memory.nextcloud.NextcloudSharePermission
import com.kernel.ai.core.memory.nextcloud.NextcloudSharee
import com.kernel.ai.core.memory.nextcloud.NextcloudShareeType
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncResult
import com.kernel.ai.core.memory.nextcloud.OkHttpCalDavTransport
import com.kernel.ai.core.memory.repository.ListMutationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical acceptance steps. The host runner supplies one step at a time after checking the
 * connected device and installed package; this test independently checks the reviewed build SHA,
 * account role, and generated fixture name before making any provider or local-data change.
 */
@RunWith(AndroidJUnit4::class)
class NextcloudSharingAcceptanceTest {
    @Test
    fun runRequestedAcceptanceStep() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Set nextcloud_acceptance=true to opt into real-account acceptance.",
            arguments.getString("nextcloud_acceptance") == "true",
        )
        val step = arguments.getString("step") ?: fail()
        val collectionName = arguments.getString("collection_name") ?: fail()
        val encodedRecipientUsername = arguments.getString("recipient_username_b64") ?: fail()
        val recipientUsername = String(
            Base64.decode(encodedRecipientUsername, Base64.URL_SAFE or Base64.NO_WRAP),
            Charsets.UTF_8,
        )
        demand(recipientUsername.isNotBlank())
        val reviewedHead = arguments.getString("reviewed_head") ?: fail()
        requireFixtureName(collectionName)
        demand(reviewedHead.matches(Regex("[0-9a-f]{40}")))
        demand(BuildConfig.GIT_SHA == reviewedHead.take(8))

        val ownerStep = step.startsWith("owner-")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountStore = NextcloudAccountStore(context)
        val account = accountStore.read()?.account ?: fail()
        if (ownerStep) {
            demand(!account.username.equals(recipientUsername, ignoreCase = true))
        } else {
            demand(account.username.equals(recipientUsername, ignoreCase = true))
        }

        val database = MemoryModule.provideKernelDatabase(context)
        try {
            val harness = createHarness(database, accountStore)
            execute(step, collectionName, recipientUsername, harness)
        } finally {
            database.close()
        }
    }

    private suspend fun execute(
        step: String,
        collectionName: String,
        recipientUsername: String,
        harness: Harness,
    ) {
        when (step) {
            "owner-create" -> createOwnerFixture(collectionName, harness)
            "owner-publish" -> publishOwnerFixture(collectionName, harness)
            "owner-share-create" -> setShare(collectionName, recipientUsername, NextcloudSharePermission.EDITABLE, create = true, harness)
            "owner-set-editable" -> setShare(collectionName, recipientUsername, NextcloudSharePermission.EDITABLE, create = false, harness)
            "owner-set-readonly" -> setShare(collectionName, recipientUsername, NextcloudSharePermission.READ_ONLY, create = false, harness)
            "owner-remove-share" -> removeShare(collectionName, recipientUsername, harness)
            "owner-add-owner-update" -> addOwnerUpdate(collectionName, harness)
            "owner-verify-recipient-write" -> verifyOwnerHas(collectionName, "recipient-write", harness)
            "owner-verify-owner-update" -> verifyOwnerHas(collectionName, "owner-update", harness)
            "owner-verify-stale-keep-absent" -> verifyOwnerAbsent(collectionName, listOf("stale-keep"), harness)
            "owner-verify-stale-discard-absent" -> verifyOwnerAbsent(collectionName, listOf("stale-discard"), harness)
            "owner-verify-local-only-absent" -> verifyOwnerAbsent(collectionName, listOf("local-only"), harness)
            "owner-verify-removed-work-absent" -> verifyOwnerAbsent(collectionName, listOf("removed-stale", "local-only"), harness)
            "owner-cleanup" -> cleanupOwner(collectionName, recipientUsername, harness)

            "recipient-import" -> importFixture(collectionName, harness)
            "recipient-add-editable" -> addAndSyncRecipientItem(collectionName, "recipient-write", harness)
            "recipient-refresh-readonly" -> refreshAndAssertWritable(collectionName, false, harness)
            "recipient-pull-owner-update" -> pullOwnerUpdate(collectionName, harness)
            "recipient-refresh-editable" -> refreshAndAssertWritable(collectionName, true, harness)
            "recipient-stop-sync" -> stopSync(collectionName, harness)
            "recipient-stale-keep" -> addAndAssertQuarantined(collectionName, "stale-keep", harness)
            "recipient-keep-stale" -> keepStrandedWork(collectionName, "stale-keep", harness)
            "recipient-proactive-copy" -> createProactiveCopy(collectionName, harness)
            "recipient-refresh-after-keep" -> refreshAndAssertWritable(collectionName, true, harness)
            "recipient-stale-discard" -> addAndAssertQuarantined(collectionName, "stale-discard", harness)
            "recipient-discard-stale" -> discardStrandedWork(collectionName, "stale-discard", harness)
            "recipient-refresh-after-discard" -> refreshAndAssertWritable(collectionName, true, harness)
            "recipient-resolve-removed-no-work" -> resolveRemovedShareWithoutWork(collectionName, harness)
            "recipient-local-copy-no-reassociate" -> mutateUnboundAfterReshare(collectionName, "local-only", harness)
            "recipient-stale-after-removal" -> addAndAssertUnavailableQuarantine(collectionName, "removed-stale", harness)
            "recipient-keep-removed-stale" -> keepRemovedWork(collectionName, "removed-stale", harness)
            "recipient-unbound-after-reshare" -> mutateUnboundAfterReshare(collectionName, "local-only", harness)
            "recipient-cleanup" -> cleanupRecipient(collectionName, harness)
            else -> fail()
        }
    }

    private fun createHarness(
        database: KernelDatabase,
        accountStore: NextcloudAccountStore,
    ): Harness {
        val itemDao = database.listItemDao()
        val nameDao = database.listNameDao()
        val collectionDao = database.nextcloudCollectionBindingDao()
        val itemBindingDao = database.nextcloudItemBindingDao()
        val transport = OkHttpCalDavTransport()
        val mutations = ListMutationRepository(
            database = database,
            listItemDao = itemDao,
            listNameDao = nameDao,
            actorDao = database.listActorStateDao(),
            appliedDao = database.listAppliedChangeDao(),
            changeDao = database.listChangeDao(),
            sourceDao = database.listSourceSequenceDao(),
            checkpointDao = database.listCheckpointDao(),
            nextcloudBindingDao = collectionDao,
        )
        val adapter = NextcloudSyncAdapter(
            accountStore,
            transport,
            collectionDao,
            itemBindingDao,
            itemDao,
            nameDao,
            mutations,
        )
        return Harness(
            accountStore = accountStore,
            adapter = adapter,
            mutations = mutations,
            database = database,
        )
    }

    private suspend fun createOwnerFixture(name: String, harness: Harness) {
        demand(harness.database.listNameDao().getByNameAnyLifecycle(name) == null)
        demand(harness.database.listNameDao().getAll().none { it.canonicalTitle == name })
        demand(harness.adapter.discoverCollections().getOrThrow().none { collectionMatchesFixture(name, it.href) })
        harness.mutations.createCollectionWithItems(name, listOf(token(name, "owner-seed")))
        requireSourceList(name, harness)
    }

    private suspend fun publishOwnerFixture(name: String, harness: Harness) {
        val list = requireSourceList(name, harness)
        demand(harness.database.nextcloudCollectionBindingDao().get(list.collectionId) == null)
        harness.adapter.publishCollection(list.id).getOrThrow()
        val (published, binding) = requireBoundList(name, harness)
        demand(published.id == list.id)
        requireRemoteMatches(name, binding, harness)
    }

    private suspend fun importFixture(name: String, harness: Harness) {
        demand(harness.database.listNameDao().getByNameAnyLifecycle(name) == null)
        demand(harness.database.listNameDao().getAll().none { it.canonicalTitle == name })
        val collection = requireUniqueRemoteCollection(name, harness)
        val localId = harness.adapter.importCollection(collection).getOrThrow()
        val imported = harness.database.listNameDao().getById(localId) ?: fail()
        demand(imported.canonicalTitle == collection.displayName)
        val binding = harness.database.nextcloudCollectionBindingDao().get(imported.collectionId) ?: fail()
        demand(binding.remoteHref == collection.href && binding.remoteTitle == collection.displayName)
    }

    private suspend fun setShare(
        name: String,
        recipientUsername: String,
        permission: NextcloudSharePermission,
        create: Boolean,
        harness: Harness,
    ) {
        val (list, binding) = requireBoundList(name, harness)
        requireRemoteMatches(name, binding, harness)
        val target = requireRecipientSharee(list.collectionId, recipientUsername, harness)
        val shares = harness.adapter.listShares(list.collectionId).getOrThrow().shares
        val alreadyShared = shares.any { it.principal == target.principal }
        demand(alreadyShared != create)
        harness.adapter.setShare(list.collectionId, target.principal, permission).getOrThrow()
    }

    private suspend fun removeShare(name: String, recipientUsername: String, harness: Harness) {
        val (list, binding) = requireBoundList(name, harness)
        requireRemoteMatches(name, binding, harness)
        val target = requireRecipientSharee(list.collectionId, recipientUsername, harness)
        val shares = harness.adapter.listShares(list.collectionId).getOrThrow().shares
        demand(shares.count { it.principal == target.principal } == 1)
        harness.adapter.removeShare(list.collectionId, target.principal).getOrThrow()
    }

    private suspend fun addOwnerUpdate(name: String, harness: Harness) {
        val list = requireSourceList(name, harness)
        val binding = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: fail()
        requireRemoteMatches(name, binding, harness)
        harness.mutations.addItem(list.id, token(name, "owner-update"))
        requireSyncSuccess(harness.adapter.syncCollection(list.collectionId))
    }

    private suspend fun addAndSyncRecipientItem(name: String, suffix: String, harness: Harness) {
        val (list, binding) = requireBoundList(name, harness)
        requireRemoteMatches(name, binding, harness)
        demand(binding.remoteWritable && binding.remoteAvailable && binding.blockedUnsyncedAt == null)
        harness.mutations.addItem(list.id, token(name, suffix))
        requireSyncSuccess(harness.adapter.syncCollection(list.collectionId))
        demand(harness.mutations.pendingChanges().none { it.collectionId == list.collectionId })
    }

    private suspend fun verifyOwnerHas(name: String, suffix: String, harness: Harness) {
        val list = requireSourceList(name, harness)
        val binding = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: fail()
        requireRemoteMatches(name, binding, harness)
        requireSyncSuccess(harness.adapter.syncCollection(list.collectionId))
        demand(hasActiveText(list, token(name, suffix), harness))
    }

    private suspend fun verifyOwnerAbsent(name: String, suffixes: List<String>, harness: Harness) {
        val list = requireSourceList(name, harness)
        val binding = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: fail()
        requireRemoteMatches(name, binding, harness)
        requireSyncSuccess(harness.adapter.syncCollection(list.collectionId))
        val activeTexts = harness.database.listItemDao().getAllByListAnyLifecycle(list.id)
            .filter { it.lifecycle == "ACTIVE" }
            .map { it.text }
            .toSet()
        demand(suffixes.none { token(name, it) in activeTexts })
    }

    private suspend fun pullOwnerUpdate(name: String, harness: Harness) {
        val (list, binding) = requireBoundList(name, harness)
        requireRemoteMatches(name, binding, harness)
        requireSyncSuccess(harness.adapter.syncCollection(list.collectionId))
        demand(hasActiveText(list, token(name, "owner-update"), harness))
        demand(harness.database.nextcloudCollectionBindingDao().get(list.collectionId)?.remoteWritable == false)
    }

    private suspend fun refreshAndAssertWritable(name: String, writable: Boolean, harness: Harness) {
        val (list, binding) = requireBoundList(name, harness)
        requireRemoteMatches(name, binding, harness)
        requireSyncSuccess(harness.adapter.syncCollection(list.collectionId))
        val refreshed = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: fail()
        demand(refreshed.remoteWritable == writable && refreshed.remoteAvailable)
        demand(refreshed.blockedUnsyncedAt == null)
    }

    private suspend fun stopSync(name: String, harness: Harness) {
        val (list, binding) = requireBoundList(name, harness)
        requireRemoteMatches(name, binding, harness)
        demand(harness.adapter.stopSync(list.collectionId))
        demand(harness.database.nextcloudCollectionBindingDao().get(list.collectionId)?.syncEnabled == false)
    }

    private suspend fun addAndAssertQuarantined(name: String, suffix: String, harness: Harness) {
        val (list, cachedBinding) = requireBoundList(name, harness)
        requireRemoteMatches(name, cachedBinding, harness)
        demand(
            cachedBinding.remoteAvailable &&
                cachedBinding.remoteWritable &&
                !cachedBinding.syncEnabled &&
                cachedBinding.blockedUnsyncedAt == null,
        )
        harness.mutations.addItem(list.id, token(name, suffix))
        val result = harness.adapter.resumeSync(list.collectionId)
        demand(result is NextcloudSyncResult.Failure && result.error.code == NextcloudFailure.Code.PERMISSION)
        val refreshed = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: fail()
        demand(refreshed.remoteWritable.not() && refreshed.blockedUnsyncedAt != null)
        demand(harness.mutations.pendingChanges().any { it.collectionId == list.collectionId })
    }

    private suspend fun keepStrandedWork(name: String, suffix: String, harness: Harness) {
        val (source, binding) = requireBoundList(name, harness)
        demand(binding.remoteAvailable && !binding.remoteWritable && binding.blockedUnsyncedAt != null)
        val copyId = harness.adapter.resolveLocalWork(
            source.collectionId,
            NextcloudLocalWorkResolution.KEEP_LOCAL_COPY,
        ).getOrThrow()
        val copy = harness.database.listNameDao().getById(copyId) ?: fail()
        demand(copy.id != source.id)
        assertIndependentLocalCopy(source, copy, token(name, suffix), harness)
        demand(harness.database.nextcloudCollectionBindingDao().get(source.collectionId)?.blockedUnsyncedAt == null)
        demand(harness.mutations.pendingChanges().none { it.collectionId == source.collectionId })
        demand(!hasActiveText(source, token(name, suffix), harness))
    }

    private suspend fun createProactiveCopy(name: String, harness: Harness) {
        val (source, binding) = requireBoundList(name, harness)
        demand(binding.remoteAvailable && !binding.remoteWritable && binding.blockedUnsyncedAt == null)
        val copyId = harness.adapter.createLocalCopy(source.collectionId).getOrThrow()
        val copy = harness.database.listNameDao().getById(copyId) ?: fail()
        demand(copy.id != source.id)
        assertIndependentLocalCopy(source, copy, null, harness)
        val stillBound = harness.database.nextcloudCollectionBindingDao().get(source.collectionId) ?: fail()
        demand(stillBound.remoteHref == binding.remoteHref && stillBound.remoteWritable.not())
    }

    private suspend fun discardStrandedWork(name: String, suffix: String, harness: Harness) {
        val (source, binding) = requireBoundList(name, harness)
        demand(binding.remoteAvailable && !binding.remoteWritable && binding.blockedUnsyncedAt != null)
        harness.adapter.resolveLocalWork(
            source.collectionId,
            NextcloudLocalWorkResolution.DISCARD_LOCAL_CHANGES,
        ).getOrThrow()
        demand(!hasActiveText(source, token(name, suffix), harness))
        demand(harness.database.nextcloudCollectionBindingDao().get(source.collectionId)?.blockedUnsyncedAt == null)
        demand(harness.mutations.pendingChanges().none { it.collectionId == source.collectionId })
    }

    private suspend fun resolveRemovedShareWithoutWork(name: String, harness: Harness) {
        val (source, binding) = requireBoundList(name, harness)
        val result = harness.adapter.syncCollection(source.collectionId)
        demand(result is NextcloudSyncResult.Failure && result.error.code == NextcloudFailure.Code.PERMISSION)
        val unavailable = harness.database.nextcloudCollectionBindingDao().get(source.collectionId) ?: fail()
        demand(!unavailable.remoteAvailable && unavailable.blockedUnsyncedAt == null)
        val localId = harness.adapter.createLocalCopy(source.collectionId).getOrThrow()
        demand(localId == source.id)
        demand(harness.database.nextcloudCollectionBindingDao().get(source.collectionId) == null)
        demand(harness.database.nextcloudItemBindingDao().getAll(source.collectionId).isEmpty())
        demand(harness.mutations.pendingChanges().none { it.collectionId == source.collectionId })
        demand(binding.remoteHref.isNotBlank())
    }

    private suspend fun addAndAssertUnavailableQuarantine(name: String, suffix: String, harness: Harness) {
        val (list, cachedBinding) = requireBoundList(name, harness)
        demand(cachedBinding.remoteAvailable && cachedBinding.remoteWritable)
        demand(!cachedBinding.syncEnabled && cachedBinding.blockedUnsyncedAt == null)
        harness.mutations.addItem(list.id, token(name, suffix))
        val result = harness.adapter.resumeSync(list.collectionId)
        demand(result is NextcloudSyncResult.Failure && result.error.code == NextcloudFailure.Code.PERMISSION)
        val unavailable = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: fail()
        demand(!unavailable.remoteAvailable && unavailable.blockedUnsyncedAt != null)
        demand(harness.mutations.pendingChanges().any { it.collectionId == list.collectionId })
    }

    private suspend fun keepRemovedWork(name: String, suffix: String, harness: Harness) {
        val (source, binding) = requireBoundList(name, harness)
        demand(!binding.remoteAvailable && binding.blockedUnsyncedAt != null)
        val retainedId = harness.adapter.resolveLocalWork(
            source.collectionId,
            NextcloudLocalWorkResolution.KEEP_LOCAL_COPY,
        ).getOrThrow()
        demand(retainedId == source.id)
        demand(harness.database.nextcloudCollectionBindingDao().get(source.collectionId) == null)
        demand(harness.database.nextcloudItemBindingDao().getAll(source.collectionId).isEmpty())
        demand(harness.mutations.pendingChanges().none { it.collectionId == source.collectionId })
        demand(hasActiveText(source, token(name, suffix), harness))
    }

    private suspend fun mutateUnboundAfterReshare(name: String, suffix: String, harness: Harness) {
        val remote = requireUniqueRemoteCollection(name, harness)
        val source = requireSourceList(name, harness)
        demand(harness.database.nextcloudCollectionBindingDao().get(source.collectionId) == null)
        demand(harness.database.nextcloudCollectionBindingDao().getByRemoteHref(remote.href) == null)
        demand(harness.database.nextcloudItemBindingDao().getAll(source.collectionId).isEmpty())
        harness.mutations.addItem(source.id, token(name, suffix))
        requireSyncSuccess(harness.adapter.syncCollection(source.collectionId))
        demand(hasActiveText(source, token(name, suffix), harness))
    }

    private suspend fun cleanupOwner(name: String, recipientUsername: String, harness: Harness) {
        val localLists = testLists(name, harness)
        val source = localLists.singleOrNull { it.canonicalTitle == name }
        val binding = source?.let { harness.database.nextcloudCollectionBindingDao().get(it.collectionId) }
        val remoteMatches = harness.adapter.discoverCollections().getOrThrow()
            .filter { collectionMatchesFixture(name, it.href) }
        demand(remoteMatches.size <= 1)
        val remote = remoteMatches.singleOrNull()
        if (remote != null && source != null && binding != null) {
            demand(remote.href == binding.remoteHref && binding.remoteTitle == remote.displayName)
            val target = requireRecipientSharee(source.collectionId, recipientUsername, harness)
            val shares = harness.adapter.listShares(source.collectionId).getOrThrow().shares
            if (shares.any { it.principal == target.principal }) {
                harness.adapter.removeShare(source.collectionId, target.principal).getOrThrow()
            }
        }
        if (remote != null) {
            val credentials = harness.accountStore.read() ?: fail()
            NextcloudCalDavClient(credentials, OkHttpCalDavTransport()).deleteCollection(remote.href)
        }
        if (source != null && binding != null) releaseRemovedBindingForCleanup(source, harness)
        deleteGeneratedLocalLists(name, harness)
    }

    private suspend fun cleanupRecipient(name: String, harness: Harness) {
        for (list in testLists(name, harness)) {
            val binding = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: continue
            val result = if (binding.syncEnabled) {
                harness.adapter.syncCollection(list.collectionId)
            } else {
                harness.adapter.resumeSync(list.collectionId)
            }
            val refreshed = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: continue
            if (refreshed.blockedUnsyncedAt != null) {
                harness.adapter.resolveLocalWork(list.collectionId, NextcloudLocalWorkResolution.KEEP_LOCAL_COPY).getOrThrow()
            } else if (!refreshed.remoteAvailable || !refreshed.remoteWritable) {
                harness.adapter.createLocalCopy(list.collectionId).getOrThrow()
            } else {
                demand(result is NextcloudSyncResult.Failure)
                fail()
            }
        }
        deleteGeneratedLocalLists(name, harness)
    }

    private suspend fun releaseRemovedBindingForCleanup(list: ListNameEntity, harness: Harness) {
        val binding = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: return
        val result = if (binding.syncEnabled) {
            harness.adapter.syncCollection(list.collectionId)
        } else {
            harness.adapter.resumeSync(list.collectionId)
        }
        val refreshed = harness.database.nextcloudCollectionBindingDao().get(list.collectionId) ?: return
        if (refreshed.blockedUnsyncedAt != null) {
            harness.adapter.resolveLocalWork(list.collectionId, NextcloudLocalWorkResolution.KEEP_LOCAL_COPY).getOrThrow()
        } else if (!refreshed.remoteAvailable) {
            harness.adapter.createLocalCopy(list.collectionId).getOrThrow()
        } else {
            demand(result is NextcloudSyncResult.Failure)
            fail()
        }
    }

    private suspend fun deleteGeneratedLocalLists(name: String, harness: Harness) {
        val lists = testLists(name, harness)
        lists.forEach { list ->
            demand(harness.database.nextcloudCollectionBindingDao().get(list.collectionId) == null)
            harness.mutations.deleteCollectionByName(list.name)
            harness.mutations.discardPendingChanges(list.collectionId)
        }
        demand(harness.mutations.pendingChanges().none { it.collectionId in lists.map(ListNameEntity::collectionId) })
    }

    private suspend fun assertIndependentLocalCopy(
        source: ListNameEntity,
        copy: ListNameEntity,
        requiredText: String?,
        harness: Harness,
    ) {
        demand(copy.collectionId != source.collectionId)
        demand(copy.canonicalTitle.startsWith("${source.canonicalTitle} (local copy)"))
        demand(harness.database.nextcloudCollectionBindingDao().get(copy.collectionId) == null)
        demand(harness.database.nextcloudItemBindingDao().getAll(copy.collectionId).isEmpty())
        demand(harness.mutations.pendingChanges().none { it.collectionId == copy.collectionId })
        val sourceRows = harness.database.listItemDao().getAllByListAnyLifecycle(source.id)
            .filter { it.lifecycle == "ACTIVE" }
        val copyRows = harness.database.listItemDao().getAllByListAnyLifecycle(copy.id)
            .filter { it.lifecycle == "ACTIVE" }
        val sourceTexts = sourceRows.map { it.text }.toSet()
        val copyTexts = copyRows.map { it.text }.toSet()
        val sourceItemIds = sourceRows.map { it.itemId }.toSet()
        val copyItemIds = copyRows.map { it.itemId }.toSet()
        demand(copyTexts.containsAll(sourceTexts))
        demand(copyItemIds.intersect(sourceItemIds).isEmpty())
        demand(copyRows.all { it.collectionId == copy.collectionId && it.notificationTime == null })
        demand(copyRows.all { it.parentItemId == null || it.parentItemId in copyItemIds })
        if (requiredText != null) demand(requiredText in copyTexts)
    }

    private suspend fun requireRecipientSharee(
        collectionId: String,
        username: String,
        harness: Harness,
    ): NextcloudSharee {
        val candidates = harness.adapter.searchSharees(collectionId, username).getOrThrow()
            .filter { sharee ->
                sharee.type == NextcloudShareeType.USER && (
                    sharee.displayName.equals(username, ignoreCase = true) ||
                        sharee.principal.trimEnd('/').substringAfterLast('/').equals(username, ignoreCase = true)
                    )
            }
        demand(candidates.size == 1)
        return candidates.single()
    }

    private suspend fun requireBoundList(
        name: String,
        harness: Harness,
    ): Pair<ListNameEntity, NextcloudCollectionBindingEntity> {
        val matches = harness.database.nextcloudCollectionBindingDao().getAll().mapNotNull { binding ->
            val list = harness.database.listNameDao().getByCollectionId(binding.collectionId)
            if (list != null && collectionMatchesFixture(name, binding.remoteHref)) list to binding else null
        }
        demand(matches.size == 1)
        return matches.single()
    }

    private suspend fun requireSourceList(name: String, harness: Harness): ListNameEntity {
        val lists = testLists(name, harness)
        val exact = lists.filter { it.canonicalTitle == name }
        if (exact.size == 1) return exact.single()
        val bound = lists.filter { list ->
            val binding = harness.database.nextcloudCollectionBindingDao().get(list.collectionId)
            binding != null && collectionMatchesFixture(name, binding.remoteHref)
        }
        if (bound.size == 1) return bound.single()
        val sourceCandidates = lists.filterNot { it.canonicalTitle.endsWith(" (local copy)") }
            .filter { list ->
                harness.database.listItemDao().getAllByListAnyLifecycle(list.id)
                    .any { it.text == token(name, "owner-seed") }
            }
        demand(sourceCandidates.size == 1)
        return sourceCandidates.single()
    }

    private suspend fun requireRemoteMatches(
        name: String,
        binding: NextcloudCollectionBindingEntity,
        harness: Harness,
    ): NextcloudCalendarCollection {
        val remote = requireUniqueRemoteCollection(name, harness)
        demand(remote.href == binding.remoteHref && binding.remoteTitle == remote.displayName)
        return remote
    }

    private suspend fun requireUniqueRemoteCollection(
        name: String,
        harness: Harness,
    ): NextcloudCalendarCollection {
        val matches = selectFixtureCollections(name, harness.adapter.discoverCollections().getOrThrow())
        demand(matches.size == 1)
        return matches.single()
    }

    private suspend fun testLists(name: String, harness: Harness): List<ListNameEntity> {
        val allLists = harness.database.listNameDao().getAll()
        return allLists.filter { list ->
            if (list.canonicalTitle == name || list.canonicalTitle.startsWith("$name (local copy)")) {
                true
            } else {
                val binding = harness.database.nextcloudCollectionBindingDao().get(list.collectionId)
                collectionMatchesFixture(name, binding?.remoteHref.orEmpty()) ||
                    harness.database.listItemDao().getAllByListAnyLifecycle(list.id)
                        .any { it.text.startsWith("${name}_") }
            }
        }
    }


    private suspend fun hasActiveText(list: ListNameEntity, text: String, harness: Harness): Boolean =
        harness.database.listItemDao().getAllByListAnyLifecycle(list.id)
            .any { it.lifecycle == "ACTIVE" && it.text == text }

    private fun requireFixtureName(name: String) {
        demand(name.matches(Regex("J1548-[0-9a-f]{16}")))
    }

    private fun token(name: String, suffix: String): String = "${name}_$suffix"

    private fun requireSyncSuccess(result: NextcloudSyncResult) {
        demand(result is NextcloudSyncResult.Success)
    }

    private fun demand(condition: Boolean) {
        if (!condition) fail()
    }

    private fun fail(): Nothing = throw AssertionError("Nextcloud acceptance precondition or assertion failed.")

    private data class Harness(
        val accountStore: NextcloudAccountStore,
        val adapter: NextcloudSyncAdapter,
        val mutations: ListMutationRepository,
        val database: KernelDatabase,
    )
}

internal fun collectionMatchesFixture(name: String, href: String): Boolean =
    href.split('/').any { segment ->
        segment.equals(name, ignoreCase = true) ||
            segment.startsWith("${name}_shared_by_", ignoreCase = true)
    }

internal fun selectFixtureCollections(
    name: String,
    collections: List<NextcloudCalendarCollection>,
): List<NextcloudCalendarCollection> = collections.filter {
    collectionMatchesFixture(name, it.href)
}
