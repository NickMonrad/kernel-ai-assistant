package com.kernel.ai.feature.chat.model

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel

sealed interface ChatUiState {
    data object Loading : ChatUiState

    data class Ready(
        val conversationId: String,
        val conversationTitle: String?,
        val messages: List<ChatMessage>,
        val isGenerating: Boolean,
        val inputText: String,
        val error: String?,
        val isLoadingModel: Boolean = false,
        /** Whether to show the model's thinking process tokens in the chat UI. */
        val showThinkingProcess: Boolean = true,
    ) : ChatUiState

    /** Models need to be downloaded before chatting. */
    data class ModelsNotReady(
        val isDownloading: Boolean,
        /** Per-model download progress, ordered by priority (required first). */
        val modelProgress: List<ModelDownloadProgress> = emptyList(),
    ) : ChatUiState

    data class ModelDownloadProgress(
        val model: KernelModel,
        val displayName: String,
        val sizeLabel: String,
        val state: DownloadState,
    )
}
