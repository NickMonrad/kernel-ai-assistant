package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.repository.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    data class MemoryUiState(
        val coreMemories: List<CoreMemoryEntity> = emptyList(),
        val episodicCount: Int = 0,
        val isAddDialogOpen: Boolean = false,
        val addDialogText: String = "",
        val showClearConfirmation: Boolean = false,
        val isSubmitting: Boolean = false,
        val pendingDeleteId: String? = null,
    )

    private val _dialogState = MutableStateFlow(
        Triple(/* isAddDialogOpen */ false, /* addDialogText */ "", /* showClearConfirmation */ false)
    )
    private val _isSubmitting = MutableStateFlow(false)
    private val _pendingDeleteId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MemoryUiState> = combine(
        combine(
            memoryRepository.observeCoreMemories(),
            memoryRepository.observeEpisodicCount(),
            _dialogState,
            _isSubmitting,
        ) { coreMemories, episodicCount, (isAddDialogOpen, addDialogText, showClearConfirmation), isSubmitting ->
            MemoryUiState(
                coreMemories = coreMemories,
                episodicCount = episodicCount,
                isAddDialogOpen = isAddDialogOpen,
                addDialogText = addDialogText,
                showClearConfirmation = showClearConfirmation,
                isSubmitting = isSubmitting,
            )
        },
        _pendingDeleteId,
    ) { base, pendingDeleteId ->
        base.copy(pendingDeleteId = pendingDeleteId)
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
}
