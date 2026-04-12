package com.kernel.ai.feature.settings

import android.util.Log
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

enum class MemorySection { CORE, EPISODIC, EMBEDDING_STATS }

private data class SearchExpandState(
    val expandedSections: Set<MemorySection>,
    val globalSearch: String,
    val coreSearch: String,
    val episodicSearch: String,
)

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
        // #110 — core bulk selection
        val isInSelectionMode: Boolean = false,
        val selectedCoreIds: Set<String> = emptySet(),
        val showBulkDeleteConfirmation: Boolean = false,
        // #164 — embedding stats
        val embeddingStats: MessageEmbeddingStats = MessageEmbeddingStats(),
        // #175 — collapsible sections + search
        val expandedSections: Set<MemorySection> = emptySet(),
        val globalSearch: String = "",
        val coreSearch: String = "",
        val episodicSearch: String = "",
        // #176 — episodic bulk selection
        val isInEpisodicSelectionMode: Boolean = false,
        val selectedEpisodicIds: Set<String> = emptySet(),
        val showEpisodicBulkDeleteConfirmation: Boolean = false,
        // #179 — detail views
        val selectedCoreMemoryDetail: CoreMemoryEntity? = null,
        val selectedEpisodicMemoryDetail: EpisodicMemoryEntity? = null,
    )

    private val _dialogState = MutableStateFlow(
        Triple(/* isAddDialogOpen */ false, /* addDialogText */ "", /* showClearConfirmation */ false)
    )
    private val _isSubmitting = MutableStateFlow(false)
    private val _pendingDeleteId = MutableStateFlow<String?>(null)
    private val _pendingDeleteEpisodicId = MutableStateFlow<String?>(null)

    // #110 — core selection mode state
    private val _isInSelectionMode = MutableStateFlow(false)
    private val _selectedCoreIds = MutableStateFlow<Set<String>>(emptySet())
    private val _showBulkDeleteConfirmation = MutableStateFlow(false)

    // #175 — collapsible sections + search
    private val _expandedSections = MutableStateFlow<Set<MemorySection>>(emptySet())
    private val _globalSearch = MutableStateFlow("")
    private val _coreSearch = MutableStateFlow("")
    private val _episodicSearch = MutableStateFlow("")

    // #176 — episodic selection mode state
    private val _isInEpisodicSelectionMode = MutableStateFlow(false)
    private val _selectedEpisodicIds = MutableStateFlow<Set<String>>(emptySet())
    private val _showEpisodicBulkDeleteConfirmation = MutableStateFlow(false)

    // #179 — detail state
    private val _selectedCoreMemoryDetail = MutableStateFlow<CoreMemoryEntity?>(null)
    private val _selectedEpisodicMemoryDetail = MutableStateFlow<EpisodicMemoryEntity?>(null)

    // #164 — embedding stats (reactive: Room re-emits on every write to message_embeddings)
    val embeddingStats: StateFlow<MessageEmbeddingStats> =
        combine(
            embeddingDao.observeCount(),
            embeddingDao.observeDistinctConversationCount(),
        ) { count, conversations ->
            MessageEmbeddingStats(messageCount = count, conversationCount = conversations)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MessageEmbeddingStats(),
        )

    private val conversationTitlesFlow = conversationRepository.observeConversations()
        .map { list: List<ConversationEntity> -> list.associate { it.id to it.title } }

    val uiState: StateFlow<MemoryUiState> =
        // Step 1: innermost (coreMemories, episodicMemories, dialogState, isSubmitting)
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
            // Step 2: + pendingDeleteId, pendingDeleteEpisodicId
            _pendingDeleteId,
            _pendingDeleteEpisodicId,
        ) { base, pendingDeleteId, pendingDeleteEpisodicId ->
            base.copy(
                pendingDeleteId = pendingDeleteId,
                pendingDeleteEpisodicId = pendingDeleteEpisodicId,
            )
        }
        // Step 3: + conversationTitles
        .combine(conversationTitlesFlow) { base, conversationTitles ->
            base.copy(conversationTitles = conversationTitles)
        }
        // Step 4: + core selection state + embeddingStats
        .combine(
            combine(_isInSelectionMode, _selectedCoreIds, _showBulkDeleteConfirmation, embeddingStats)
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
        }
        // Step 5: + search/expand state (with filtering) — #175
        .combine(
            combine(_expandedSections, _globalSearch, _coreSearch, _episodicSearch) { expanded, global, core, episodic ->
                SearchExpandState(expanded, global, core, episodic)
            }
        ) { base, searchState ->
            val coreFilterActive = searchState.globalSearch.isNotBlank() || searchState.coreSearch.isNotBlank()
            val filteredCore = base.coreMemories.filter { m ->
                !coreFilterActive ||
                (searchState.globalSearch.isNotBlank() && m.content.contains(searchState.globalSearch, ignoreCase = true)) ||
                (searchState.coreSearch.isNotBlank() && m.content.contains(searchState.coreSearch, ignoreCase = true))
            }
            val episodicFilterActive = searchState.globalSearch.isNotBlank() || searchState.episodicSearch.isNotBlank()
            val filteredEpisodic = base.episodicMemories.filter { m ->
                !episodicFilterActive ||
                (searchState.globalSearch.isNotBlank() && m.content.contains(searchState.globalSearch, ignoreCase = true)) ||
                (searchState.episodicSearch.isNotBlank() && m.content.contains(searchState.episodicSearch, ignoreCase = true))
            }
            // Auto-expand sections when global search is active
            val expandedSections = if (searchState.globalSearch.isBlank()) {
                searchState.expandedSections
            } else {
                searchState.expandedSections.toMutableSet().apply {
                    if (filteredCore.isNotEmpty()) add(MemorySection.CORE)
                    if (filteredEpisodic.isNotEmpty()) add(MemorySection.EPISODIC)
                }
            }
            base.copy(
                coreMemories = filteredCore,
                episodicMemories = filteredEpisodic,
                episodicCount = filteredEpisodic.size,
                expandedSections = expandedSections,
                globalSearch = searchState.globalSearch,
                coreSearch = searchState.coreSearch,
                episodicSearch = searchState.episodicSearch,
            )
        }
        // Step 6: + episodic selection state — #176
        .combine(
            combine(_isInEpisodicSelectionMode, _selectedEpisodicIds, _showEpisodicBulkDeleteConfirmation)
            { inEpisodicSelection, selectedEpisodicIds, showEpisodicBulkConfirm ->
                Triple(inEpisodicSelection, selectedEpisodicIds, showEpisodicBulkConfirm)
            }
        ) { base, (inEpisodicSelection, selectedEpisodicIds, showEpisodicBulkConfirm) ->
            base.copy(
                isInEpisodicSelectionMode = inEpisodicSelection,
                selectedEpisodicIds = selectedEpisodicIds,
                showEpisodicBulkDeleteConfirmation = showEpisodicBulkConfirm,
            )
        }
        // Step 7: + detail state — #179
        .combine(
            combine(_selectedCoreMemoryDetail, _selectedEpisodicMemoryDetail)
            { coreDetail, episodicDetail -> coreDetail to episodicDetail }
        ) { base, (coreDetail, episodicDetail) ->
            base.copy(
                selectedCoreMemoryDetail = coreDetail,
                selectedEpisodicMemoryDetail = episodicDetail,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MemoryUiState(),
        )

    // ── Dialog state ───────────────────────────────────────────────────────

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

    // ── #110 Core bulk selection ───────────────────────────────────────────

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
        if (_selectedCoreIds.value.isEmpty()) {
            _isInSelectionMode.value = false
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

    // ── #175 Collapsible sections + search ────────────────────────────────

    fun toggleSection(section: MemorySection) {
        _expandedSections.update { current ->
            if (section in current) current - section else current + section
        }
    }

    fun updateGlobalSearch(q: String) { _globalSearch.value = q }
    fun updateCoreSearch(q: String) { _coreSearch.value = q }
    fun updateEpisodicSearch(q: String) { _episodicSearch.value = q }

    // ── #176 Episodic bulk selection ──────────────────────────────────────

    fun enterEpisodicSelectionMode(id: String) {
        _isInEpisodicSelectionMode.value = true
        _selectedEpisodicIds.update { it + id }
    }

    fun toggleEpisodicSelection(id: String) {
        _selectedEpisodicIds.update { current ->
            if (id in current) current - id else current + id
        }
        if (_selectedEpisodicIds.value.isEmpty()) {
            _isInEpisodicSelectionMode.value = false
        }
    }

    fun selectAllEpisodic(allIds: List<String>) {
        _selectedEpisodicIds.value = allIds.toSet()
    }

    fun clearEpisodicSelection() {
        _isInEpisodicSelectionMode.value = false
        _selectedEpisodicIds.value = emptySet()
        _showEpisodicBulkDeleteConfirmation.value = false
    }

    fun requestEpisodicBulkDelete() {
        if (_selectedEpisodicIds.value.isNotEmpty()) {
            _showEpisodicBulkDeleteConfirmation.value = true
        }
    }

    fun dismissEpisodicBulkDeleteConfirmation() {
        _showEpisodicBulkDeleteConfirmation.value = false
    }

    fun deleteSelectedEpisodic() {
        val ids = _selectedEpisodicIds.value.toList()
        viewModelScope.launch {
            try {
                ids.forEach { id -> memoryRepository.deleteEpisodicMemory(id) }
            } finally {
                _showEpisodicBulkDeleteConfirmation.value = false
                _isInEpisodicSelectionMode.value = false
                _selectedEpisodicIds.value = emptySet()
            }
        }
    }

    // ── #179 Memory detail/edit ───────────────────────────────────────────

    fun openCoreMemoryDetail(memory: CoreMemoryEntity) { _selectedCoreMemoryDetail.value = memory }
    fun closeCoreMemoryDetail() { _selectedCoreMemoryDetail.value = null }
    fun openEpisodicMemoryDetail(memory: EpisodicMemoryEntity) { _selectedEpisodicMemoryDetail.value = memory }
    fun closeEpisodicMemoryDetail() { _selectedEpisodicMemoryDetail.value = null }

    fun saveCoreMemoryEdit(id: String, newContent: String) {
        viewModelScope.launch {
            try {
                memoryRepository.updateCoreMemory(id, newContent)
                _selectedCoreMemoryDetail.value = null
            } catch (e: Exception) {
                Log.e("KernelAI", "saveCoreMemoryEdit failed", e)
            }
        }
    }
}
