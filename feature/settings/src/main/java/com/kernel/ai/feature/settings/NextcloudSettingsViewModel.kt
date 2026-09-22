package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.nextcloud.NextcloudAccount
import com.kernel.ai.core.memory.nextcloud.NextcloudAccountCredentials
import com.kernel.ai.core.memory.nextcloud.NextcloudAddress
import com.kernel.ai.core.memory.nextcloud.NextcloudAddressResult
import com.kernel.ai.core.memory.nextcloud.NextcloudCalendarCollection
import com.kernel.ai.core.memory.nextcloud.NextcloudFailure
import com.kernel.ai.core.memory.nextcloud.NextcloudListBinding
import com.kernel.ai.core.memory.nextcloud.NextcloudListState
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Client-side state filters for the Nextcloud lists screen (#1551). */
enum class NextcloudListFilter(val label: String) {
    ALL("All"),
    CONNECTED("Connected"),
    NEXTCLOUD_ONLY("Nextcloud only"),
    JANDAL_ONLY("Jandal only"),
    NEEDS_ATTENTION("Needs attention"),
}

/** The three mutually exclusive sections of the Nextcloud lists screen. */
enum class NextcloudListSection(val title: String) {
    CONNECTED("Connected lists"),
    NEXTCLOUD_ONLY("Nextcloud only"),
    JANDAL_ONLY("Jandal only"),
}

/** One row of the Nextcloud lists screen; a list appears in exactly one section. */
data class NextcloudListItem(
    val displayName: String,
    val section: NextcloudListSection,
    /** Local list row id when this list exists in Jandal. */
    val listId: Long? = null,
    /** Local collection id when this list is bound to Nextcloud. */
    val collectionId: String? = null,
    /** Remote collection href when this row came from Nextcloud discovery. */
    val remoteHref: String? = null,
    /** Durable sync state, present only for [NextcloudListSection.CONNECTED]. */
    val syncState: NextcloudListState? = null,
) {
    /** Stable identity for list keys and tests. */
    val key: String
        get() = when {
            collectionId != null -> "collection:$collectionId"
            listId != null -> "list:$listId"
            remoteHref != null -> "remote:$remoteHref"
            else -> "remote:$displayName"
        }
}

/** Rows grouped into the three sections; sections with no visible rows are not rendered. */
data class NextcloudListSections(
    val connected: List<NextcloudListItem> = emptyList(),
    val nextcloudOnly: List<NextcloudListItem> = emptyList(),
    val jandalOnly: List<NextcloudListItem> = emptyList(),
) {
    val isEmpty: Boolean get() = connected.isEmpty() && nextcloudOnly.isEmpty() && jandalOnly.isEmpty()

    companion object {
        val EMPTY = NextcloudListSections()
    }
}

data class NextcloudSettingsState(
    val address: String = "",
    val username: String = "",
    val appPassword: String = "",
    val allowInsecureHttp: Boolean = false,
    /** Inline address validation message shown next to the address field. */
    val addressError: String? = null,
    /** Non-null once an account is stored; the screen then shows a compact summary. */
    val connected: NextcloudAccount? = null,
    /** True while the user is entering or replacing credentials. */
    val editingAccount: Boolean = false,
    /** True when a stored account can no longer authenticate. */
    val authenticationFailed: Boolean = false,
    val collections: List<NextcloudCalendarCollection> = emptyList(),
    /** True once discovery has produced a remote collection list for this session. */
    val discovered: Boolean = false,
    val query: String = "",
    val filter: NextcloudListFilter = NextcloudListFilter.ALL,
    /** Local list waiting for a contextual first-time Nextcloud connection. */
    val pendingListId: Long? = null,
    val pendingListName: String? = null,
    /** One-shot signal that the contextual setup finished and its origin should be shown again. */
    val pendingSetupCompleted: Boolean = false,
    val busy: Boolean = false,
    /** Collection currently being synchronized, for per-row progress. */
    val busyCollectionId: String? = null,
    /** Concise transient feedback for the screen's snackbar. */
    val feedback: String? = null,
) {
    /** Credential entry is limited to initial connection or explicit edit/reconnect. */
    val showCredentialForm: Boolean get() = connected == null || editingAccount
}

/**
 * Drives the Nextcloud lists screen.
 *
 * Credentials are held only long enough to complete discovery and are never read back out of the
 * account store into UI state, so a saved app password cannot be redisplayed. The screen is
 * client-side over already discovered remote collections and Room-backed local/binding state, and
 * every per-list Nextcloud action routes through [NextcloudSyncAdapter].
 */
@HiltViewModel
class NextcloudSettingsViewModel @Inject constructor(
    private val adapter: NextcloudSyncAdapter,
    listNameDao: ListNameDao,
) : ViewModel() {
    private val lists = listNameDao.observeActiveLists()
    private val bindings = adapter.observeListBindings()

    private val _state = MutableStateFlow(NextcloudSettingsState(connected = adapter.account()))
    val state: StateFlow<NextcloudSettingsState> = _state.asStateFlow()

    /** Sections after search and state filtering, with a bound list never repeated. */
    val sections: StateFlow<NextcloudListSections> = combine(
        lists,
        bindings,
        _state,
    ) { lists, bindings, state -> buildSections(lists, bindings, state) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NextcloudListSections.EMPTY)

    init {
        // A stored account needs its remote collections before the sections mean anything.
        if (adapter.account() != null) refresh()
    }

    // ── Address entry ────────────────────────────────────────────────────────────────────────────

    fun setAddress(value: String) {
        // Surface the security-relevant rejection as soon as `http://` is typed; incomplete hosts
        // are only reported when the user submits the form.
        val rejection = NextcloudAddress.normalize(value, _state.value.allowInsecureHttp)
        _state.value = _state.value.copy(
            address = value,
            addressError = insecureSchemeMessage(rejection),
            feedback = null,
        )
    }

    fun setUsername(value: String) {
        _state.value = _state.value.copy(username = value, feedback = null)
    }

    fun setAppPassword(value: String) {
        _state.value = _state.value.copy(appPassword = value, feedback = null)
    }

    fun setAllowInsecureHttp(value: Boolean) {
        val rejection = NextcloudAddress.normalize(_state.value.address, value)
        _state.value = _state.value.copy(
            allowInsecureHttp = value,
            addressError = insecureSchemeMessage(rejection),
        )
    }

    // ── Connection lifecycle ─────────────────────────────────────────────────────────────────────

    fun connect() {
        val current = _state.value
        val normalized = NextcloudAddress.normalize(current.address, current.allowInsecureHttp)
        if (normalized is NextcloudAddressResult.Rejected) {
            _state.value = current.copy(addressError = normalized.message)
            return
        }
        val serverUrl = (normalized as NextcloudAddressResult.Accepted).serverUrl
        val username = current.username.trim()
        val stored = adapter.account()
        val canUseStoredCredentials = current.appPassword.isBlank() &&
            stored?.serverUrl == serverUrl &&
            stored.username == username
        if (username.isBlank() || (current.appPassword.isBlank() && !canUseStoredCredentials)) {
            _state.value = current.copy(
                addressError = null,
                feedback = "Enter the username and app password for this Nextcloud account.",
            )
            return
        }
        val transient = if (canUseStoredCredentials) {
            null
        } else {
            NextcloudAccountCredentials(
                account = NextcloudAccount(serverUrl, username, current.allowInsecureHttp),
                appPassword = current.appPassword,
            )
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, feedback = null, addressError = null)
            val discovery = if (transient == null) {
                adapter.discoverCollections()
            } else {
                adapter.discoverCollections(transient)
            }
            discovery.fold(
                onSuccess = { collections ->
                    // Credentials are persisted only after discovery succeeds.
                    transient?.let {
                        adapter.saveAccount(
                            it.account.serverUrl,
                            it.account.username,
                            it.appPassword,
                            it.account.allowInsecureHttp,
                        )
                    }
                    _state.value = _state.value.copy(
                        collections = collections,
                        discovered = true,
                        appPassword = "",
                        address = serverUrl,
                        username = username,
                        connected = adapter.account(),
                        editingAccount = false,
                        authenticationFailed = false,
                        busy = false,
                    )
                    continuePendingSetup()
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        busy = false,
                        authenticationFailed = (error as? NextcloudFailure)?.code ==
                            NextcloudFailure.Code.AUTHENTICATION,
                        feedback = safeMessage(error),
                    )
                },
            )
        }
    }

    /** Abandons an in-progress credential edit; a stored account stays untouched. */
    fun cancelEditing() {
        _state.value = _state.value.copy(
            editingAccount = false,
            appPassword = "",
            addressError = null,
            address = _state.value.connected?.serverUrl.orEmpty(),
            username = _state.value.connected?.username.orEmpty(),
        )
    }

    /** Opens credential entry for the stored account without reading the app password back. */
    fun editAccount() {
        _state.value = _state.value.copy(
            editingAccount = true,
            appPassword = "",
            addressError = null,
            address = _state.value.connected?.serverUrl.orEmpty(),
            username = _state.value.connected?.username.orEmpty(),
            authenticationFailed = false,
        )
    }

    fun disconnect() {
        adapter.clearAccount()
        _state.value = NextcloudSettingsState(feedback = "Nextcloud account disconnected.")
    }

    // ── Screen state ─────────────────────────────────────────────────────────────────────────────

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun setFilter(value: NextcloudListFilter) {
        _state.value = _state.value.copy(filter = value)
    }

    /**
     * Records the list that asked for a Nextcloud connection, so a first-time setup continues that
     * exact binding instead of making the user find the list again (#1551).
     */
    fun setPendingList(listId: Long?, name: String?) {
        _state.value = _state.value.copy(pendingListId = listId, pendingListName = name)
    }

    fun consumeFeedback() {
        if (_state.value.feedback != null) _state.value = _state.value.copy(feedback = null)
    }

    fun consumePendingSetupCompleted() {
        if (_state.value.pendingSetupCompleted) {
            _state.value = _state.value.copy(pendingSetupCompleted = false)
        }
    }

    /** Reconciliation and recovery for immediate needs; automatic sync stays the normal model. */
    fun refresh() {
        if (adapter.account() == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, feedback = null)
            val discovery = adapter.discoverCollections()
            val sync = adapter.syncAll()
            val discoveryFailure = failureOf(discovery)
            val syncFailure = (sync as? NextcloudSyncResult.Failure)?.error
            _state.value = _state.value.copy(
                collections = discovery.getOrElse { _state.value.collections },
                discovered = _state.value.discovered || discovery.isSuccess,
                busy = false,
                authenticationFailed = discoveryFailure?.code == NextcloudFailure.Code.AUTHENTICATION ||
                    syncFailure?.code == NextcloudFailure.Code.AUTHENTICATION,
                feedback = discoveryFailure?.message ?: syncFailure?.message,
            )
        }
    }

    // ── Per-list actions ─────────────────────────────────────────────────────────────────────────

    /** Binds a Nextcloud-only collection into Jandal. */
    fun addToJandal(item: NextcloudListItem) {
        val collection = _state.value.collections.firstOrNull { it.href == item.remoteHref }
        if (collection == null) {
            _state.value = _state.value.copy(feedback = "That Nextcloud list is no longer available.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, feedback = null)
            adapter.importCollection(collection).fold(
                onSuccess = {
                    _state.value = _state.value.copy(busy = false, feedback = "List added to Jandal")
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(busy = false, feedback = safeMessage(error))
                },
            )
        }
    }

    /**
     * Binds a Jandal-only list, creating its Nextcloud collection on first use. Without a configured
     * account this only records the pending list; the caller routes to the connection flow.
     */
    fun syncWithNextcloud(item: NextcloudListItem) {
        val listId = item.listId ?: return
        if (adapter.account() == null) {
            setPendingList(listId, item.displayName)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                busyCollectionId = item.collectionId,
                feedback = null,
            )
            adapter.publishCollection(listId).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        busy = false,
                        busyCollectionId = null,
                        feedback = "List synced with Nextcloud",
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        busy = false,
                        busyCollectionId = null,
                        feedback = safeMessage(error),
                    )
                },
            )
        }
    }

    /** Stops synchronization for one list; both the local list and the remote copy are kept. */
    fun stopSync(item: NextcloudListItem) {
        val collectionId = item.collectionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyCollectionId = collectionId, feedback = null)
            val stopped = adapter.stopSync(collectionId)
            _state.value = _state.value.copy(
                busy = false,
                busyCollectionId = null,
                feedback = if (stopped) "Nextcloud sync stopped for ${item.displayName}" else null,
            )
        }
    }

    /** Resumes synchronization through the retained association and reconciles once. */
    fun resumeSync(item: NextcloudListItem) {
        val collectionId = item.collectionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyCollectionId = collectionId, feedback = null)
            reportSyncResult(adapter.resumeSync(collectionId))
        }
    }

    /** Retries a bound list whose last synchronization failed. */
    fun retry(item: NextcloudListItem) {
        val collectionId = item.collectionId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, busyCollectionId = collectionId, feedback = null)
            reportSyncResult(adapter.syncCollection(collectionId))
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────────────────────────

    private fun reportSyncResult(result: NextcloudSyncResult) {
        _state.value = when (result) {
            is NextcloudSyncResult.Success -> _state.value.copy(
                busy = false,
                busyCollectionId = null,
                feedback = "List synced with Nextcloud",
            )
            is NextcloudSyncResult.Failure -> _state.value.copy(
                busy = false,
                busyCollectionId = null,
                authenticationFailed = result.error.code == NextcloudFailure.Code.AUTHENTICATION,
                feedback = result.error.message,
            )
        }
    }

    /** Runs the binding that prompted a first-time connection; never leaves a partial binding. */
    private suspend fun continuePendingSetup() {
        val pendingListId = _state.value.pendingListId ?: return
        adapter.publishCollection(pendingListId).fold(
            onSuccess = {
                _state.value = _state.value.copy(
                    pendingListId = null,
                    pendingListName = null,
                    pendingSetupCompleted = true,
                    feedback = "List synced with Nextcloud",
                )
            },
            onFailure = { error ->
                // The account stays connected; the list remains local-only with no partial binding.
                _state.value = _state.value.copy(
                    pendingListId = null,
                    pendingListName = null,
                    feedback = safeMessage(error),
                )
            },
        )
    }

    private fun buildSections(
        lists: List<ListNameEntity>,
        bindings: List<NextcloudListBinding>,
        state: NextcloudSettingsState,
    ): NextcloudListSections =
        nextcloudSections(lists, bindings, state.collections).filtered(state.query, state.filter)

    private fun insecureSchemeMessage(result: NextcloudAddressResult): String? =
        (result as? NextcloudAddressResult.Rejected)
            ?.takeIf { it.code == NextcloudAddressResult.Rejected.Code.INSECURE_SCHEME }
            ?.message

    private fun failureOf(result: Result<*>): NextcloudFailure? =
        result.exceptionOrNull() as? NextcloudFailure

    private fun safeMessage(error: Throwable): String = when (error) {
        is NextcloudFailure -> error.message
        else -> "Nextcloud operation failed. Check the server and try again."
    }
}

