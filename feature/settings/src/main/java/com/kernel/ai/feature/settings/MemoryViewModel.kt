package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val embeddingDao: MessageEmbeddingDao,
) : ViewModel() {

    data class MessageEmbeddingStats(
        val messageCount: Int = 0,
        val conversationCount: Int = 0,
    )

    data class MemoryUiState(
        val coreMemories: List<CoreMemoryEntity> = emptyList(),
        val episodicCount: Int = 0,
        val episodicMemories: List<EpisodicMemoryEntity> = emptyList(),
        val conversationTitles: Map<String, String?> = emptyMap(),
        val isAddDialogOpen: Boolean = false,
        val addDialogText: String = "",
        val showClearConfirmation: Boolean = false,
        val isSubmitting: Boolean = false,
        val pendingDeleteId: String? = null,
        val pendingDeleteEpisodicId: String? = null,
        // #110 — bulk selection
        val isInSelectionMode: Boolean = false,
        val selectedCoreIds: Set<String> = emptySet(),
        val showBulkDeleteConfirmation: Boolean = false,
        // #164 — embedding stats
        val embeddingStats: MessageEmbeddingStats = MessageEmbeddingStats(),
    )

    private val _dialogState = MutableStateFlow(
        Triple(/* isAddDialogOpen */ false, /* addDialogText */ "", /* showClearConfirmation */ false)
    )
    private val _isSubmitting = MutableStateFlow(false)
    private val _pendingDeleteId = MutableStateFlow<String?>(null)
    private val _pendingDeleteEpisodicId = MutableStateFlow<String?>(null)

    // #110 — selection mode state
    private val _isInSelectionMode = MutableStateFlow(false)
    private val _selectedCoreIds = MutableStateFlow<Set<String>>(emptySet())
    private val _showBulkDeleteConfirmation = MutableStateFlow(false)

    // #164 — embedding stats
    private val _embeddingStats = MutableStateFlow(MessageEmbeddingStats())

    init {
        refreshEmbeddingStats()
    }

    private fun refreshEmbeddingStats() {
        viewModelScope.launch {
            val messageCount = embeddingDao.count()
            val conversationCount = embeddingDao.countDistinctConversations()
            _embeddingStats.value = MessageEmbeddingStats(messageCount, conversationCount)
        }
    }

    private val conversationTitlesFlow = conversationRepository.observeConversations()
        .map { list: List<ConversationEntity> -> list.associate { it.id to it.title } }

    val uiState: StateFlow<MemoryUiState> = combine(
        combine(
            combine(
                memoryRepository.observeCoreMemories(),
                memoryRepository.observeEpisodicMemories(),
                _dialogState,
                _isSubmitting,
            ) { coreMemories, episodicMemories, (isAddDialogOpen, addDialogText, showClearConfirmation), isSubmitting ->
                MemoryUiState(
                    coreMemories = coreMemories,
                    episodicCount = episodicMemories.size,
                    episodicMemories = episodicMemories,
                    isAddDialogOpen = isAddDialogOpen,
                    addDialogText = addDialogText,
                    showClearConfirmation = showClearConfirmation,
                    isSubmitting = isSubmitting,
                )
            },
            _pendingDeleteId,
            _pendingDeleteEpisodicId,
        ) { base, pendingDeleteId, pendingDeleteEpisodicId ->
            base.copy(
                pendingDeleteId = pendingDeleteId,
                pendingDeleteEpisodicId = pendingDeleteEpisodicId,
            )
        },
        conversationTitlesFlow,
    ) { base, conversationTitles ->
        base.copy(conversationTitles = conversationTitles)
    }.combine(
        combine(_isInSelectionMode, _selectedCoreIds, _showBulkDeleteConfirmation, _embeddingStats)
        { inSelection, selectedIds, showBulkConfirm, stats ->
            Triple(inSelection to selectedIds, showBulkConfirm, stats)
        }
    ) { base, (selectionPair, showBulkConfirm, stats) ->
        val (inSelection, selectedIds) = selectionPair
        base.copy(
            isInSelectionMode = inSelection,
            selectedCoreIds = selectedIds,
            showBulkDeleteConfirmation = showBulkConfirm,
            embeddingStats = stats,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MemoryUiState(),
    )

    fun openAddDialog() {
        _dialogState.update { it.copy(first = true) }
    }

    fun dismissAddDialog() {
        _dialogState.update { it.copy(first = false, second = "") }
    }

    fun onAddDialogTextChange(text: String) {
        _dialogState.update { it.copy(second = text) }
    }

    fun addCoreMemory() {
        if (!_isSubmitting.compareAndSet(expect = false, update = true)) return
        val text = _dialogState.value.second.trim()
        if (text.isBlank()) {
            _isSubmitting.value = false
            return
        }
        viewModelScope.launch {
            try {
                memoryRepository.addCoreMemory(content = text, source = "user")
                dismissAddDialog()
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun requestDeleteCoreMemory(id: String) {
        _pendingDeleteId.value = id
    }

    fun dismissDeleteConfirmation() {
        _pendingDeleteId.value = null
    }

    fun deleteCoreMemory(id: String) {
        _pendingDeleteId.value = null
        viewModelScope.launch { memoryRepository.deleteCoreMemory(id) }
    }

    fun requestDeleteEpisodicMemory(id: String) {
        _pendingDeleteEpisodicId.value = id
    }

    fun dismissDeleteEpisodicConfirmation() {
        _pendingDeleteEpisodicId.value = null
    }

    fun deleteEpisodicMemory(id: String) {
        viewModelScope.launch {
            try {
                memoryRepository.deleteEpisodicMemory(id)
            } finally {
                _pendingDeleteEpisodicId.value = null
            }
        }
    }

    suspend fun getConversationTitle(conversationId: String): String? =
        conversationRepository.getConversation(conversationId)?.title

    fun showClearEpisodicConfirmation() {
        _dialogState.update { it.copy(third = true) }
    }

    fun dismissClearConfirmation() {
        _dialogState.update { it.copy(third = false) }
    }

    fun clearEpisodicMemories() {
        viewModelScope.launch {
            try {
                memoryRepository.clearEpisodicMemories()
            } finally {
                _dialogState.update { it.copy(third = false) }
            }
        }
    }

    // ── #110 Bulk selection ────────────────────────────────────────────────

    /** Long-press entry point: enter selection mode and select the tapped item. */
    fun enterSelectionMode(id: String) {
        _isInSelectionMode.value = true
        _selectedCoreIds.update { it + id }
    }

    /** Toggle an individual item's selected state. */
    fun toggleSelection(id: String) {
        _selectedCoreIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    /** Select every core memory currently in the list. */
    fun selectAll(allIds: List<String>) {
        _selectedCoreIds.value = allIds.toSet()
    }

    /** Exit selection mode without deleting anything. */
    fun clearSelection() {
        _isInSelectionMode.value = false
        _selectedCoreIds.value = emptySet()
        _showBulkDeleteConfirmation.value = false
    }

    /** Show the "Delete Selected (N)" confirmation dialog. */
    fun requestBulkDelete() {
        if (_selectedCoreIds.value.isNotEmpty()) {
            _showBulkDeleteConfirmation.value = true
        }
    }

    fun dismissBulkDeleteConfirmation() {
        _showBulkDeleteConfirmation.value = false
    }

    /** Delete all selected core memories, then exit selection mode. */
    fun deleteSelected() {
        val ids = _selectedCoreIds.value.toList()
        viewModelScope.launch {
            try {
                ids.forEach { id -> memoryRepository.deleteCoreMemory(id) }
            } finally {
                _showBulkDeleteConfirmation.value = false
                _isInSelectionMode.value = false
                _selectedCoreIds.value = emptySet()
            }
        }
    }
}
