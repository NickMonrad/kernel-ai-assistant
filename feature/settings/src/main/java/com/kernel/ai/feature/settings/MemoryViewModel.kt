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
    )

    private val _dialogState = MutableStateFlow(
        Triple(/* isAddDialogOpen */ false, /* addDialogText */ "", /* showClearConfirmation */ false)
    )

    val uiState: StateFlow<MemoryUiState> = combine(
        memoryRepository.observeCoreMemories(),
        memoryRepository.observeEpisodicCount(),
        _dialogState,
    ) { coreMemories, episodicCount, (isAddDialogOpen, addDialogText, showClearConfirmation) ->
        MemoryUiState(
            coreMemories = coreMemories,
            episodicCount = episodicCount,
            isAddDialogOpen = isAddDialogOpen,
            addDialogText = addDialogText,
            showClearConfirmation = showClearConfirmation,
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
        val text = _dialogState.value.second.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            memoryRepository.addCoreMemory(content = text, source = "user")
            dismissAddDialog()
        }
    }

    fun deleteCoreMemory(id: String) {
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
            memoryRepository.clearEpisodicMemories()
            _dialogState.update { it.copy(third = false) }
        }
    }
}
