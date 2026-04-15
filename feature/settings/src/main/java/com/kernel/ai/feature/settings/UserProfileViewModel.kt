package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.profile.UserProfileYaml
import com.kernel.ai.core.memory.repository.UserProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = "",
        )

    val structuredProfile: StateFlow<UserProfileYaml?> = repository.observeStructured()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun save(text: String) {
        viewModelScope.launch { repository.save(text) }
    }

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }
}
