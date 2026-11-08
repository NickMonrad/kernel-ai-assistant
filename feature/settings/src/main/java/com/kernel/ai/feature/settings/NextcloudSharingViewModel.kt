package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.nextcloud.NextcloudFailure
import com.kernel.ai.core.memory.nextcloud.NextcloudShare
import com.kernel.ai.core.memory.nextcloud.NextcloudSharePermission
import com.kernel.ai.core.memory.nextcloud.NextcloudSharee
import com.kernel.ai.core.memory.nextcloud.NextcloudShareeType
import com.kernel.ai.core.memory.nextcloud.NextcloudSharingOperations
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State for sharing one already-bound Nextcloud task collection. */
data class NextcloudSharingState(
    val loading: Boolean = true,
    val shares: List<NextcloudShare> = emptyList(),
    val writable: Boolean = false,
    val query: String = "",
    val results: List<NextcloudSharee> = emptyList(),
    val selectedSharee: NextcloudSharee? = null,
    val selectedPermission: NextcloudSharePermission = NextcloudSharePermission.READ_ONLY,
    val searching: Boolean = false,
    val busyPrincipal: String? = null,
    val feedback: String? = null,
    val error: String? = null,
)

@HiltViewModel
class NextcloudSharingViewModel @Inject constructor(
    private val adapter: NextcloudSharingOperations,
) : ViewModel() {
    private val _state = MutableStateFlow(NextcloudSharingState())
    val state: StateFlow<NextcloudSharingState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var collectionId: String? = null

    fun start(collectionId: String) {
        if (this.collectionId == collectionId && !_state.value.loading) return
        this.collectionId = collectionId
        loadShares()
    }

    fun refresh() {
        if (collectionId != null) loadShares()
    }

    fun setQuery(query: String) {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            query = query,
            results = emptyList(),
            selectedSharee = null,
            selectedPermission = NextcloudSharePermission.READ_ONLY,
            feedback = null,
            error = null,
        )
        if (query.trim().isEmpty()) return
        searchJob = viewModelScope.launch {
            delay(250)
            _state.value = _state.value.copy(searching = true)
            val result = adapter.searchSharees(requireCollectionId(), query)
            result.fold(
                onSuccess = { sharees ->
                    _state.value = _state.value.copy(searching = false, results = sharees, error = null)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(searching = false, results = emptyList(), error = message(error))
                },
            )
        }
    }

    fun selectSharee(sharee: NextcloudSharee) {
        if (_state.value.busyPrincipal != null) return
        _state.value = _state.value.copy(
            selectedSharee = sharee,
            selectedPermission = NextcloudSharePermission.READ_ONLY,
        )
    }

    fun selectPermission(permission: NextcloudSharePermission) {
        if (_state.value.selectedSharee == null || _state.value.busyPrincipal != null) return
        _state.value = _state.value.copy(selectedPermission = permission)
    }

    fun shareSelected() {
        val selected = _state.value.selectedSharee ?: return
        val permission = _state.value.selectedPermission
        mutate(selected.principal, clearSelection = true) {
            adapter.setShare(requireCollectionId(), selected.principal, permission)
        }
    }

    fun changePermission(share: NextcloudShare, permission: NextcloudSharePermission) {
        if (share.permission == permission) return
        mutate(share.principal) {
            adapter.setShare(requireCollectionId(), share.principal, permission)
        }
    }

    fun remove(share: NextcloudShare) {
        mutate(share.principal) {
            adapter.removeShare(requireCollectionId(), share.principal)
        }
    }

    fun consumeFeedback() {
        _state.value = _state.value.copy(feedback = null)
    }

    private fun loadShares(preserveFeedback: Boolean = false) {
        val feedback = _state.value.feedback
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            feedback = if (preserveFeedback) feedback else null,
        )
        viewModelScope.launch {
            val result = adapter.listShares(requireCollectionId())
            result.fold(
                onSuccess = { listing ->
                    _state.value = _state.value.copy(
                        loading = false,
                        shares = listing.shares,
                        writable = listing.writable,
                        error = null,
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(loading = false, error = message(error))
                },
            )
        }
    }

    private fun mutate(
        principal: String,
        clearSelection: Boolean = false,
        operation: suspend () -> Result<Unit>,
    ) {
        if (_state.value.busyPrincipal != null || !_state.value.writable) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyPrincipal = principal, error = null, feedback = null)
            operation().fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        busyPrincipal = null,
                        selectedSharee = if (clearSelection) null else _state.value.selectedSharee,
                        feedback = "Sharing updated.",
                    )
                    loadShares(preserveFeedback = true)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(busyPrincipal = null, error = message(error))
                },
            )
        }
    }

    private fun requireCollectionId(): String = requireNotNull(collectionId) { "No Nextcloud list selected" }

    private fun message(error: Throwable): String = when (error) {
        is NextcloudFailure -> error.message
        else -> "Nextcloud sharing could not be updated. Try again when the server is reachable."
    }
}

internal fun NextcloudSharee.typeLabel(): String = when (type) {
    NextcloudShareeType.USER -> "User"
    NextcloudShareeType.GROUP -> "Group"
}

internal fun NextcloudShare.permissionLabel(): String = when (permission) {
    NextcloudSharePermission.READ_ONLY -> "Read-only"
    NextcloudSharePermission.EDITABLE -> "Editable"
}
