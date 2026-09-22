package com.kernel.ai.core.memory.nextcloud

/**
 * Durable, user-visible synchronization state for one local list (#1551).
 *
 * [SYNCING] is a transient overlay applied while a synchronization for that list is in flight;
 * the other three values are derived from persisted binding metadata only.
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
}

/** Room projection of the per-list binding columns needed to render a list's Nextcloud state. */
data class NextcloudListSyncSummary(
    val collectionId: String,
    val remoteHref: String,
    val syncEnabled: Boolean,
    val lastFailureCode: String?,
) {
    /**
     * Durable state, before the in-flight overlay.
     *
     * An explicit Stop wins over a still-recorded failure: the user asked for this list to stop
     * synchronizing, so the row must read `Sync off` and offer Resume instead of staying stuck on
     * `Needs attention` with Stop as its only action (#1551). The failure history is left in place,
     * so it is still there if the list is resumed and fails again.
     */
    fun state(syncing: Boolean = false): NextcloudListState = when {
        syncing -> NextcloudListState.SYNCING
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
)
