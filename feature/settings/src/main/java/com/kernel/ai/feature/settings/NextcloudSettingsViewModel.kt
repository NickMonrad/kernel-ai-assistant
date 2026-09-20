package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.nextcloud.NextcloudCalendarCollection
import com.kernel.ai.core.memory.nextcloud.NextcloudFailure
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class NextcloudSettingsState(
    val serverUrl: String = "",
    val username: String = "",
    val appPassword: String = "",
    val hasSavedAccount: Boolean = false,
    val collections: List<NextcloudCalendarCollection> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class NextcloudSettingsViewModel @Inject constructor(
    private val adapter: NextcloudSyncAdapter,
    listNameDao: ListNameDao,
) : ViewModel() {
    private val _state = MutableStateFlow(
        NextcloudSettingsState(
            serverUrl = adapter.account()?.serverUrl.orEmpty(),
            username = adapter.account()?.username.orEmpty(),
            hasSavedAccount = adapter.account() != null,
        ),
    )
    val state: StateFlow<NextcloudSettingsState> = _state.asStateFlow()
    val lists: StateFlow<List<ListNameEntity>> = listNameDao.observeActiveLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setServerUrl(value: String) { _state.value = _state.value.copy(serverUrl = value, message = null) }
    fun setUsername(value: String) { _state.value = _state.value.copy(username = value, message = null) }
    fun setAppPassword(value: String) { _state.value = _state.value.copy(appPassword = value, message = null) }

    fun connect() {
        val current = _state.value
        val stored = adapter.account()
        val canUseStoredCredentials = current.appPassword.isBlank() &&
            stored?.serverUrl == current.serverUrl.trim().removeSuffix("/") &&
            stored.username == current.username.trim()
        if (current.serverUrl.isBlank() || current.username.isBlank() ||
            (current.appPassword.isBlank() && !canUseStoredCredentials)
        ) {
            _state.value = current.copy(message = "Enter the server URL, username, and app password.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            if (!canUseStoredCredentials) adapter.saveAccount(current.serverUrl, current.username, current.appPassword)
            adapter.discoverCollections().fold(
                onSuccess = { collections ->
                    _state.value = _state.value.copy(
                        collections = collections,
                        appPassword = "",
                        hasSavedAccount = true,
                        busy = false,
                        message = "Connected. Select a remote list to import, or publish a local list.",
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(busy = false, message = safeMessage(error))
                },
            )
        }
    }
    fun clearAccount() {
        adapter.clearAccount()
        _state.value = NextcloudSettingsState(message = "Saved Nextcloud account removed.")
    }

    fun importCollection(collection: NextcloudCalendarCollection) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            adapter.importCollection(collection).fold(
                onSuccess = { _state.value = _state.value.copy(busy = false, message = "Imported ${collection.displayName}.") },
                onFailure = { error -> _state.value = _state.value.copy(busy = false, message = safeMessage(error)) },
            )
        }
    }

    fun publishList(list: ListNameEntity) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            adapter.publishCollection(list.id).fold(
                onSuccess = { _state.value = _state.value.copy(busy = false, message = "Published ${list.name}.") },
                onFailure = { error -> _state.value = _state.value.copy(busy = false, message = safeMessage(error)) },
            )
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            when (val result = adapter.syncAll()) {
                is com.kernel.ai.core.memory.nextcloud.NextcloudSyncResult.Success ->
                    _state.value = _state.value.copy(busy = false, message = "Sync complete.")
                is com.kernel.ai.core.memory.nextcloud.NextcloudSyncResult.Failure ->
                    _state.value = _state.value.copy(busy = false, message = safeMessage(result.error))
            }
        }
    }

    private fun safeMessage(error: Throwable): String = when (error) {
        is NextcloudFailure -> error.message
        else -> "Nextcloud operation failed. Check the server and try again."
    }
}
