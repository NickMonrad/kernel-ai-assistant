package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.profile.UserProfileYaml
import com.kernel.ai.core.memory.repository.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val repository: UserProfileRepository,
) : ViewModel() {

    val maxLength: Int = repository.maxLength

    val profileText: StateFlow<String> = repository.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = "",
        )

    val structuredProfile: StateFlow<UserProfileYaml?> = repository.observeStructured()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /** True while a save operation is in progress. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** One-shot save result events for transient UI feedback. */
    private val _saveResult = MutableSharedFlow<SaveResult>(extraBufferCapacity = 1)
    val saveResult: SharedFlow<SaveResult> = _saveResult.asSharedFlow()

    fun save(text: String) {
        if (_saving.value) return // prevent duplicate taps
        viewModelScope.launch {
            _saving.value = true
            try {
                repository.save(text)
                _saveResult.tryEmit(SaveResult.Success)
            } catch (e: Exception) {
                _saveResult.tryEmit(SaveResult.Error(e.message ?: "Save failed"))
            } finally {
                _saving.value = false
            }
        }
    }

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }
}

sealed interface SaveResult {
    data object Success : SaveResult
    data class Error(val message: String) : SaveResult
}
