package com.kernel.ai.feature.chat.model

sealed interface ChatUiState {
    data object Loading : ChatUiState

    data class Ready(
        val conversationId: String,
        val messages: List<ChatMessage>,
        val isGenerating: Boolean,
        val inputText: String,
        val error: String?,
    ) : ChatUiState

    /** Models need to be downloaded before chatting. */
    data class ModelsNotReady(val isDownloading: Boolean) : ChatUiState
}
