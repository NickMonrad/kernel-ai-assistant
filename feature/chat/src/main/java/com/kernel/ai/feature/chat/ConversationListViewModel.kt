package com.kernel.ai.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.inference.setup.SetupWizardPreferences
import com.kernel.ai.core.inference.setup.SetupWizardProgress
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.model.availability.ConversationModelReadiness
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ConversationRepository,
    private val chatPreferences: ChatPreferences,
    private val downloadManager: ModelDownloadManager,
    private val downloadNetworkPreferences: DownloadNetworkPreferences,
    private val setupWizardPreferences: SetupWizardPreferences,
) : ViewModel() {

    /**
     * Null until both the welcome preference and the total conversation count (archived included) have loaded.
     * True when the welcome is hidden or any conversation exists, so a returning user never sees the welcome flash.
     */
    val chatsWelcomeDismissed: StateFlow<Boolean?> = combine(
        chatPreferences.chatsWelcomeDismissed,
        repository.observeConversationCount(),
    ) { dismissed, count -> dismissed || count > 0 }
        .stateIn<Boolean?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloadStates = downloadManager.downloadStates

    val tabHintsDismissed: StateFlow<Boolean?> = chatPreferences.tabHintsDismissed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while the first-run setup wizard is not finished or skipped. Null until loaded (#1855). */
    val setupWizardPending: StateFlow<Boolean?> = setupWizardPreferences.progress
        .map<SetupWizardProgress, Boolean?> { !it.completed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val waitingForWifi: StateFlow<Set<KernelModel>> = downloadManager.waitingForWifi

    val assistantReadiness: StateFlow<ConversationModelReadiness> = downloadManager.downloadStates
        .map { states -> ConversationModelReadiness.compute(downloadManager.deviceTier, states) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ConversationModelReadiness.compute(downloadManager.deviceTier, downloadManager.downloadStates.value),
        )

    init {
        // The welcome is one-time: once any conversation exists (archived included), it never returns.
        // First-run decision for the setup wizard (#1855). Existing installs are marked complete.
        viewModelScope.launch {
            val existingInstall = downloadNetworkPreferences.choice.first() != null ||
                downloadManager.hasExistingDownloads()
            setupWizardPreferences.decideFirstRun(existingInstall)
        }
        viewModelScope.launch {
            repository.observeConversationCount().first { it > 0 }
            chatPreferences.setChatsWelcomeDismissed(true)
        }
    }

    /** Starts (or retries) the download of [model] from the welcome, same path as the chat recovery card. */
    fun downloadAssistantModel(model: KernelModel) {
        downloadManager.startDownload(model, force = false)
    }

    /** Chooses mobile data for downloads waiting on Wi-Fi, from the welcome (#1855). */
    fun useMobileData() {
        downloadManager.setDownloadNetworkChoice(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showArchived = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    val conversations: StateFlow<List<ConversationEntity>> =
        combine(_searchQuery, _showArchived) { q, archived -> q to archived }
            .flatMapLatest { (query, archived) ->
                if (archived) {
                    if (query.isBlank()) repository.observeArchived()
                    else repository.searchArchived(query.escapeLikeWildcards())
                } else {
                    if (query.isBlank()) repository.observeActive()
                    else repository.searchActive(query.escapeLikeWildcards())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pinnedConversations: StateFlow<List<ConversationEntity>> = conversations
        .map { it.filter { c -> c.pinned } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unpinnedConversations: StateFlow<List<ConversationEntity>> = conversations
        .map { it.filter { c -> !c.pinned } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }

    fun clearSearch() { _searchQuery.value = "" }

    fun toggleShowArchived() {
        _showArchived.value = !_showArchived.value
        // Reset search and selection when toggling views
        _searchQuery.value = ""
        clearSelection()
    }

    private fun String.escapeLikeWildcards(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun hideWelcome() {
        viewModelScope.launch { chatPreferences.setChatsWelcomeDismissed(true) }
    }

    fun dismissTabHints() {
        viewModelScope.launch { chatPreferences.setTabHintsDismissed(true) }
    }

    fun deleteConversation(conversation: ConversationEntity) {
        viewModelScope.launch {
            repository.deleteConversation(conversation)
        }
    }

    fun archiveConversation(id: String) {
        viewModelScope.launch {
            repository.archiveConversation(id)
        }
    }

    fun restoreConversation(id: String) {
        viewModelScope.launch {
            repository.restoreConversation(id)
        }
    }

    fun togglePin(id: String) {
        viewModelScope.launch {
            repository.togglePin(id)
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            repository.renameConversation(id, title)
        }
    }

    // ── #178 Bulk selection ────────────────────────────────────────────────

    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode: StateFlow<Boolean> = _isInSelectionMode.asStateFlow()

    private val _selectedConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedConversationIds: StateFlow<Set<String>> = _selectedConversationIds.asStateFlow()

    private val _showBulkDeleteConfirmation = MutableStateFlow(false)
    val showBulkDeleteConfirmation: StateFlow<Boolean> = _showBulkDeleteConfirmation.asStateFlow()

    fun enterSelectionMode(id: String) {
        _isInSelectionMode.value = true
        _selectedConversationIds.update { it + id }
    }

    fun toggleSelection(id: String) {
        _selectedConversationIds.update { current ->
            if (id in current) current - id else current + id
        }
        if (_selectedConversationIds.value.isEmpty()) {
            _isInSelectionMode.value = false
        }
    }

    fun selectAll(allIds: List<String>) {
        _selectedConversationIds.value = allIds.toSet()
    }

    fun clearSelection() {
        _isInSelectionMode.value = false
        _selectedConversationIds.value = emptySet()
        _showBulkDeleteConfirmation.value = false
    }

    fun requestBulkDelete() {
        if (_selectedConversationIds.value.isNotEmpty()) {
            _showBulkDeleteConfirmation.value = true
        }
    }

    fun dismissBulkDeleteConfirmation() {
        _showBulkDeleteConfirmation.value = false
    }

    fun deleteSelected() {
        val ids = _selectedConversationIds.value.toSet()
        val entities = conversations.value.filter { it.id in ids }
        viewModelScope.launch {
            try {
                entities.forEach { entity -> repository.deleteConversation(entity) }
            } finally {
                _showBulkDeleteConfirmation.value = false
                _isInSelectionMode.value = false
                _selectedConversationIds.value = emptySet()
            }
        }
    }

    fun archiveSelected() {
        val ids = _selectedConversationIds.value.toSet()
        viewModelScope.launch {
            try {
                repository.archiveSelected(ids)
            } finally {
                _isInSelectionMode.value = false
                _selectedConversationIds.value = emptySet()
            }
        }
    }

    fun restoreSelected() {
        val ids = _selectedConversationIds.value.toSet()
        viewModelScope.launch {
            try {
                repository.restoreSelected(ids)
            } finally {
                _isInSelectionMode.value = false
                _selectedConversationIds.value = emptySet()
            }
        }
    }

    fun onConversationsReordered(pinnedIds: List<String>, unpinnedIds: List<String>) {
        viewModelScope.launch {
            repository.reorderConversations(pinnedIds, unpinnedIds)
        }
    }
}
