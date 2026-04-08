package com.kernel.ai.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ConversationRepository,
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> = repository
        .observeConversations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

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
}