/**
 * Groups local lists and discovered remote collections into the three mutually exclusive sections.
 *
 * A local list is connected when its collection id has a binding; a remote collection is
 * Nextcloud-only when no binding owns its href, so a bound list can never be offered again as an
 * import candidate.
 */
internal fun nextcloudSections(
    lists: List<ListNameEntity>,
    bindings: List<NextcloudListBinding>,
    collections: List<NextcloudCalendarCollection>,
): NextcloudListSections {
    val stateByCollectionId = bindings.associate { it.collectionId to it.state }
    val boundHrefs = bindings.mapTo(HashSet()) { it.remoteHref }
    val connected = ArrayList<NextcloudListItem>()
    val jandalOnly = ArrayList<NextcloudListItem>()
    lists.forEach { list ->
        val syncState = stateByCollectionId[list.collectionId]
        val row = NextcloudListItem(
            displayName = list.name,
            section = if (syncState == null) {
                NextcloudListSection.JANDAL_ONLY
            } else {
                NextcloudListSection.CONNECTED
            },
            listId = list.id,
            collectionId = list.collectionId.takeIf { syncState != null },
            syncState = syncState,
        )
        if (syncState == null) jandalOnly += row else connected += row
    }
    val nextcloudOnly = collections
        .filter { it.href !in boundHrefs }
        .map { collection ->
            NextcloudListItem(
                displayName = collection.displayName,
                section = NextcloudListSection.NEXTCLOUD_ONLY,
                remoteHref = collection.href,
            )
        }
    return NextcloudListSections(
        connected = connected,
        nextcloudOnly = nextcloudOnly,
        jandalOnly = jandalOnly,
    )
}

/**
 * Client-side search and state filtering. Search matches the displayed list name across all
 * sections and composes with the state filter; sections left empty are simply not rendered.
 */
internal fun NextcloudListSections.filtered(
    query: String,
    filter: NextcloudListFilter,
): NextcloudListSections {
    val trimmed = query.trim()
    fun keep(row: NextcloudListItem): Boolean {
        if (trimmed.isNotEmpty() && !row.displayName.contains(trimmed, ignoreCase = true)) return false
        return when (filter) {
            NextcloudListFilter.ALL -> true
            NextcloudListFilter.CONNECTED -> row.section == NextcloudListSection.CONNECTED
            NextcloudListFilter.NEXTCLOUD_ONLY -> row.section == NextcloudListSection.NEXTCLOUD_ONLY
            NextcloudListFilter.JANDAL_ONLY -> row.section == NextcloudListSection.JANDAL_ONLY
            NextcloudListFilter.NEEDS_ATTENTION -> row.syncState == NextcloudListState.NEEDS_ATTENTION
        }
    }
    return NextcloudListSections(
        connected = connected.filter(::keep),
        nextcloudOnly = nextcloudOnly.filter(::keep),
        jandalOnly = jandalOnly.filter(::keep),
    )
}
