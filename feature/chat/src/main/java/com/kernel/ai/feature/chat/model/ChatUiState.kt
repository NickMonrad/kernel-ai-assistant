package com.kernel.ai.feature.chat.model

import com.kernel.ai.core.inference.download.DownloadState

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
    data class ModelsNotReady(
        val isDownloading: Boolean,
        /** Per-model download progress, ordered by priority (required first). */
        val modelProgress: List<ModelDownloadProgress> = emptyList(),
    ) : ChatUiState

    data class ModelDownloadProgress(
        val displayName: String,
        val sizeLabel: String,
        val state: DownloadState,
    )
}
