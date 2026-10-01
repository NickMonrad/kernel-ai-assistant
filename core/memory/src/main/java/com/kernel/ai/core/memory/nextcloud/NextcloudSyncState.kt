package com.kernel.ai.core.memory.nextcloud

/**
 * Durable, user-visible synchronization state for one local list (#1551).
 *
 * [SYNCING] is a transient overlay applied while a synchronization for that list is in flight;
 * the other values are derived from persisted binding metadata only.
 */
enum class NextcloudListState {
    /** Bound with sync enabled and no recorded failure. */
    UP_TO_DATE,

    /** A synchronization for this list is running right now. */
    SYNCING,

    /** Bound, but the user stopped synchronization for this list. */
    SYNC_OFF,

    /** Bound, but the last attempt failed and remains retryable. */
    NEEDS_ATTENTION,

    /**
     * Local content changes were found to be unpublishable because provider access was lost (#1548):
     * a read-only downgrade or a removed share discovered after the edit. The work is retained and
     * never pushed; the user chooses **Keep as local copy** or **Discard local changes**.
     */
    UNSYNCED_CHANGES,

    /**
     * The bound collection is no longer present in this account's discovery — the owner removed the
     * share. The local list is preserved until the user keeps it as a local copy (#1548).
     */
    UNAVAILABLE,
}

/**
 * The user's explicit decision for local work that provider access stranded (#1548).
 *
 * [KEEP_LOCAL_COPY] preserves the visible local content in an unbound local list.
 * [DISCARD_LOCAL_CHANGES] returns the list to the provider representation Jandal last saw.
 */
enum class NextcloudLocalWorkResolution {
    KEEP_LOCAL_COPY,
    DISCARD_LOCAL_CHANGES,
}

/** Room projection of the per-list binding columns needed to render a list's Nextcloud state. */
data class NextcloudListSyncSummary(
    val collectionId: String,
    val remoteHref: String,
    val syncEnabled: Boolean,
    val lastFailureCode: String?,
    val remoteWritable: Boolean = true,
    val remoteAvailable: Boolean = true,
    val blockedUnsyncedAt: Long? = null,
) {
    /** True when local changes are stranded by lost provider access and await user resolution. */
    val unsyncedChanges: Boolean get() = blockedUnsyncedAt != null

    /**
     * Durable state, before the in-flight overlay.
     *
     * Lost provider access outranks the remaining flags: a list whose share was removed, or whose
     * stranded local work is waiting for the user's Keep as local copy / Discard decision, must not
     * read as healthy even when a failure was recorded earlier or the user stopped sync afterwards.
     * An explicit Stop then wins over a still-recorded failure: the user asked for this list to stop
     * synchronizing, so the row must read `Sync off` and offer Resume instead of staying stuck on
     * `Needs attention` with Stop as its only action (#1551). The failure history is left in place,
     * so it is still there if the list is resumed and fails again.
     */
    fun state(syncing: Boolean = false): NextcloudListState = when {
        syncing -> NextcloudListState.SYNCING
        !remoteAvailable -> NextcloudListState.UNAVAILABLE
        unsyncedChanges -> NextcloudListState.UNSYNCED_CHANGES
        !syncEnabled -> NextcloudListState.SYNC_OFF
        lastFailureCode != null -> NextcloudListState.NEEDS_ATTENTION
        else -> NextcloudListState.UP_TO_DATE
    }
}

/**
 * One bound collection as its consumers see it: the local collection identity, the remote resource
 * it owns and the state to render. The remote href is exposed so a screen can tell a bound remote
 * collection from one that exists only in Nextcloud without repeating the binding query.
 */
data class NextcloudListBinding(
    val collectionId: String,
    val remoteHref: String,
    val state: NextcloudListState,
    val remoteWritable: Boolean = true,
    val remoteAvailable: Boolean = true,
    val unsyncedChanges: Boolean = false,
)
