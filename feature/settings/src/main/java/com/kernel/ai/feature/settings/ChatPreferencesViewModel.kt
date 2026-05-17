package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.prefs.ChatPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatPreferencesViewModel @Inject constructor(
    private val chatPreferences: ChatPreferences,
) : ViewModel() {

    val archiveRetentionDays: StateFlow<Int> = chatPreferences.archiveRetentionDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7)

    fun setArchiveRetentionDays(days: Int) {
        viewModelScope.launch {
            chatPreferences.setArchiveRetentionDays(days)
        }
    }
}
