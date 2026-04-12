package com.kernel.ai.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ConversationRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val conversations: StateFlow<List<ConversationEntity>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) repository.observeConversations()
            else repository.searchByTitle(query.escapeLikeWildcards())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }

    fun clearSearch() { _searchQuery.value = "" }

    private fun String.escapeLikeWildcards(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun deleteConversation(conversation: ConversationEntity) {
        viewModelScope.launch {
            repository.deleteConversation(conversation)
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
}

